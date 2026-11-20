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
        val maxFps: Double? = null, // what the encoder itself reports for this exact size
        val alignment: Int = 16,
        val bitrateMode: Int = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
    ) {
        val hardware get() = codecName != null
    }

    private fun align(v: Double, a: Int): Int = max(a, (v / a).roundToInt() * a)

    fun outputSize(res: Res, nLong: Int, nShort: Int): Pair<Double, Double> {
        val scale = when (res) {
            Res.P1080 -> min(1.0, 1920.0 / nLong)
            Res.NATIVE -> min(1.0, 1080.0 / nShort)
            Res.P720 -> min(1.0, 1280.0 / nLong)
        }
        return nLong * scale to nShort * scale
    }

    /** Orientation actually used for the canvas. */
    fun isLandscape(s: RecSettings, nd: NativeDisplay): Boolean = when (s.orient) {
        Orient.LANDSCAPE -> true
        Orient.PORTRAIT -> false
        Orient.AUTO -> nd.landscape
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

        val landscape = isLandscape(s, nd)
        val (l, sh) = outputSize(s.res, nd.long, nd.short)
        val outW = if (landscape) l else sh
        val outH = if (landscape) sh else l
        val aspect = outW / outH

        val reqFps = s.fps
        var bitrate = s.bitrateMbps * 1_000_000
        val caps = info?.getCapabilitiesForType(codec.mime)?.videoCapabilities
        // Hardware encoders want macroblock-aligned sizes (1920x862 is rejected for 60 fps on the Unisoc one).
        val a = if (caps != null) max(16, max(caps.widthAlignment, caps.heightAlignment)) else 16
        val firstW = align(outW, a)
        val firstH = align(outH, a)

        if (caps == null) return Plan(info?.name, codec.mime, firstW, firstH, nd.dpi, reqFps, bitrate, notes, alignment = a)

        bitrate = bitrate.coerceIn(caps.bitrateRange.lower, caps.bitrateRange.upper)
        // CBR honours the selected bitrate far better than VBR (VBR undershot: 16 Mbps selected -> 11 actual).
        val enc = info.getCapabilitiesForType(codec.mime).encoderCapabilities
        val mode = if (enc != null && enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR))
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        fun rateAt(w: Int, h: Int): Double? = runCatching { caps.getSupportedFrameRatesFor(w, h).upper }.getOrNull()
        fun fpsOk(w: Int, h: Int, f: Int) = caps.areSizeAndRateSupported(w, h, f.toDouble())

        // Candidates: the ideal aligned size first, then slightly narrower 16-aligned sizes with nearly the same aspect.
        val candidates = ArrayList<Pair<Int, Int>>()
        candidates += firstW to firstH
        var w = firstW - a
        while (w >= firstW * 0.85) {
            candidates += w to align(w / aspect, a)
            w -= a
        }
        val supported = candidates.filter { (cw, ch) -> caps.isSizeSupported(cw, ch) }
        val withRate = supported.firstOrNull { (cw, ch) -> fpsOk(cw, ch, reqFps) }

        val chosen: Pair<Int, Int>
        var fps = reqFps
        if (withRate != null) {
            chosen = withRate
            if (chosen != candidates[0]) notes += "${candidates[0].first}x${candidates[0].second} can't do $reqFps fps on this encoder; using ${chosen.first}x${chosen.second} to keep $reqFps fps."
        } else if (supported.isNotEmpty()) {
            // Never go above what the encoder declares: clamp to the best supported rate <= requested.
            chosen = supported[0]
            val limit = rateAt(chosen.first, chosen.second)?.toInt()
            fps = listOf(60, 30, 24, 15).firstOrNull { it <= reqFps && fpsOk(chosen.first, chosen.second, it) }
                ?: min(reqFps, max(1, limit ?: 15))
            notes += "Encoder supports up to ${limit ?: "?"} fps at ${chosen.first}x${chosen.second}; using $fps fps."
        } else {
            chosen = candidates[0]
            notes += "Encoder reports ${candidates[0].first}x${candidates[0].second} unsupported; trying anyway."
        }
        return Plan(info.name, codec.mime, chosen.first, chosen.second, nd.dpi, fps, bitrate, notes, rateAt(chosen.first, chosen.second), a, mode)
    }

    /** True if the encoder declares support for 60 fps at the 1080p-class size of [s] (used to enable the 60 fps chip). */
    fun supports60(ctx: Context, s: RecSettings): Pair<Boolean, Int?> {
        val p = plan(ctx, s.copy(fps = 60))
        return (p.fps >= 60) to p.maxFps?.toInt()
    }

    // ---------------------------------------------------------------- diagnostics

    fun listEncoders(mime: String): List<String> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { i -> i.isEncoder && i.supportedTypes.any { it.equals(mime, true) } }
            .map { i ->
                "${i.name} hw=${if (Build.VERSION.SDK_INT >= 29) i.isHardwareAccelerated else "?"} " +
                    "swOnly=${if (Build.VERSION.SDK_INT >= 29) i.isSoftwareOnly else "?"} " +
                    "alias=${if (Build.VERSION.SDK_INT >= 29) i.isAlias else "?"}"
            }

    /** Human-readable capability dump of an encoder for [mime], with rates checked at [w]x[h]. */
    fun describe(info: MediaCodecInfo, mime: String, w: Int, h: Int): List<String> {
        val out = ArrayList<String>()
        out += "Encoder: ${info.name}" + if (Build.VERSION.SDK_INT >= 29) " (canonical ${info.canonicalName}, hw=${info.isHardwareAccelerated}, softwareOnly=${info.isSoftwareOnly}, vendor=${info.isVendor})" else ""
        val c = try { info.getCapabilitiesForType(mime) } catch (e: Exception) { out += "  capabilities unavailable: $e"; return out }
        val v = c.videoCapabilities
        if (v != null) {
            out += "  widths=${v.supportedWidths} heights=${v.supportedHeights} alignment=${v.widthAlignment}x${v.heightAlignment}"
            out += "  supportedFrameRates(any size)=${v.supportedFrameRates}"
            out += "  supportedFrameRatesFor(${w}x$h)=${runCatching { v.getSupportedFrameRatesFor(w, h) }.getOrElse { "n/a ($it)" }}"
            out += "  achievableFrameRatesFor(${w}x$h)=${runCatching { v.getAchievableFrameRatesFor(w, h) }.getOrNull() ?: "n/a"}"
            out += "  isSizeSupported=${v.isSizeSupported(w, h)} areSizeAndRateSupported(60)=${v.areSizeAndRateSupported(w, h, 60.0)} (30)=${v.areSizeAndRateSupported(w, h, 30.0)}"
            out += "  bitrateRange=${v.bitrateRange}"
            if (Build.VERSION.SDK_INT >= 29) out += "  performancePoints=${v.supportedPerformancePoints?.joinToString("; ") ?: "none"}"
        }
        out += "  maxSupportedInstances=${c.maxSupportedInstances}"
        c.encoderCapabilities?.let { e ->
            out += "  bitrateModes: VBR=${e.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)} " +
                "CBR=${e.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)} " +
                "CQ=${e.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)}; complexity=${e.complexityRange} quality=${e.qualityRange}"
        }
        out += "  profiles/levels: " + c.profileLevels.joinToString(", ") { "p=0x${Integer.toHexString(it.profile)}/l=0x${Integer.toHexString(it.level)}" }
        out += "  surface input colour format: ${c.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)}"
        if (Build.VERSION.SDK_INT >= 30) out += "  feature low-latency=${c.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)}"
        return out
    }
}
