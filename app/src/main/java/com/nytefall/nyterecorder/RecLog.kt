package com.nytefall.nyterecorder

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.MediaStore
import java.util.Locale

/** In-memory diagnostic log for one recording. Nothing touches the disk until [writeFile] at the end. */
class RecLog {
    private val t0 = System.nanoTime()
    private val sb = StringBuilder(16 * 1024)

    fun elapsedMs(): Long = (System.nanoTime() - t0) / 1_000_000

    @Synchronized
    fun line(msg: String) {
        val ms = elapsedMs()
        sb.append('[').append(String.format(Locale.US, "+%d.%03ds", ms / 1000, ms % 1000)).append("] ").append(msg).append('\n')
    }

    @Synchronized
    fun raw(msg: String = "") {
        sb.append(msg).append('\n')
    }

    fun section(title: String) {
        raw()
        raw("=== $title ===")
    }

    fun error(msg: String, t: Throwable? = null) {
        line("ERROR: $msg" + (t?.let { " | ${it.javaClass.name}: ${it.message}\n" + it.stackTrace.take(8).joinToString("\n") { s -> "      at $s" } } ?: ""))
    }

    @Synchronized
    override fun toString(): String = sb.toString()

    companion object {
        const val DIR = "Documents/NyteRecorder/logs"

        fun thermalName(s: Int) = when (s) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN($s)"
        }

        /** "thermal=NONE headroom(10s)=0.62 battery=37.5C level=81% plugged=false" */
        fun thermalBattery(ctx: Context): String {
            val pm = ctx.getSystemService(PowerManager::class.java)
            val thermal = runCatching { thermalName(pm.currentThermalStatus) }.getOrDefault("n/a")
            val head = runCatching { pm.getThermalHeadroom(10) }.getOrDefault(Float.NaN)
            val b: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
            val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val plugged = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            return "thermal=$thermal headroom(10s)=${if (head.isNaN()) "n/a" else String.format(Locale.US, "%.2f", head)} " +
                "battery=${temp?.let { String.format(Locale.US, "%.1fC", it) } ?: "n/a"} level=$level% plugged=$plugged"
        }

        fun batteryTempC(ctx: Context): Double? {
            val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
            val t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            return if (t == Int.MIN_VALUE) null else t / 10.0
        }

        fun deviceInfo(ctx: Context): List<String> {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            return listOf(
                "App: Nyte Recorder ${pi.versionName} (versionCode $code)",
                "Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), hardware=${Build.HARDWARE}, board=${Build.BOARD}" +
                    (if (Build.VERSION.SDK_INT >= 31) ", soc=${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else ""),
                "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), build=${Build.DISPLAY}",
            )
        }

        /** Writes the log next to the video, under Documents/NyteRecorder/logs (visible in the Files app). */
        fun writeFile(ctx: Context, baseName: String, text: String): Uri? {
            val name = "$baseName.log.txt"
            val cv = { path: String ->
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                }
            }
            val targets = ArrayList<Pair<Uri, String>>()
            if (Build.VERSION.SDK_INT >= 30) targets += MediaStore.Files.getContentUri("external") to DIR
            targets += MediaStore.Downloads.EXTERNAL_CONTENT_URI to "Download/NyteRecorder/logs"
            for ((coll, path) in targets) {
                try {
                    val u = ctx.contentResolver.insert(coll, cv(path)) ?: continue
                    ctx.contentResolver.openOutputStream(u, "wt")!!.use { it.write(text.toByteArray()) }
                    return u
                } catch (_: Throwable) {
                }
            }
            return null
        }

        fun find(ctx: Context, baseName: String): Uri? {
            val name = "$baseName.log.txt"
            for (coll in listOf(MediaStore.Files.getContentUri("external"), MediaStore.Downloads.EXTERNAL_CONTENT_URI)) {
                try {
                    ctx.contentResolver.query(
                        coll, arrayOf(MediaStore.MediaColumns._ID),
                        "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                        arrayOf(name, "%NyteRecorder/logs%"), null,
                    )?.use { c -> if (c.moveToFirst()) return android.content.ContentUris.withAppendedId(coll, c.getLong(0)) }
                } catch (_: Throwable) {
                }
            }
            return null
        }
    }
}
