package com.nytefall.nyterecorder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

/**
 * Thread-safe MediaMuxer front. Starts the muxer once every expected track has a format, queues samples
 * that arrive earlier, and rebases all timestamps (same monotonic clock for audio + video) to start at 0.
 */
class MuxerSink(private val muxer: MediaMuxer, private var expectedTracks: Int) {
    private class Pending(val track: Int, val data: ByteArray, val pts: Long, val flags: Int)

    private val trackIds = IntArray(2) { -1 }
    private val lastPts = LongArray(2) { Long.MIN_VALUE }
    private val pending = ArrayList<Pending>()
    private var pendingBytes = 0
    private var started = false
    private var base = Long.MAX_VALUE
    private var added = 0
    var wroteSamples = false
        private set

    @Synchronized
    fun addTrack(kind: Int, format: MediaFormat) {
        if (trackIds[kind] >= 0) return
        trackIds[kind] = muxer.addTrack(format)
        added++
        if (added >= expectedTracks) start()
    }

    private fun start() {
        if (started) return
        muxer.start()
        started = true
        for (p in pending) if (p.pts < base) base = p.pts
        val info = MediaCodec.BufferInfo()
        for (p in pending) writeNow(p.track, ByteBuffer.wrap(p.data), 0, p.pts, p.flags, p.data.size, info)
        pending.clear()
        pendingBytes = 0
    }

    @Synchronized
    fun write(kind: Int, buf: ByteBuffer, offset: Int, size: Int, ptsUs: Long, flags: Int) {
        if (started) {
            if (trackIds[kind] < 0) return
            writeNow(kind, buf, offset, ptsUs, flags, size, MediaCodec.BufferInfo())
            return
        }
        val copy = ByteArray(size)
        buf.position(offset)
        buf.limit(offset + size)
        buf.get(copy)
        pending += Pending(kind, copy, ptsUs, flags)
        pendingBytes += size
        // Safety valve: if the other track never shows up, go on with what we have.
        if (pendingBytes > 24 * 1024 * 1024 && added > 0) {
            expectedTracks = added
            start()
        }
    }

    private fun writeNow(kind: Int, buf: ByteBuffer, offset: Int, ptsUs: Long, flags: Int, size: Int, info: MediaCodec.BufferInfo) {
        if (trackIds[kind] < 0) return
        if (base == Long.MAX_VALUE) base = ptsUs
        var t = max0(ptsUs - base)
        if (t <= lastPts[kind]) t = lastPts[kind] + 1
        lastPts[kind] = t
        buf.position(offset)
        buf.limit(offset + size)
        info.set(offset, size, t, flags)
        muxer.writeSampleData(trackIds[kind], buf, info)
        wroteSamples = true
    }

    private fun max0(v: Long) = if (v < 0) 0 else v

    @Synchronized
    fun finish() {
        try {
            if (started) muxer.stop()
        } catch (_: Throwable) {
        }
        try {
            muxer.release()
        } catch (_: Throwable) {
        }
    }
}
