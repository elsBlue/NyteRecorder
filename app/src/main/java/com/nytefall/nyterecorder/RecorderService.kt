package com.nytefall.nyterecorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat

class RecorderService : Service() {
    companion object {
        const val ACTION_START = "com.nytefall.nyterecorder.START"
        const val ACTION_STOP = "com.nytefall.nyterecorder.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 1

        fun stopIntent(c: Context) = Intent(c, RecorderService::class.java).setAction(ACTION_STOP)
    }

    private val main = Handler(Looper.getMainLooper())
    private var engine: RecordingEngine? = null
    private var bubble: OverlayBubble? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> engine?.stop() ?: stopSelf()
            else -> if (engine == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (engine != null) return
        val settings = SettingsStore.load(this)
        val micOk = settings.mic &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        createChannel()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (micOk) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(NOTIF_ID, buildNotification(SystemClock.elapsedRealtime()), type)

        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            fail("Screen capture permission missing.")
            return
        }
        val projection: MediaProjection = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        } catch (e: Exception) {
            fail("Could not start screen capture: ${e.message}")
            return
        }

        val eng = RecordingEngine(applicationContext, projection, settings) { uri, error -> main.post { onFinished(uri != null, error) } }
        engine = eng
        try {
            eng.start()
        } catch (e: Exception) {
            eng.stop() // cleans up, then reports through onFinished
            RecorderState.message.value = "Could not start: ${e.message ?: e.javaClass.simpleName}"
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        RecorderState.startedAt.value = startedAt
        RecorderState.recording.value = true
        val notes = eng.notes
        RecorderState.message.value = if (notes.isEmpty()) null else notes.joinToString(" ")
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(startedAt))
        if (settings.overlay && android.provider.Settings.canDrawOverlays(this)) {
            bubble = OverlayBubble(this, startedAt) { eng.stop() }.also { it.show() }
        }
    }

    private fun fail(msg: String) {
        RecorderState.message.value = msg
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onFinished(saved: Boolean, error: String?) {
        bubble?.hide(); bubble = null
        RecorderState.recording.value = false
        RecorderState.savedCount.value += 1
        if (saved) Toast.makeText(this, "Saved to Movies/NyteRecorder", Toast.LENGTH_SHORT).show()
        if (error != null && RecorderState.message.value == null) RecorderState.message.value = error
        engine = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(since: Long): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("Recording screen")
            .setContentText("Tap Stop to finish")
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - since))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        bubble?.hide()
        super.onDestroy()
    }
}
