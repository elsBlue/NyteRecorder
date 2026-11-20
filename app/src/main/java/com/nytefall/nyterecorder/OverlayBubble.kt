package com.nytefall.nyterecorder

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * Tiny, semi-transparent timer bubble: tap = stop, drag = move. Not focusable, so it never steals
 * input from the game. Note: overlays are part of the screen, so the bubble is visible in the video.
 */
class OverlayBubble(private val ctx: Context, private val since: Long, private val onStop: () -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 24; y = 120
    }

    private val tick = object : Runnable {
        override fun run() {
            val s = (SystemClock.elapsedRealtime() - since) / 1000
            view?.text = "● %02d:%02d".format(s / 60, s % 60)
            handler.postDelayed(this, 1000)
        }
    }

    fun show() {
        val d = ctx.resources.displayMetrics.density
        val tv = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
            background = GradientDrawable().apply { setColor(Color.argb(150, 200, 30, 40)); cornerRadius = 20 * d }
            alpha = 0.7f
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) { lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt(); wm.updateViewLayout(tv, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) onStop()
            }
            true
        }
        view = tv
        wm.addView(tv, lp)
        tick.run()
    }

    fun hide() {
        handler.removeCallbacks(tick)
        view?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        view = null
    }
}
