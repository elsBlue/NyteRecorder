package com.nytefall.nyterecorder

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore

data class Rec(val uri: Uri, val name: String, val size: Long, val dateSec: Long, val durationMs: Long)

object Recordings {
    fun list(ctx: Context, limit: Int = 30): List<Rec> {
        val out = ArrayList<Rec>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION,
        )
        ctx.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("Movies/NyteRecorder%"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                out += Rec(
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                    c.getString(1) ?: "recording.mp4", c.getLong(2), c.getLong(3), c.getLong(4),
                )
            }
        }
        return out
    }

    fun delete(ctx: Context, r: Rec): Boolean {
        val ok = try {
            ctx.contentResolver.delete(r.uri, null, null) > 0
        } catch (e: SecurityException) {
            false
        }
        if (ok) VerifyStore.remove(ctx, r.name)
        if (ok) { // the matching diagnostic log goes with it (best effort)
            runCatching { RecLog.find(ctx, r.name.removeSuffix(".mp4"))?.let { ctx.contentResolver.delete(it, null, null) } }
        }
        return ok
    }

    /** Shares the diagnostic log that belongs to this recording. */
    fun shareLog(ctx: Context, r: Rec): Boolean {
        val u = RecLog.find(ctx, r.name.removeSuffix(".mp4")) ?: return false
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, u)
            .putExtra(Intent.EXTRA_SUBJECT, r.name.removeSuffix(".mp4") + " log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(i, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return true
    }

    fun play(ctx: Context, r: Rec) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(r.uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }

    fun share(ctx: Context, r: Rec) {
        val i = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, r.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(i, "Share recording").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** Remembers each recording's read-back verification so the list can show it without re-reading files. */
object VerifyStore {
    data class Verdict(val summary: String, val ok: Boolean, val note: String)

    private fun prefs(c: Context) = c.getSharedPreferences("verify", Context.MODE_PRIVATE)

    fun put(c: Context, name: String, v: Verdict) {
        prefs(c).edit().putString(name, "${if (v.ok) 1 else 0}\n${v.summary}\n${v.note}").apply()
    }

    fun get(c: Context, name: String): Verdict? {
        val parts = prefs(c).getString(name, null)?.split("\n", limit = 3) ?: return null
        if (parts.size < 3) return null
        return Verdict(parts[1], parts[0] == "1", parts[2])
    }

    fun remove(c: Context, name: String) = prefs(c).edit().remove(name).apply()
}
