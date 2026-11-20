package com.nytefall.nyterecorder

import android.content.Context

enum class Res(val label: String) { P1080("1080p"), NATIVE("Native"), P720("720p") }
enum class Orient(val label: String) { LANDSCAPE("Landscape"), AUTO("Auto"), PORTRAIT("Portrait") }
enum class Codec(val label: String, val mime: String) {
    AVC("H.264", "video/avc"),
    HEVC("H.265", "video/hevc"),
}

/** Defaults tuned for the lightest load on the nubia Neo 5G (Unisoc T820) while keeping good quality. */
data class RecSettings(
    val res: Res = Res.P1080,
    val fps: Int = 30,
    val bitrateMbps: Int = 12,
    val codec: Codec = Codec.AVC,
    val orient: Orient = Orient.LANDSCAPE,
    val mic: Boolean = false,
    val overlay: Boolean = false,
)

object SettingsStore {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(c: Context): RecSettings {
        val p = prefs(c)
        val d = RecSettings()
        return RecSettings(
            res = runCatching { Res.valueOf(p.getString("res", d.res.name)!!) }.getOrDefault(d.res),
            fps = p.getInt("fps30", d.fps),
            bitrateMbps = p.getInt("bitrate", d.bitrateMbps),
            codec = runCatching { Codec.valueOf(p.getString("codec", d.codec.name)!!) }.getOrDefault(d.codec),
            orient = runCatching { Orient.valueOf(p.getString("orient", d.orient.name)!!) }.getOrDefault(d.orient),
            mic = p.getBoolean("mic", d.mic),
            overlay = p.getBoolean("overlay", d.overlay),
        )
    }

    fun save(c: Context, s: RecSettings) {
        prefs(c).edit()
            .putString("res", s.res.name)
            .putInt("fps30", s.fps)
            .putInt("bitrate", s.bitrateMbps)
            .putString("codec", s.codec.name)
            .putString("orient", s.orient.name)
            .putBoolean("mic", s.mic)
            .putBoolean("overlay", s.overlay)
            .apply()
    }
}
