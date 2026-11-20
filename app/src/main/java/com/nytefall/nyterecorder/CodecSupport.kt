package com.nytefall.nyterecorder

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object CodecSupport {

    class NativeDisplay(val long: Int, val short: Int, val dpi: Int, val landscape: Boolean)

    @Suppress("DEPRECATION")
    fun nativeDisplay(ctx: Context): NativeDisplay {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        d.getRealMetrics(m)
        return NativeDisplay(max(m.widthPixels, m.heightPixels), min(m.widthPixels, m.heightPixels), m.densityDpi, m.widthPixels >= m.heightPixels)
    }

    /**
     * First hardware encoder for [mime], in the system's own preference order (c2.* / OMX.sprd.* ...).
     * Software encoders (c2.android.*, OMX.google.*) are skipped.
     */
    fun findHardwareEncoder(mime: String): MediaCodecInfo? {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, true) }
        }
        return all.firstOrNull { isHardware(it) }
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated && !info.isSoftwareOnly && !info.isAlias
        val n = info.name.lowercase()
        return !n.startsWith("omx.google.") && !n.startsWith("c2.android.")
    }

    fun hevcAvailable(): Boolean = findHardwareEncoder(Codec.HEVC.mime) != null

    class Plan(
        val codecName: String?, // null = no hardware encoder found, fall back to system default
        val mime: String,
        val width: Int,
        val height: Int,
        val dpi: Int,
        val fps: Int,
        val bitrate: Int,
        val notes: List<String>,
    ) {
        val hardware get() = codecName != null
    }

    private fun align(v: Double, a: Int): Int {
        val al = max(a, 2)
        return max(al, ((v / al).roundToInt()) * al)
    }

    fun outputSize(res: Res, nLong: Int, nShort: Int): Pair<Double, Double> {
        val scale = when (res) {
            Res.P1080 -> min(1.0, 1920.0 / nLong)
            Res.NATIVE -> min(1.0, 1080.0 / nShort)
            Res.P720 -> min(1.0, 1280.0 / nLong)
        }
        return nLong * scale to nShort * scale
    }

    fun plan(ctx: Context, s: RecSettings): Plan {
        val nd = nativeDisplay(ctx)
        val notes = ArrayList<String>()
        var codec = s.codec
        var info = findHardwareEncoder(codec.mime)
        if (info == null && codec == Codec.HEVC) {
            notes += "No hardware H.265 encoder, using H.264."
            codec = Codec.AVC
            info = findHardwareEncoder(codec.mime)
        }
        if (info == null) notes += "No hardware ${codec.label} encoder found; using the system default (may use CPU)."

        val landscape = when (s.orient) {
            Orient.LANDSCAPE -> true
            Orient.PORTRAIT -> false
            Orient.AUTO -> nd.landscape
        }
        val (l, sh) = outputSize(s.res, nd.long, nd.short)
        var w = if (landscape) l else sh
        var h = if (landscape) sh else l

        var fps = s.fps
        var bitrate = s.bitrateMbps * 1_000_000
        val caps = info?.getCapabilitiesForType(codec.mime)?.videoCapabilities
        var aw = 2; var ah = 2
        if (caps != null) {
            aw = caps.widthAlignment; ah = caps.heightAlignment
            var wi = align(w, aw); var hi = align(h, ah)
            var tries = 0
            while (!caps.isSizeSupported(wi, hi) && tries < 4) {
                w *= 0.85; h *= 0.85
                wi = align(w, aw); hi = align(h, ah)
                notes += "Size not supported, reduced to ${wi}x$hi."
                tries++
            }
            w = wi.toDouble(); h = hi.toDouble()
            if (!caps.areSizeAndRateSupported(wi, hi, fps.toDouble())) {
                val maxFps = runCatching { caps.getSupportedFrameRatesFor(wi, hi).upper.toInt() }.getOrDefault(30)
                notes += "$fps fps not supported at ${wi}x$hi, using ${min(maxFps, 30)} fps."
                fps = min(maxFps, 30)
            }
            bitrate = bitrate.coerceIn(caps.bitrateRange.lower, caps.bitrateRange.upper)
        }
        return Plan(info?.name, codec.mime, align(w, aw), align(h, ah), nd.dpi, fps, bitrate, notes)
    }
}
