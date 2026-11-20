package com.nytefall.nyterecorder

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * Screen -> VirtualDisplay -> encoder input Surface (zero CPU copies) -> MediaMuxer, plus an AAC audio
 * path fed by AudioPlaybackCapture (and optionally the mic). Everything runs on its own threads.
 */
class RecordingEngine(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val settings: RecSettings,
    private val onFinished: (uri: Uri?, error: String?) -> Unit,
) {
    companion object {
        private const val TAG = "NyteRecorder"
        private const val VIDEO = 0
        private const val AUDIO = 1
        private const val SAMPLE_RATE = 48000
        private const val AUDIO_BITRATE = 192_000
        private const val FRAME_SAMPLES = 1024 // one AAC frame
        private const val CHUNK_BYTES = FRAME_SAMPLES * 4 // 16-bit stereo
    }

    private val stopped = AtomicBoolean(false)
    @Volatile private var audioStop = false

    private var uri: Uri? = null
    private var pfd: ParcelFileDescriptor? = null
    private var sink: MuxerSink? = null
    private var venc: MediaCodec? = null
    private var aenc: MediaCodec? = null
    private var surface: Surface? = null
    private var vdisplay: VirtualDisplay? = null
    private var internalRec: AudioRecord? = null
    private var micRec: AudioRecord? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var callback: MediaProjection.Callback? = null

    var plan: CodecSupport.Plan? = null
        private set
    val notes = ArrayList<String>()

    /** Sets everything up and starts recording. Throws on a fatal error (caller cleans up via [stop]). */
    fun start() {
        val p = CodecSupport.plan(ctx, settings)
        plan = p
        notes += p.notes

        // --- Output file in Movies/NyteRecorder (MediaStore, no storage permission needed) ---
        val name = "NyteRec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/NyteRecorder")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val u = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create output file")
        uri = u
        pfd = resolver.openFileDescriptor(u, "rw") ?: error("Cannot open output file")
        val muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // --- Audio (optional, failure is non-fatal) ---
        val audioOk = setupAudio()
        sink = MuxerSink(muxer, if (audioOk) 2 else 1)

        // --- Video encoder ---
        setupVideoEncoder(p)

        // --- Projection + VirtualDisplay (callback must be registered first on Android 14+) ---
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                stop()
            }
        }
        callback = cb
        projection.registerCallback(cb, Handler(Looper.getMainLooper()))
        vdisplay = projection.createVirtualDisplay(
            "NyteRecorder", p.width, p.height, p.dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
        )

        videoThread = Thread({ videoLoop() }, "nr-video").also { it.start() }
        if (audioOk) audioThread = Thread({ audioLoop() }, "nr-audio").also { it.start() }
    }

    // ------------------------------------------------------------------ video

    private fun setupVideoEncoder(p: CodecSupport.Plan) {
        fun create(): MediaCodec = if (p.codecName != null) MediaCodec.createByCodecName(p.codecName) else MediaCodec.createEncoderByType(p.mime)

        fun format(full: Boolean): MediaFormat = MediaFormat.createVideoFormat(p.mime, p.width, p.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, p.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, p.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (full) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
                setInteger(MediaFormat.KEY_LATENCY, 1) // low latency
                // If the display runs faster than the target (e.g. a 120 Hz game), drop frames before the encoder.
                setFloat("max-fps-to-encoder", p.fps.toFloat())
                if (p.mime == Codec.AVC.mime) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                } else {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                }
            }
        }

        var enc = create()
        try {
            enc.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(TAG, "Full encoder config rejected, retrying with basic config", e)
            notes += "Encoder rejected some tuning options, using basic config."
            try { enc.release() } catch (_: Throwable) {}
            enc = create()
            enc.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        surface = enc.createInputSurface()
        enc.start()
        venc = enc
        Log.i(TAG, "Video encoder ${enc.name} ${p.width}x${p.height}@${p.fps} ${p.bitrate / 1000}kbps")
    }

    private fun videoLoop() {
        val enc = venc ?: return
        val info = MediaCodec.BufferInfo()
        var offsetUs: Long? = null
        try {
            while (true) {
                val idx = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> sink?.addTrack(VIDEO, enc.outputFormat)
                    idx >= 0 -> {
                        val buf = enc.getOutputBuffer(idx)
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            if (offsetUs == null) {
                                // Surface timestamps are normally CLOCK_MONOTONIC. If the codec rebased them, realign.
                                val nowUs = System.nanoTime() / 1000
                                offsetUs = if (abs(info.presentationTimeUs - nowUs) < 10_000_000) 0L else nowUs - info.presentationTimeUs
                            }
                            sink?.write(VIDEO, buf, info.offset, info.size, info.presentationTimeUs + offsetUs, info.flags)
                        }
                        enc.releaseOutputBuffer(idx, false)
                        if (eos) break
                    }
                }
            }
        } catch (e: Exception) {
            if (!stopped.get()) Log.e(TAG, "Video loop failed", e)
        }
    }

    // ------------------------------------------------------------------ audio

    private fun setupAudio(): Boolean {
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            notes += "No microphone/audio permission: recording without audio."
            return false
        }
        try {
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = max(minBuf, CHUNK_BYTES * 8)
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            @Suppress("MissingPermission")
            val rec = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufSize)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                notes += "Internal audio capture failed to initialize."
                return false
            }
            internalRec = rec

            if (settings.mic) {
                try {
                    @Suppress("MissingPermission")
                    val m = AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(bufSize)
                        .build()
                    if (m.state == AudioRecord.STATE_INITIALIZED) micRec = m else m.release()
                } catch (e: Exception) {
                    notes += "Microphone unavailable, recording internal audio only."
                }
            }

            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_BYTES * 2)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            aenc = enc
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Audio setup failed", e)
            notes += "Audio setup failed (${e.javaClass.simpleName}): recording video only."
            releaseAudio()
            return false
        }
    }

    private fun audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rec = internalRec ?: return
        val enc = aenc ?: return
        val mic = micRec
        val info = MediaCodec.BufferInfo()
        val pcm = ByteArray(CHUNK_BYTES)
        val micBuf = ByteArray(CHUNK_BYTES)
        val micFifo = ByteArray(CHUNK_BYTES * 16)
        var micCount = 0
        val chunkUs = FRAME_SAMPLES * 1_000_000L / SAMPLE_RATE
        var samples = 0L
        var anchorUs = 0L
        var first = true
        var lastOutPts = Long.MIN_VALUE

        fun drain(eos: Boolean) {
            var spins = 0
            while (true) {
                val idx = enc.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> sink?.addTrack(AUDIO, enc.outputFormat)
                    idx >= 0 -> {
                        val buf = enc.getOutputBuffer(idx)
                        val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            sink?.write(AUDIO, buf, info.offset, info.size, info.presentationTimeUs, info.flags)
                            lastOutPts = info.presentationTimeUs
                        }
                        enc.releaseOutputBuffer(idx, false)
                        if (end) return
                    }
                    else -> if (!eos || ++spins > 50) return
                }
            }
        }

        try {
            rec.startRecording()
            mic?.startRecording()
            while (!audioStop) {
                // Blocking read of exactly one AAC frame of internal audio.
                var got = 0
                while (got < CHUNK_BYTES && !audioStop) {
                    val n = rec.read(pcm, got, CHUNK_BYTES - got)
                    if (n < 0) throw IllegalStateException("AudioRecord.read error $n")
                    got += n
                }
                if (got < CHUNK_BYTES) break
                val nowUs = System.nanoTime() / 1000

                if (first) {
                    first = false
                    var a = nowUs - chunkUs
                    val ts = AudioTimestamp()
                    if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        val candidate = ts.nanoTime / 1000 - ts.framePosition * 1_000_000L / SAMPLE_RATE
                        if (abs(candidate - a) < 500_000) a = candidate
                    }
                    anchorUs = a
                } else {
                    // If capture stalled (nothing playing), jump the clock forward instead of drifting behind.
                    val expected = anchorUs + samples * 1_000_000L / SAMPLE_RATE
                    val actual = nowUs - chunkUs
                    if (actual - expected > 200_000) anchorUs += actual - expected
                }
                val pts = anchorUs + samples * 1_000_000L / SAMPLE_RATE
                samples += FRAME_SAMPLES

                if (mic != null) {
                    val n = mic.read(micBuf, 0, micBuf.size, AudioRecord.READ_NON_BLOCKING)
                    if (n > 0) {
                        if (micCount + n > micFifo.size) { // overflow: drop oldest
                            val drop = micCount + n - micFifo.size
                            System.arraycopy(micFifo, drop, micFifo, 0, micCount - drop)
                            micCount -= drop
                        }
                        System.arraycopy(micBuf, 0, micFifo, micCount, n)
                        micCount += n
                    }
                    val take = minOf(micCount, CHUNK_BYTES)
                    if (take > 0) {
                        mix(pcm, micFifo, take)
                        System.arraycopy(micFifo, take, micFifo, 0, micCount - take)
                        micCount -= take
                    }
                }

                val ii = enc.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val ib = enc.getInputBuffer(ii)!!
                    ib.clear()
                    ib.put(pcm, 0, CHUNK_BYTES)
                    enc.queueInputBuffer(ii, 0, CHUNK_BYTES, pts, 0)
                }
                drain(false)
            }
            // End of stream
            val ii = enc.dequeueInputBuffer(50_000)
            if (ii >= 0) {
                val pts = anchorUs + samples * 1_000_000L / SAMPLE_RATE
                enc.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(true)
            }
        } catch (e: Exception) {
            if (!stopped.get()) Log.e(TAG, "Audio loop failed", e)
        }
    }

    /** Adds [len] bytes of 16-bit LE mic samples onto [dst] with clipping. */
    private fun mix(dst: ByteArray, src: ByteArray, len: Int) {
        val d = ByteBuffer.wrap(dst).order(ByteOrder.LITTLE_ENDIAN)
        val s = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i + 1 < len) {
            val v = d.getShort(i) + s.getShort(i)
            d.putShort(i, v.coerceIn(-32768, 32767).toShort())
            i += 2
        }
    }

    private fun releaseAudio() {
        try { internalRec?.release() } catch (_: Throwable) {}
        try { micRec?.release() } catch (_: Throwable) {}
        try { aenc?.release() } catch (_: Throwable) {}
        internalRec = null; micRec = null; aenc = null
    }

    // ------------------------------------------------------------------ stop

    /** Idempotent. Finalizes the file on a background thread, then calls [onFinished]. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        Thread({ finalizeAll() }, "nr-finalize").start()
    }

    private fun finalizeAll() {
        var error: String? = null
        try { vdisplay?.release() } catch (_: Throwable) {}
        try { callback?.let { projection.unregisterCallback(it) } } catch (_: Throwable) {}
        try { projection.stop() } catch (_: Throwable) {}
        try { venc?.signalEndOfInputStream() } catch (_: Throwable) {}
        audioStop = true
        videoThread?.join(3000)
        audioThread?.let {
            it.join(1500)
            if (it.isAlive) {
                try { internalRec?.stop() } catch (_: Throwable) {}
                it.join(1500)
            }
        }
        val s = sink
        val wrote = s?.wroteSamples == true
        try { s?.finish() } catch (e: Throwable) { error = e.message }
        try { pfd?.close() } catch (_: Throwable) {}
        try { surface?.release() } catch (_: Throwable) {}
        try { venc?.stop() } catch (_: Throwable) {}
        try { venc?.release() } catch (_: Throwable) {}
        try { internalRec?.stop() } catch (_: Throwable) {}
        try { micRec?.stop() } catch (_: Throwable) {}
        releaseAudio()

        val u = uri
        var result: Uri? = null
        if (u != null) {
            try {
                if (wrote) {
                    val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    ctx.contentResolver.update(u, v, null, null)
                    result = u
                } else {
                    ctx.contentResolver.delete(u, null, null)
                    if (error == null) error = "Nothing was recorded."
                }
            } catch (e: Throwable) {
                error = e.message
            }
        }
        onFinished(result, error)
    }
}
