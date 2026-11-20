package com.nytefall.nyterecorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Let the system lay out around the status/navigation bars so nothing is clipped.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFFF3B47), onPrimary = Color.White,
                    background = Color(0xFF0E0F12), surface = Color(0xFF181A20),
                    onBackground = Color(0xFFECECEF), onSurface = Color(0xFFECECEF),
                    secondaryContainer = Color(0xFF3A1D22), onSecondaryContainer = Color.White,
                ),
            ) { Screen() }
        }
    }
}

private enum class Step { IDLE, WAIT_PERMS, WAIT_OVERLAY, WAIT_PROJECTION }

@Composable
private fun Screen() {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    var s by remember { mutableStateOf(SettingsStore.load(ctx)) }
    fun update(n: RecSettings) { s = n; SettingsStore.save(ctx, n) }

    val recording by RecorderState.recording.collectAsState()
    val waiting by RecorderState.waiting.collectAsState()
    val message by RecorderState.message.collectAsState()
    val saved by RecorderState.savedCount.collectAsState()
    val recs = remember { mutableStateListOf<Rec>() }
    LaunchedEffect(saved, recording) {
        recs.clear(); recs.addAll(Recordings.list(ctx))
    }

    val hevc = remember { CodecSupport.hevcAvailable() }
    var plan by remember { mutableStateOf<CodecSupport.Plan?>(null) }
    LaunchedEffect(s) { plan = CodecSupport.plan(ctx, s) }
    val native = remember { CodecSupport.nativeDisplay(ctx) }

    var step by remember { mutableStateOf(Step.IDLE) }

    fun launchService(code: Int, data: Intent) {
        val i = Intent(ctx, RecorderService::class.java).setAction(RecorderService.ACTION_START)
            .putExtra(RecorderService.EXTRA_RESULT_CODE, code).putExtra(RecorderService.EXTRA_DATA, data)
        RecorderState.message.value = null
        ContextCompat.startForegroundService(ctx, i)
        activity.moveTaskToBack(true) // go to the game
    }

    val projLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        step = Step.IDLE
        val d = r.data
        if (r.resultCode == Activity.RESULT_OK && d != null) launchService(r.resultCode, d)
    }
    fun requestProjection() {
        step = Step.WAIT_PROJECTION
        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        projLauncher.launch(intent)
    }

    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(ctx)) update(s.copy(overlay = true))
        if (step == Step.WAIT_OVERLAY) requestProjection()
    }
    fun askOverlay() {
        overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == false &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(ctx, "Audio permission denied: video will have no sound.", Toast.LENGTH_LONG).show()
        }
        if (s.overlay && !Settings.canDrawOverlays(ctx)) { step = Step.WAIT_OVERLAY; askOverlay() } else requestProjection()
    }

    fun onStart() {
        val need = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        step = Step.WAIT_PERMS
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
        else if (s.overlay && !Settings.canDrawOverlays(ctx)) { step = Step.WAIT_OVERLAY; askOverlay() }
        else requestProjection()
    }

    fun batteryExempt(): Boolean = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    var battery by remember { mutableStateOf(batteryExempt()) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 18.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Nyte Recorder", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("Internal audio · hardware encoder · built for gaming", fontSize = 13.sp, color = Color(0xFF9A9CA6))
            }
            item {
                Button(
                    onClick = { if (recording) ctx.startService(RecorderService.stopIntent(ctx)) else onStart() },
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (recording) Color(0xFF3A3D47) else Color(0xFFFF3B47)),
                ) { Text(if (waiting) "Cancel (waiting for landscape)" else if (recording) "Stop recording" else "Start recording", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
            }
            if (message != null) item { Text(message!!, color = Color(0xFFFFB74D), fontSize = 13.sp) }

            item { Label("Resolution") }
            item {
                Chips(Res.entries, s.res, { it.label }) { update(s.copy(res = it)) }
                val (l, sh) = CodecSupport.outputSize(s.res, native.long, native.short)
                Text(
                    when (s.res) {
                        Res.P1080 -> "1920 px wide, native aspect (≈ ${l.toInt()}×${sh.toInt()}). Lightest 1080p-class option."
                        Res.NATIVE -> "Full ${native.long}×${native.short} (1080 px high, no scaling)."
                        Res.P720 -> "1280 px wide, native aspect. Lowest load."
                    }, fontSize = 12.sp, color = Color(0xFF9A9CA6),
                )
            }
            item { Label("Frame rate") }
            item { Chips(listOf(30, 60), s.fps, { "$it fps" }) { update(s.copy(fps = it)) } }
            item { Label("Bitrate (VBR)") }
            item { Chips(listOf(8, 12, 16), s.bitrateMbps, { "$it Mbps" }) { update(s.copy(bitrateMbps = it)) } }
            item { Label("Codec") }
            item {
                Chips(if (hevc) Codec.entries else listOf(Codec.AVC), s.codec, { it.label }) { update(s.copy(codec = it)) }
                if (!hevc) Text("H.265 hardware encoder not available on this device.", fontSize = 12.sp, color = Color(0xFF9A9CA6))
            }
            item { Label("Orientation") }
            item {
                Chips(Orient.entries, s.orient, { it.label }) { update(s.copy(orient = it)) }
                Text(
                    "Landscape: the frame is always landscape and recording begins once the screen is landscape (open your game), so content fills the frame. Auto: frame matches the screen when you press Start. Portrait: always portrait.",
                    fontSize = 12.sp, color = Color(0xFF9A9CA6),
                )
            }
            item { SwitchRow("Record microphone too", "Off by default. Mixed with game audio.", s.mic) { update(s.copy(mic = it)) } }
            item {
                SwitchRow("Floating timer bubble", "Off by default. Tap to stop. Note: it shows up in the video.", s.overlay) {
                    if (it && !Settings.canDrawOverlays(ctx)) { step = Step.IDLE; askOverlay() } else update(s.copy(overlay = it))
                }
            }
            item {
                val p = plan
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("This recording", fontWeight = FontWeight.SemiBold)
                    if (p != null) {
                        Text("${p.width}×${p.height} @ ${p.fps} fps · ${p.bitrate / 1_000_000} Mbps · ${if (p.mime == Codec.AVC.mime) "H.264" else "H.265"}", fontSize = 13.sp)
                        Text("Encoder: ${p.codecName ?: "none (hardware not found)"}", fontSize = 12.sp, color = Color(0xFF9A9CA6))
                        if (p.maxFps != null) Text("Encoder reports up to ${p.maxFps.toInt()} fps at this size (${p.alignment}px aligned)", fontSize = 12.sp, color = Color(0xFF9A9CA6))
                        p.notes.forEach { Text(it, fontSize = 12.sp, color = Color(0xFFFFB74D)) }
                    }
                    Text("Audio: AAC-LC 48 kHz stereo 192 kbps", fontSize = 12.sp, color = Color(0xFF9A9CA6))
                }
            }
            item {
                if (!battery) OutlinedButton(onClick = {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                    }
                    battery = batteryExempt()
                }, modifier = Modifier.fillMaxWidth()) { Text("Allow background running (battery optimization)") }
                else Text("Battery optimization: exempt ✓", fontSize = 12.sp, color = Color(0xFF9A9CA6))
            }

            item { Label("Recent recordings") }
            item {
                Text("Diagnostic logs (.log.txt, one per recording): ${RecLog.DIR}  - open it in the Files app.", fontSize = 12.sp, color = Color(0xFF9A9CA6))
            }
            if (recs.isEmpty()) item { Text("Nothing yet. Videos are saved to Movies/NyteRecorder.", fontSize = 13.sp, color = Color(0xFF9A9CA6)) }
            items(recs, key = { it.uri.toString() }) { r ->
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)).padding(12.dp)) {
                    Text(r.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "${fmtDur(r.durationMs)} · ${r.size / 1_048_576} MB · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.dateSec * 1000))}",
                        fontSize = 12.sp, color = Color(0xFF9A9CA6),
                    )
                    Row {
                        TextButton({ Recordings.play(ctx, r) }) { Text("Play") }
                        TextButton({ Recordings.share(ctx, r) }) { Text("Share") }
                        TextButton({
                            if (!Recordings.shareLog(ctx, r)) Toast.makeText(ctx, "No log found for this recording.", Toast.LENGTH_SHORT).show()
                        }) { Text("Share log") }
                        TextButton({
                            if (Recordings.delete(ctx, r)) recs.remove(r)
                            else Toast.makeText(ctx, "Can't delete this file from here.", Toast.LENGTH_SHORT).show()
                        }) { Text("Delete", color = Color(0xFFFF6B75)) }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun fmtDur(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Label(t: String) = Text(t, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFB9BBC4))

@Composable
private fun <T> Chips(items: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { FilterChip(selected = it == selected, onClick = { onPick(it) }, label = { Text(label(it)) }) }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(sub, fontSize = 12.sp, color = Color(0xFF9A9CA6))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
