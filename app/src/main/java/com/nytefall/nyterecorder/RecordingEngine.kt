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
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Display
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
 * A diagnostic log is kept in memory (aggregated per second) and written once at the end.
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

    // ---- diagnostics (all in memory) ----
    private class VStats {
        var frames = 0; var keyFrames = 0; var bytes = 0L
        var firstPts = -1L; var lastPts = -1L
        var minIv = Long.MAX_VALUE; var maxIv = 0L; var sumIv = 0L; var nIv = 0; var hitches = 0
        val perSecFrames = ArrayList<Int>(); val perSecBytes = ArrayList<Long>()
        var hitchLines = 0
    }

    private class AStats {
        var chunks = 0L; var silentChunks = 0L; var readErrors = 0; var stallJumps = 0; var stallTotalUs = 0L
        var inputMiss = 0; var aacBuffers = 0L; var aacBytes = 0L
        var micChunks = 0L; var micShort = 0L; var micOverflows = 0
        var maxReadGapUs = 0L; var slowReads = 0; var lastReadAtUs = 0L
        var firstPtsUs = Long.MIN_VALUE
        var timestampAnchor = "n/a"
    }

    private val head = RecLog()
    private val log = RecLog()
    private val vs = VStats()
    private val aus = AStats()
    private var baseName = "NyteRec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private var stopReason = "n/a"
    private var armedAtMs = -1L
    private var waitedMs = -1L
    private var recStartMs = -1L
    private var batteryStart = ""
    private var maxBatteryC = Double.NaN
    private var lastRotation = -1
    private var lastDisplaySize = ""
    private var rotListener: DisplayManager.DisplayListener? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var vMetrics = ""
    private var aMetrics = ""
    private var videoFormatLines = ""
    private var usedMode = "n/a"
    private var repeatUsed = "n/a"
    private var audioWanted = false

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
    private var effFps = 60
    private var startError: String? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    @Volatile var waiting = false
        private set
    val notes = ArrayList<String>()

    private fun note(msg: String) {
        notes += msg
        log.line("NOTE: $msg")
    }

    private fun displayDesc(): String {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val nd = CodecSupport.nativeDisplay(ctx)
        return "rotation=${d.rotation} ${if (nd.landscape) "landscape" else "portrait"} real=${if (nd.landscape) nd.long else nd.short}x${if (nd.landscape) nd.short else nd.long} refresh=${"%.1f".format(Locale.US, d.refreshRate)}Hz"
    }

    /**
     * Starts now, or - when the canvas must be landscape but the screen is currently portrait - arms
     * and waits (cheaply, via a DisplayListener) until the screen becomes landscape. That way the
     * recording never begins with a portrait screen squeezed into a landscape frame.
     */
    fun begin(onStarted: () -> Unit) {
        head.raw("Nyte Recorder diagnostic log")
        head.raw("Recording: $baseName")
        head.raw("Written: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        RecLog.deviceInfo(ctx).forEach { head.raw(it) }
        head.raw("Settings: res=${settings.res} fps=${settings.fps} bitrate=${settings.bitrateMbps}Mbps codec=${settings.codec} orientation=${settings.orient} mic=${settings.mic} overlay=${settings.overlay}")
        log.line("begin(): ${displayDesc()}")
        log.line("start state: ${RecLog.thermalBattery(ctx)}")

        val dm = ctx.getSystemService(DisplayManager::class.java)
        // Always-on, cheap: log rotation / size changes for the whole session.
        val rl = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val d = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
                val desc = displayDesc()
                if (d.rotation != lastRotation || desc != lastDisplaySize) {
                    lastRotation = d.rotation; lastDisplaySize = desc
                    log.line("display changed: $desc")
                }
            }
        }
        rotListener = rl
        dm.registerDisplayListener(rl, Handler(Looper.getMainLooper()))

        val needLandscape = settings.orient == Orient.LANDSCAPE
        if (!needLandscape || CodecSupport.nativeDisplay(ctx).landscape) {
            start()
            onStarted()
            return
        }
        waiting = true
        armedAtMs = log.elapsedMs()
        log.line("armed: screen is portrait, waiting for landscape")
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY || stopped.get() || !waiting) return
                if (!CodecSupport.nativeDisplay(ctx).landscape) return
                waiting = false
                waitedMs = log.elapsedMs() - armedAtMs
                log.line("landscape reached after waiting $waitedMs ms; starting")
                dm.unregisterDisplayListener(this)
                displayListener = null
                try {
                    start()
                    onStarted()
                } catch (e: Exception) {
                    log.error("start after waiting failed", e)
                    startError = "Could not start: ${e.message ?: e.javaClass.simpleName}"
                    stop("start failed")
                }
            }
        }
        displayListener = l
        dm.registerDisplayListener(l, Handler(Looper.getMainLooper()))
        // Needed on Android 14+ before a projection can sit idle; harmless earlier.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stop("MediaProjection.onStop while waiting") }
        }, Handler(Looper.getMainLooper()))
    }

    /** Sets everything up and starts recording. Throws on a fatal error (caller cleans up via [stop]). */
    fun start() {
        try {
            doStart()
        } catch (e: Exception) {
            log.error("start() failed", e)
            throw e
        }
    }

    private fun doStart() {
        baseName = "NyteRec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        head.raw("Recording (final name): $baseName")
        val p = CodecSupport.plan(ctx, settings)
        plan = p
        notes += p.notes
        p.notes.forEach { log.line("plan note: $it") }
        log.line("plan: ${p.width}x${p.height} @${p.fps}fps ${p.bitrate / 1000}kbps mime=${p.mime} encoder=${p.codecName ?: "NONE(system default)"} dpi=${p.dpi} reportedMaxFps=${p.maxFps} bitrateMode=${modeName(p.bitrateMode)} align=${p.alignment}")
        log.raw("  hardware ${p.mime} encoders on device: " + CodecSupport.listEncoders(p.mime).joinToString(" | "))
        p.codecName?.let { n ->
            MediaCodecList_find(n)?.let { info -> CodecSupport.describe(info, p.mime, p.width, p.height).forEach { log.raw("  $it") } }
        }

        // --- Output file in Movies/NyteRecorder (MediaStore, no storage permission needed) ---
        val name = "$baseName.mp4"
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
        log.line("output: Movies/NyteRecorder/$name")

        // --- Audio (optional, failure is non-fatal) ---
        val audioOk = setupAudio()
        sink = MuxerSink(muxer, if (audioOk) 2 else 1)
        audioWanted = audioOk
        log.line("audio ${if (audioOk) "enabled (internal${if (micRec != null) " + mic" else ""})" else "DISABLED, video only"}")

        // --- Video encoder ---
        setupVideoEncoder(p)

        // thermal events
        try {
            val pm = ctx.getSystemService(PowerManager::class.java)
            val tl = PowerManager.OnThermalStatusChangedListener { st -> log.line("thermal status -> ${RecLog.thermalName(st)} | ${RecLog.thermalBattery(ctx)}") }
            thermalListener = tl
            pm.addThermalStatusListener(ctx.mainExecutor, tl)
        } catch (e: Throwable) {
            log.line("thermal listener unavailable: $e")
        }

        // --- Projection + VirtualDisplay (callback must be registered first on Android 14+) ---
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                stop("MediaProjection.onStop (system or user ended the projection)")
            }
        }
        callback = cb
        projection.registerCallback(cb, Handler(Looper.getMainLooper()))
        log.line("creating VirtualDisplay ${p.width}x${p.height} dpi=${p.dpi} flags=AUTO_MIRROR; ${displayDesc()}")
        vdisplay = projection.createVirtualDisplay(
            "NyteRecorder", p.width, p.height, p.dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
        )

        recStartMs = log.elapsedMs()
        batteryStart = RecLog.thermalBattery(ctx)
        maxBatteryC = RecLog.batteryTempC(ctx) ?: Double.NaN
        log.line("RECORDING STARTED: $batteryStart")
        videoThread = Thread({ videoLoop() }, "nr-video").also { it.start() }
        if (audioOk) audioThread = Thread({ audioLoop() }, "nr-audio").also { it.start() }
    }

    private fun MediaCodecList_find(name: String): MediaCodecInfo? =
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == name }

    // ------------------------------------------------------------------ video

    private fun setupVideoEncoder(p: CodecSupport.Plan) {
        fun create(): MediaCodec = if (p.codecName != null) MediaCodec.createByCodecName(p.codecName) else MediaCodec.createEncoderByType(p.mime)

        // Tuned config. Deliberately NO KEY_PRIORITY(realtime) / KEY_LATENCY(low-latency): they can push the
        // Unisoc Codec2 encoder into modes that hurt quality/steadiness and aren't needed for file recording.
        fun format(full: Boolean, fps: Int, mode: Int, repeat: Boolean): MediaFormat = MediaFormat.createVideoFormat(p.mime, p.width, p.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, p.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (full) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
                setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
                // Cap frames before the encoder so the output is a steady <= fps (e.g. 60/120 Hz game -> 30).
                setFloat("max-fps-to-encoder", fps.toFloat())
                // Constant-ish frame rate: when the screen is static, resubmit the last frame after one frame
                // interval (handled by the framework's surface-input path, no extra work for the game).
                if (repeat) setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    if (p.mime == Codec.AVC.mime) MediaCodecInfo.CodecProfileLevel.AVCProfileHigh else MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
                )
            }
        }

        // Try: tuned with CBR (if supported) -> tuned with VBR -> basic -> (only then, if >30) basic at 30 fps.
        class Attempt(val full: Boolean, val fps: Int, val mode: Int, val repeat: Boolean = true)
        val attempts = ArrayList<Attempt>()
        if (p.bitrateMode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) attempts += Attempt(true, p.fps, p.bitrateMode)
        attempts += Attempt(true, p.fps, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        attempts += Attempt(true, p.fps, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, repeat = false)
        attempts += Attempt(false, p.fps, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        if (p.fps > 30) attempts += Attempt(false, 30, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        var lastErr: Exception? = null
        var n = 0
        for (at in attempts) {
            n++
            val fmt = format(at.full, at.fps, at.mode, at.repeat)
            log.line("encoder attempt #$n (${if (at.full) "tuned, mode=${modeName(at.mode)}" else "basic (encoder default mode)"}, ${at.fps}fps): configure $fmt")
            val enc = create()
            try {
                enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = enc.createInputSurface()
                enc.start()
                venc = enc
                effFps = at.fps
                usedMode = if (at.full) modeName(at.mode) else "encoder default (basic config)"
                if (!at.full) note("Encoder rejected some tuning options, using basic config.")
                else if (at.mode != p.bitrateMode) note("CBR rejected by the encoder; using VBR (actual bitrate may undershoot the selected value).")
                if (at.fps != p.fps) note("Encoder refused ${p.fps} fps at ${p.width}x${p.height}; recording at ${at.fps} fps.")
                repeatUsed = if (at.full && at.repeat) "${1_000_000L / at.fps} us (accepted by configure; applied by the framework's surface-input path)" else "off"
                if (at.full && !at.repeat) note("Encoder rejected repeat-previous-frame; output frame rate follows screen changes (variable).")
                log.line("encoder attempt #$n OK: ${enc.name}; bitrate mode used: $usedMode; repeat-previous-frame-after: $repeatUsed")
                videoFormatLines = "configured: $fmt\naccepted (inputFormat): ${runCatching { enc.inputFormat }.getOrNull()}"
                log.raw("  accepted inputFormat: ${runCatching { enc.inputFormat }.getOrNull()}")
                Log.i(TAG, "Video encoder ${enc.name} ${p.width}x${p.height}@${at.fps} ${p.bitrate / 1000}kbps mode=$usedMode")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Encoder config failed (attempt $n)", e)
                log.error("encoder attempt #$n failed", e)
                lastErr = e
                try { enc.release() } catch (_: Throwable) {}
            }
        }
        throw lastErr ?: IllegalStateException("Encoder setup failed")
    }

    private fun modeName(m: Int) = when (m) {
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR -> "CBR"
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR -> "VBR"
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ -> "CQ"
        else -> "mode$m"
    }

    private fun videoLoop() {
        val enc = venc ?: return
        val info = MediaCodec.BufferInfo()
        var offsetUs: Long? = null
        val expectedIv = 1_000_000L / max(1, effFps)
        var nextBatteryAt = SystemClock.elapsedRealtime() + 15_000
        try {
            while (true) {
                val idx = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        log.line("video outputFormat: ${enc.outputFormat}")
                        videoFormatLines += "\nfinal outputFormat: ${enc.outputFormat}"
                        sink?.addTrack(VIDEO, enc.outputFormat)
                    }
                    idx >= 0 -> {
                        val buf = enc.getOutputBuffer(idx)
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            if (offsetUs == null) {
                                // Surface timestamps are normally CLOCK_MONOTONIC. If the codec rebased them, realign.
                                val nowUs = System.nanoTime() / 1000
                                offsetUs = if (abs(info.presentationTimeUs - nowUs) < 10_000_000) 0L else nowUs - info.presentationTimeUs
                                log.line("first video frame: pts=${info.presentationTimeUs}us, monotonic now=${nowUs}us, clock offset applied=${offsetUs}us (${if (offsetUs == 0L) "surface pts are CLOCK_MONOTONIC" else "codec rebased pts"})")
                            }
                            val pts = info.presentationTimeUs + offsetUs
                            record(pts, info.size, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, expectedIv)
                            sink?.write(VIDEO, buf, info.offset, info.size, pts, info.flags)
                        }
                        enc.releaseOutputBuffer(idx, false)
                        if (eos) break
                    }
                }
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs >= nextBatteryAt) {
                    nextBatteryAt = nowMs + 15_000
                    RecLog.batteryTempC(ctx)?.let { if (maxBatteryC.isNaN() || it > maxBatteryC) maxBatteryC = it }
                    log.line("periodic: ${RecLog.thermalBattery(ctx)}")
                }
            }
        } catch (e: Exception) {
            if (!stopped.get()) { Log.e(TAG, "Video loop failed", e); log.error("video loop failed", e) }
        }
    }

    private fun record(ptsUs: Long, size: Int, key: Boolean, expectedIv: Long) {
        val v = vs
        if (v.firstPts < 0) v.firstPts = ptsUs
        v.frames++; v.bytes += size; if (key) v.keyFrames++
        if (v.lastPts >= 0) {
            val iv = ptsUs - v.lastPts
            v.nIv++; v.sumIv += iv
            if (iv < v.minIv) v.minIv = iv
            if (iv > v.maxIv) v.maxIv = iv
            if (iv > 50_000 && iv > expectedIv * 5 / 2) {
                v.hitches++
                if (v.hitchLines < 60) { v.hitchLines++; log.line("video gap: ${iv / 1000} ms between frames (expected ~${expectedIv / 1000} ms) at t=${(ptsUs - v.firstPts) / 1000} ms") }
            }
        }
        v.lastPts = ptsUs
        val sec = ((ptsUs - v.firstPts) / 1_000_000L).toInt()
        while (v.perSecFrames.size <= sec) { v.perSecFrames += 0; v.perSecBytes += 0L }
        v.perSecFrames[sec] = v.perSecFrames[sec] + 1
        v.perSecBytes[sec] = v.perSecBytes[sec] + size
    }

    // ------------------------------------------------------------------ audio

    private fun setupAudio(): Boolean {
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            note("No microphone/audio permission: recording without audio.")
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
            log.line("audio: minBuf=$minBuf bufSize=$bufSize usages=MEDIA,GAME,UNKNOWN")
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
                note("Internal audio capture failed to initialize.")
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
                    log.error("mic init failed", e)
                    note("Microphone unavailable, recording internal audio only.")
                }
            }

            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_BYTES * 2)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            log.line("audio encoder ${enc.name}: $af")
            enc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            aenc = enc
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Audio setup failed", e)
            log.error("audio setup failed", e)
            note("Audio setup failed (${e.javaClass.simpleName}): recording video only.")
            releaseAudio()
            return false
        }
    }

    private fun audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rec = internalRec ?: return
        val enc = aenc ?: return
        val mic = micRec
        val a = aus
        val info = MediaCodec.BufferInfo()
        val pcm = ByteArray(CHUNK_BYTES)
        val micBuf = ByteArray(CHUNK_BYTES)
        val micFifo = ByteArray(CHUNK_BYTES * 16)
        var micCount = 0
        val chunkUs = FRAME_SAMPLES * 1_000_000L / SAMPLE_RATE
        var samples = 0L
        var anchorUs = 0L
        var first = true
        var stallLines = 0

        fun drain(eos: Boolean) {
            var spins = 0
            while (true) {
                val idx = enc.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        log.line("audio outputFormat: ${enc.outputFormat}")
                        sink?.addTrack(AUDIO, enc.outputFormat)
                    }
                    idx >= 0 -> {
                        val buf = enc.getOutputBuffer(idx)
                        val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            if (a.firstPtsUs == Long.MIN_VALUE) a.firstPtsUs = info.presentationTimeUs
                            a.aacBuffers++; a.aacBytes += info.size
                            sink?.write(AUDIO, buf, info.offset, info.size, info.presentationTimeUs, info.flags)
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
            log.line("audio capture started")
            while (!audioStop) {
                // Blocking read of exactly one AAC frame of internal audio.
                var got = 0
                while (got < CHUNK_BYTES && !audioStop) {
                    val n = rec.read(pcm, got, CHUNK_BYTES - got)
                    if (n < 0) { a.readErrors++; throw IllegalStateException("AudioRecord.read error $n") }
                    got += n
                }
                if (got < CHUNK_BYTES) break
                val nowUs = System.nanoTime() / 1000
                a.chunks++
                if (a.lastReadAtUs != 0L) {
                    val gap = nowUs - a.lastReadAtUs
                    if (gap > a.maxReadGapUs) a.maxReadGapUs = gap
                    if (gap > 3 * chunkUs) a.slowReads++
                }
                a.lastReadAtUs = nowUs
                // Cheap silence check: sample every 64th byte pair.
                var peak = 0
                var i = 0
                while (i + 1 < CHUNK_BYTES) { val s = abs(((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()); if (s > peak) peak = s; i += 64 }
                if (peak == 0) a.silentChunks++

                if (first) {
                    first = false
                    var an = nowUs - chunkUs
                    val ts = AudioTimestamp()
                    if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        val candidate = ts.nanoTime / 1000 - ts.framePosition * 1_000_000L / SAMPLE_RATE
                        if (abs(candidate - an) < 500_000) { an = candidate; a.timestampAnchor = "AudioRecord.getTimestamp (offset vs read-time ${(candidate - (nowUs - chunkUs)) / 1000} ms)" }
                        else a.timestampAnchor = "read-time (getTimestamp off by ${(candidate - an) / 1000} ms, rejected)"
                    } else a.timestampAnchor = "read-time (getTimestamp unavailable)"
                    anchorUs = an
                    log.line("audio first chunk anchor: ${a.timestampAnchor}")
                } else {
                    // If capture stalled (nothing playing), jump the clock forward instead of drifting behind.
                    val expected = anchorUs + samples * 1_000_000L / SAMPLE_RATE
                    val actual = nowUs - chunkUs
                    if (actual - expected > 200_000) {
                        a.stallJumps++; a.stallTotalUs += actual - expected
                        if (stallLines < 40) { stallLines++; log.line("audio gap: capture stalled ${(actual - expected) / 1000} ms, clock jumped forward") }
                        anchorUs += actual - expected
                    }
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
                            a.micOverflows++
                        }
                        System.arraycopy(micBuf, 0, micFifo, micCount, n)
                        micCount += n
                    }
                    val take = minOf(micCount, CHUNK_BYTES)
                    a.micChunks++
                    if (take < CHUNK_BYTES) a.micShort++
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
                } else a.inputMiss++
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
            if (!stopped.get()) { Log.e(TAG, "Audio loop failed", e); log.error("audio loop failed", e) }
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
    fun stop(reason: String = "stop requested (notification, app button or bubble)") {
        if (!stopped.compareAndSet(false, true)) return
        stopReason = reason
        log.line("STOP: $reason")
        Thread({ finalizeAll() }, "nr-finalize").start()
    }

    private fun bundleDump(b: android.os.BaseBundle?): String =
        b?.keySet()?.sorted()?.joinToString("\n") { "    $it = ${b.get(it)}" } ?: "    (none)"

    private fun finalizeAll() {
        var error: String? = startError
        val stopAtMs = log.elapsedMs()
        val dm = ctx.getSystemService(DisplayManager::class.java)
        displayListener?.let { l -> try { dm.unregisterDisplayListener(l) } catch (_: Throwable) {}; displayListener = null }
        rotListener?.let { l -> try { dm.unregisterDisplayListener(l) } catch (_: Throwable) {}; rotListener = null }
        thermalListener?.let { l -> try { ctx.getSystemService(PowerManager::class.java).removeThermalStatusListener(l) } catch (_: Throwable) {}; thermalListener = null }
        waiting = false
        val endState = RecLog.thermalBattery(ctx)
        RecLog.batteryTempC(ctx)?.let { if (maxBatteryC.isNaN() || it > maxBatteryC) maxBatteryC = it }
        try { vdisplay?.release() } catch (_: Throwable) {}
        try { callback?.let { projection.unregisterCallback(it) } } catch (_: Throwable) {}
        try { projection.stop() } catch (_: Throwable) {}
        try { venc?.signalEndOfInputStream() } catch (_: Throwable) {}
        audioStop = true
        videoThread?.join(3000)
        audioThread?.let {
            it.join(1500)
            if (it.isAlive) {
                log.line("audio thread did not exit in time; forcing AudioRecord.stop()")
                try { internalRec?.stop() } catch (_: Throwable) {}
                it.join(1500)
            }
        }
        // Codec / AudioRecord metrics, captured before release.
        try { vMetrics = bundleDump(venc?.metrics) } catch (_: Throwable) {}
        try { aMetrics = bundleDump(internalRec?.metrics) } catch (_: Throwable) {}
        val s = sink
        val wrote = s?.wroteSamples == true
        try { s?.finish() } catch (e: Throwable) { error = e.message; log.error("muxer finish failed", e) }
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
                log.error("finalizing MediaStore entry failed", e)
                error = e.message
            }
        }
        try { writeDiagnostics(result, error, stopAtMs, endState, s) } catch (e: Throwable) { Log.e(TAG, "log failed", e) }
        onFinished(result, error)
    }

    // ------------------------------------------------------------------ diagnostics output

    private fun ms(us: Long) = String.format(Locale.US, "%.2f", us / 1000.0)

    private fun writeDiagnostics(result: Uri?, error: String?, stopAtMs: Long, endState: String, s: MuxerSink?) {
        val p = plan
        val sum = RecLog()
        sum.section("SUMMARY")
        sum.raw("Result: " + if (result != null) "saved" else "no video saved")
        sum.raw("Error: ${error ?: "none"}")
        sum.raw("Stop reason: $stopReason")
        if (armedAtMs >= 0) sum.raw("Waited for landscape: ${if (waitedMs >= 0) "$waitedMs ms" else "never reached landscape"}")
        if (recStartMs >= 0) sum.raw("Wall-clock recording duration: ${(stopAtMs - recStartMs) / 1000.0} s")
        sum.raw("Bitrate mode used: $usedMode")
        sum.raw("Repeat-previous-frame-after: $repeatUsed")
        sum.raw("Thermal/battery at start: ${batteryStart.ifEmpty { "n/a" }}")
        sum.raw("Thermal/battery at end:   $endState")
        sum.raw("Max battery temp seen: ${if (maxBatteryC.isNaN()) "n/a" else "%.1f C".format(Locale.US, maxBatteryC)}")
        if (p != null) sum.raw("Selected: ${p.width}x${p.height} @${p.fps}fps (effective encoder fps $effFps) ${p.bitrate / 1000} kbps ${p.mime}, encoder=${p.codecName ?: "default"}")

        sum.section("VIDEO (encoder output, measured in-process)")
        val v = vs
        if (v.frames == 0) sum.raw("No video frames were encoded.")
        else {
            val durUs = max(1L, v.lastPts - v.firstPts)
            sum.raw("Frames encoded: ${v.frames} (key frames ${v.keyFrames}), bytes ${v.bytes}")
            sum.raw("Video span: ${ms(durUs)} ms; average encoded fps: ${"%.2f".format(Locale.US, (v.frames - 1) * 1_000_000.0 / durUs)}")
            sum.raw("Frame interval ms: avg=${if (v.nIv > 0) ms(v.sumIv / v.nIv) else "n/a"} min=${if (v.nIv > 0) ms(v.minIv) else "n/a"} max=${ms(v.maxIv)}; gaps > 2.5x expected & >50ms: ${v.hitches}")
            sum.raw("Average video bitrate: ${"%.0f".format(Locale.US, v.bytes * 8 * 1_000_000.0 / durUs / 1000)} kbps")
            sum.raw("Per-second output (sec: fps / kbps):")
            val sb = StringBuilder("  ")
            for (i in v.perSecFrames.indices) {
                sb.append(i).append(": ").append(v.perSecFrames[i]).append('/').append(v.perSecBytes[i] * 8 / 1000).append("   ")
                if ((i + 1) % 6 == 0) sb.append("\n  ")
            }
            sum.raw(sb.toString().trimEnd())
        }
        sum.raw("Note: frames received from the VirtualDisplay and frames dropped by the fps cap are not visible to the app")
        sum.raw("      (surface input has no per-frame callback by design, to avoid overhead). Encoded frames/intervals above are exact.")
        sum.raw("Video formats:\n$videoFormatLines")
        sum.raw("MediaCodec metrics (video):\n$vMetrics")

        sum.section("AUDIO")
        val a = aus
        sum.raw("PCM chunks (1024 frames each): ${a.chunks}, silent chunks: ${a.silentChunks}, read errors: ${a.readErrors}")
        sum.raw("Capture stalls (clock jumps >200ms): ${a.stallJumps}, total ${a.stallTotalUs / 1000} ms; max gap between reads ${a.maxReadGapUs / 1000} ms; slow reads (>3 chunks): ${a.slowReads}")
        sum.raw("AAC buffers out: ${a.aacBuffers}, bytes ${a.aacBytes}; encoder input unavailable (dropped chunks): ${a.inputMiss}")
        if (settings.mic) sum.raw("Mic chunks: ${a.micChunks}, short/padded: ${a.micShort}, FIFO overflows: ${a.micOverflows}")
        sum.raw("First-sample clock anchor: ${a.timestampAnchor}")
        sum.raw("AudioRecord metrics:\n$aMetrics")

        sum.section("A/V SYNC")
        if (s != null) {
            val vf = s.firstRawPts[VIDEO]; val af = s.firstRawPts[AUDIO]
            val base = s.rebaseBase
            sum.raw("First video pts (monotonic): ${if (vf == Long.MIN_VALUE) "n/a" else "$vf us"}")
            sum.raw("First audio pts (monotonic): ${if (af == Long.MIN_VALUE) "n/a" else "$af us"}")
            if (vf != Long.MIN_VALUE && af != Long.MIN_VALUE) sum.raw("Audio starts ${ms(af - vf)} ms ${if (af >= vf) "after" else "before"} video")
            if (base != Long.MAX_VALUE) {
                if (vf != Long.MIN_VALUE) sum.raw("Video start in file: ${ms(vf - base)} ms; ")
                if (af != Long.MIN_VALUE) sum.raw("Audio start in file: ${ms(af - base)} ms")
            }
            sum.raw("Samples muxed: video ${s.sampleCount[VIDEO]}, audio ${s.sampleCount[AUDIO]}")
        }

        sum.section("POST-RECORDING VERIFICATION (read back from the saved file)")
        if (result != null) {
            verify(result, p, sum)?.let { VerifyStore.put(ctx, "$baseName.mp4", it) }
        } else sum.raw("No file to verify.")
        sum.raw()
        val text = head.toString() + sum.toString() + "\n=== EVENTS ===\n" + log.toString()
        val u = RecLog.writeFile(ctx, baseName, text)
        Log.i(TAG, "diagnostic log: ${u ?: "WRITE FAILED"}")
    }

    /** Reads the saved file back and compares it with what was selected. Returns a compact verdict for the UI. */
    private fun verify(u: Uri, p: CodecSupport.Plan?, out: RecLog): VerifyStore.Verdict? {
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(ctx, u)
            fun m(k: Int) = r.extractMetadata(k)
            val w = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val durMs = m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            out.raw("Container: ${w}x$h rotation=${m(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)} duration=${durMs} ms bitrate=${m(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.div(1000)} kbps hasAudio=${m(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)} hasVideo=${m(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)} frameCount=${m(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)} captureFps=${m(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)}")
            r.release()

            val ex = MediaExtractor()
            ex.setDataSource(ctx, u, null)
            var videoFps = Double.NaN
            var videoKbps = Double.NaN
            var audioDesc: String? = null
            var audioKbps = Double.NaN
            var vFirstMs = Double.NaN
            var aFirstMs = Double.NaN
            for (t in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(t)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                ex.selectTrack(t)
                var count = 0; var first = -1L; var last = -1L; var minIv = Long.MAX_VALUE; var maxIv = 0L; var prev = -1L; var bytes = 0L
                while (true) {
                    val ts = ex.sampleTime
                    if (ts < 0) break
                    count++
                    bytes += max(0L, ex.sampleSize)
                    if (first < 0) first = ts
                    if (prev >= 0) { val iv = ts - prev; if (iv > 0 && iv < minIv) minIv = iv; if (iv > maxIv) maxIv = iv }
                    prev = ts; last = ts
                    if (!ex.advance()) break
                }
                ex.unselectTrack(t)
                val span = max(1L, last - first)
                val fps = if (count > 1) (count - 1) * 1_000_000.0 / span else 0.0
                val kbps = bytes * 8 * 1000.0 / span
                out.raw("Track $t $mime: samples=$count first=${ms(first)} ms span=${ms(span)} ms avg rate=${"%.2f".format(Locale.US, fps)}/s min interval=${if (minIv == Long.MAX_VALUE) "n/a" else ms(minIv)} ms max interval=${ms(maxIv)} ms bitrate=${"%.0f".format(Locale.US, kbps)} kbps | $f")
                if (mime.startsWith("video/")) { videoFps = fps; videoKbps = kbps; vFirstMs = first / 1000.0 }
                if (mime.startsWith("audio/")) {
                    audioKbps = kbps
                    aFirstMs = first / 1000.0
                    audioDesc = "AAC ${f.getInteger(MediaFormat.KEY_SAMPLE_RATE) / 1000.0} kHz ${f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}ch ${"%.0f".format(Locale.US, kbps)} kbps"
                }
            }
            ex.release()

            if (!vFirstMs.isNaN() && !aFirstMs.isNaN()) {
                out.raw("A/V start in file: video first sample at ${"%.1f".format(Locale.US, vFirstMs)} ms, audio at ${"%.1f".format(Locale.US, aFirstMs)} ms (audio ${"%.1f".format(Locale.US, aFirstMs - vFirstMs)} ms after video).")
                out.raw("  Both tracks use real capture times on one monotonic clock, so this offset is the true gap before audio capture delivered its first")
                out.raw("  sample; MP4 start offsets keep them in sync (no drift, only that many ms of silence at the start).")
            }
            val size = ctx.contentResolver.openFileDescriptor(u, "r")?.use { it.statSize } ?: -1L
            out.raw("File size: $size bytes")
            val problems = ArrayList<String>()
            var info = ""
            if (p != null) {
                val sizeOk = w == p.width && h == p.height
                if (!sizeOk) problems += "size ${w}x$h, selected ${p.width}x${p.height}"
                val fpsOk = !videoFps.isNaN() && videoFps >= effFps * 0.9
                if (!fpsOk && !videoFps.isNaN()) problems += "avg ${"%.1f".format(Locale.US, videoFps)} fps vs $effFps selected (idle screen content also lowers this)"
                val selKbps = p.bitrate / 1000.0
                val isCbr = usedMode == "CBR"
                // CBR: expect ~selected (+-20%). VBR: undershoot on easy content is normal (informational);
                // only flag overshoot >25% or an extreme undershoot (<10%).
                val brOk = videoKbps.isNaN() || if (isCbr) videoKbps in selKbps * 0.8..selKbps * 1.2 else videoKbps in selKbps * 0.10..selKbps * 1.25
                if (!brOk) problems += "video ${"%.1f".format(Locale.US, videoKbps / 1000)} Mbps vs ${"%.0f".format(Locale.US, selKbps / 1000)} Mbps selected ($usedMode)"
                if (!isCbr && !videoKbps.isNaN() && videoKbps < selKbps * 0.8 && brOk) {
                    info = "VBR: ${"%.1f".format(Locale.US, videoKbps / 1000)} Mbps used of ${"%.0f".format(Locale.US, selKbps / 1000)} selected; the encoder saved space on simple content."
                }
                if (audioWanted && audioDesc == null) problems += "no audio track"
                out.raw("Selected vs actual:")
                out.raw("  size:    selected ${p.width}x${p.height}, actual ${w}x$h -> ${if (sizeOk) "OK" else "MISMATCH"}")
                out.raw("  fps:     selected $effFps, actual average ${"%.2f".format(Locale.US, videoFps)} -> ${if (fpsOk) "OK" else "BELOW TARGET"}")
                out.raw("  bitrate: selected video ${p.bitrate / 1000} kbps ($usedMode), actual video ${"%.0f".format(Locale.US, videoKbps)} kbps -> ${if (!brOk) "OFF TARGET" else if (info.isNotEmpty()) "OK (VBR undershoot, informational)" else "OK"}")
                out.raw("  audio:   ${audioDesc ?: "none"} (wanted=$audioWanted)")
            }
            return VerifyStore.Verdict(
                "${w ?: "?"}×${h ?: "?"} · ${"%.1f".format(Locale.US, videoFps)} fps · ${"%.1f".format(Locale.US, videoKbps / 1000)} Mbps · ${audioDesc ?: "no audio"}",
                problems.isEmpty(), problems.joinToString("; "), info,
            )
        } catch (e: Throwable) {
            out.raw("Verification failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
    }
}
