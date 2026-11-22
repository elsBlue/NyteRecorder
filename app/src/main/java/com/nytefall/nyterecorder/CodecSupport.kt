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
        /** Aligned size of the chosen resolution preset, before any fps-driven shrink. */
        val presetWidth: Int = width,
        val presetHeight: Int = height,
        /** Largest 16-aligned native-aspect size the encoder reports as supporting the requested fps. */
        val capsMaxWidth: Int? = null,
        val capsMaxHeight: Int? = null,
        val supportedRates: String? = null,
        val achievableRates: String? = null,
        /** Detailed fps/size probe lines for the diagnostic log (not shown in the UI). */
        val searchLog: List<String> = emptyList(),
    ) {
        val hardware get() = codecName != null
        val sizeLowered get() = width != presetWidth || height != presetHeight
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
        val searchLog = ArrayList<String>()
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

        val reqFps = s.fps
        var bitrate = s.bitrateMbps * 1_000_000
        val caps = info?.getCapabilitiesForType(codec.mime)?.videoCapabilities
        // Hardware encoders want macroblock-aligned sizes (1920x862 is rejected for 60 fps on the Unisoc one).
        val a = if (caps != null) max(16, max(caps.widthAlignment, caps.heightAlignment)) else 16
        val firstW = align(outW, a)
        val firstH = align(outH, a)

        fun planOf(
            w: Int,
            h: Int,
            fps: Int,
            maxFps: Double? = null,
            mode: Int = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            capsW: Int? = null,
            capsH: Int? = null,
            supported: String? = null,
            achievable: String? = null,
        ) = Plan(
            info?.name, codec.mime, w, h, nd.dpi, fps, bitrate, notes, maxFps, a, mode,
            firstW, firstH, capsW, capsH, supported, achievable, searchLog,
        )

        if (caps == null) return planOf(firstW, firstH, reqFps)

        bitrate = bitrate.coerceIn(caps.bitrateRange.lower, caps.bitrateRange.upper)
        // CBR honours the selected bitrate far better than VBR (VBR undershot: 16 Mbps selected -> 11 actual).
        val enc = info.getCapabilitiesForType(codec.mime).encoderCapabilities
        val mode = if (enc != null && enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR))
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        fun rateAt(w: Int, h: Int): Double? = runCatching { caps.getSupportedFrameRatesFor(w, h).upper }.getOrNull()
        fun ratesAt(w: Int, h: Int): String = runCatching { caps.getSupportedFrameRatesFor(w, h).toString() }.getOrElse { "n/a ($it)" }
        fun achievableAt(w: Int, h: Int): String = runCatching { caps.getAchievableFrameRatesFor(w, h)?.toString() }.getOrNull() ?: "n/a"
        fun fpsOk(w: Int, h: Int, f: Int) = caps.areSizeAndRateSupported(w, h, f.toDouble())

        val nativeAspect = nd.long.toDouble() / nd.short // long/short, e.g. 2408/1080

        /** Short-edge candidates for a 16-aligned long edge, staying close to the native aspect. */
        fun shortsFor(longEdge: Int): List<Int> {
            val ideal = longEdge / nativeAspect
            val mid = align(ideal, a)
            return listOf(mid, mid - a, mid + a).filter { it >= a }.distinct()
        }

        fun orient(longEdge: Int, shortEdge: Int): Pair<Int, Int> =
            if (landscape) longEdge to shortEdge else shortEdge to longEdge

        /**
         * Largest 16-aligned size at the screen's native aspect for which the encoder declares [fps].
         * Walks the long edge down from [startLong], trying nearby short-edge alignments.
         */
        fun largestForFps(startLong: Int, fps: Int, logProbes: Boolean): Pair<Int, Int>? {
            val minW = max(a, caps.supportedWidths.lower)
            val minH = max(a, caps.supportedHeights.lower)
            val minLong = if (landscape) minW else minH
            var longEdge = startLong
            var probed = 0
            while (longEdge >= minLong) {
                var best: Pair<Int, Int>? = null
                for (shortEdge in shortsFor(longEdge)) {
                    val (cw, ch) = orient(longEdge, shortEdge)
                    if (cw < minW || ch < minH) continue
                    val sizeOk = caps.isSizeSupported(cw, ch)
                    val ok = sizeOk && fpsOk(cw, ch, fps)
                    if (logProbes && (sizeOk || longEdge == startLong)) {
                        searchLog += "  ${cw}x$ch size=$sizeOk rates=${if (sizeOk) ratesAt(cw, ch) else "n/a"} " +
                            "achievable=${if (sizeOk) achievableAt(cw, ch) else "n/a"} " +
                            "areSizeAndRateSupported($fps)=$ok"
                        probed++
                    }
                    if (ok && (best == null || cw * ch > best.first * best.second)) best = cw to ch
                }
                if (best != null) {
                    if (logProbes) searchLog += "  -> chosen ${best.first}x${best.second} after $probed size-supported probes"
                    return best
                }
                longEdge -= a
            }
            if (logProbes) searchLog += "  -> no size declared $fps fps (probed $probed sizes from long-edge $startLong down to $minLong)"
            return null
        }

        val startLong = max(firstW, firstH)
        searchLog += "preset ${firstW}x$firstH (aligned ${a}px) requested $reqFps fps, native ${nd.long}x${nd.short} aspect, " +
            (if (landscape) "landscape" else "portrait")
        searchLog += "  preset rates=${ratesAt(firstW, firstH)} achievable=${achievableAt(firstW, firstH)} " +
            "areSizeAndRateSupported($reqFps)=${fpsOk(firstW, firstH, reqFps)}"

        val chosen: Pair<Int, Int>
        var fps = reqFps
        var capsMax: Pair<Int, Int>? = null

        if (reqFps > 30) {
            // Prefer keeping the requested fps, shrinking resolution as needed (used for 60 fps mode).
            searchLog += "searching largest native-aspect ${a}px-aligned size with areSizeAndRateSupported($reqFps)=true:"
            capsMax = largestForFps(startLong, reqFps, logProbes = true)
            if (capsMax != null) {
                chosen = capsMax
                if (chosen != firstW to firstH) {
                    notes += "To keep $reqFps fps, resolution is lowered from ${firstW}x$firstH to ${chosen.first}x${chosen.second} (largest size this encoder supports at $reqFps fps, native aspect, ${a}px aligned)."
                }
            } else {
                // Nothing at the requested fps: fall back to the preset size and clamp fps (old behaviour).
                chosen = firstW to firstH
                val limit = rateAt(chosen.first, chosen.second)?.toInt()
                fps = listOf(60, 30, 24, 15).firstOrNull { it <= reqFps && fpsOk(chosen.first, chosen.second, it) }
                    ?: min(reqFps, max(1, limit ?: 15))
                notes += "Encoder has no ${a}px-aligned native-aspect size for $reqFps fps; using $fps fps at ${chosen.first}x${chosen.second} (up to ${limit ?: "?"} fps)."
            }
        } else {
            // 30 fps and below: keep the selected preset. Only nudge size within 15% if the exact size is rejected.
            val aspect = outW / outH
            val candidates = ArrayList<Pair<Int, Int>>()
            candidates += firstW to firstH
            var w = firstW - a
            while (w >= firstW * 0.85) {
                candidates += w to align(w / aspect, a)
                w -= a
            }
            val supported = candidates.filter { (cw, ch) -> caps.isSizeSupported(cw, ch) }
            val withRate = supported.firstOrNull { (cw, ch) -> fpsOk(cw, ch, reqFps) }
            if (withRate != null) {
                chosen = withRate
                if (chosen != candidates[0]) notes += "${candidates[0].first}x${candidates[0].second} can't do $reqFps fps on this encoder; using ${chosen.first}x${chosen.second} to keep $reqFps fps."
            } else if (supported.isNotEmpty()) {
                chosen = supported[0]
                val limit = rateAt(chosen.first, chosen.second)?.toInt()
                fps = listOf(60, 30, 24, 15).firstOrNull { it <= reqFps && fpsOk(chosen.first, chosen.second, it) }
                    ?: min(reqFps, max(1, limit ?: 15))
                notes += "Encoder supports up to ${limit ?: "?"} fps at ${chosen.first}x${chosen.second}; using $fps fps."
            } else {
                chosen = candidates[0]
                notes += "Encoder reports ${candidates[0].first}x${candidates[0].second} unsupported; trying anyway."
            }
            // Still compute the caps-derived max for the requested rate (useful in logs even at 30 fps).
            capsMax = if (fpsOk(chosen.first, chosen.second, reqFps)) chosen else largestForFps(startLong, reqFps, logProbes = false)
        }

        val cw = chosen.first
        val ch = chosen.second
        return planOf(
            cw, ch, fps, rateAt(cw, ch), mode,
            capsMax?.first, capsMax?.second, ratesAt(cw, ch), achievableAt(cw, ch),
        )
    }

    /** True if the encoder declares support for 60 fps at some native-aspect size (used to enable the 60 fps chip). */
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
