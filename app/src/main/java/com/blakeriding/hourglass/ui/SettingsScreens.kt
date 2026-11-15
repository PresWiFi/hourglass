package com.blakeriding.hourglass.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.blakeriding.hourglass.Apps
import com.blakeriding.hourglass.BreakRules
import com.blakeriding.hourglass.Engine
import com.blakeriding.hourglass.Gate
import com.blakeriding.hourglass.MINUTE_MS
import com.blakeriding.hourglass.MonitorService
import com.blakeriding.hourglass.Passcode
import com.blakeriding.hourglass.Route
import com.blakeriding.hourglass.Store
import com.blakeriding.hourglass.TamperKind
import com.blakeriding.hourglass.adminIntent
import com.blakeriding.hourglass.batteryIntent
import com.blakeriding.hourglass.breaksStricter
import com.blakeriding.hourglass.canDrawOverlays
import com.blakeriding.hourglass.canNotify
import com.blakeriding.hourglass.formatClock
import com.blakeriding.hourglass.hasNotificationAccess
import com.blakeriding.hourglass.hasUsageAccess
import com.blakeriding.hourglass.ignoresBatteryOptimizations
import com.blakeriding.hourglass.isAccessibilityEnabled
import com.blakeriding.hourglass.isAdminActive
import com.blakeriding.hourglass.logTamper
import com.blakeriding.hourglass.nowMs
import com.blakeriding.hourglass.overlayIntent
import com.blakeriding.hourglass.removeAdmin
import com.blakeriding.hourglass.toast
import com.blakeriding.hourglass.usageAccessIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------- Permission checklist ----------

private data class Check(val title: String, val why: String, val ok: Boolean, val required: Boolean, val open: () -> Unit)

@Composable
private fun rememberChecks(launch: (Intent) -> Unit): List<Check> {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    // Re-read every permission each time we come back from a settings page.
    val ok = remember(tick) {
        listOf(
            hasUsageAccess(context), isAccessibilityEnabled(context), canDrawOverlays(context), isAdminActive(context),
            canNotify(context), ignoresBatteryOptimizations(context), hasNotificationAccess(context),
        )
    }
    return listOf(
        Check("Usage access", "Measures screen time and sees which app is open.", ok[0], true) {
            launch(usageAccessIntent(context))
        },
        Check("Accessibility service", "Instant blocking, website rules, and stops Screen Time being switched off.", ok[1], true) {
            launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        Check("Display over other apps", "Lets the block screen appear over any app.", ok[2], true) {
            launch(overlayIntent(context))
        },
        Check("Uninstall protection", "Device admin: Android won't uninstall Hourglass while it's on.", ok[3], true) {
            launch(adminIntent(context))
        },
        Check("Notifications", "Limit warnings, Downtime warnings and the weekly report.", ok[4], false) {
            if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        Check("Ignore battery optimization", "Stops the phone from killing Hourglass in the background.", ok[5], false) {
            launch(batteryIntent(context))
        },
        Check("Notification access", "Optional: counts notifications per app for the dashboard.", ok[6], false) {
            launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        },
    )
}

@Composable
private fun Checklist(checks: List<Check>) {
    Card(Modifier.padding(horizontal = 16.dp)) {
        checks.forEachIndexed { i, c ->
            if (i > 0) Divider()
            ListRow(
                c.title,
                subtitle = c.why,
                trailing = {
                    if (c.ok) Text("✓", color = Color(0xFF34C759), fontSize = 20.sp)
                    else TextButton(onClick = c.open) { Text(if (c.required) "Turn on" else "Allow") }
                },
            )
        }
    }
}

private fun Context.safeStart(i: Intent) {
    try {
        startActivity(i)
    } catch (_: Exception) {
        toast("Couldn't open that settings page")
    }
}

// ---------- First-run setup ----------

@Composable
fun SetupScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var first by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    val recovery = remember { Passcode.newRecoveryCode() }
    var saving by remember { mutableStateOf(false) }

    when (step) {
        0 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Spacer(Modifier.height(40.dp))
            Text("Hourglass", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Screen Time for Android.", color = Muted, fontSize = 18.sp)
            Spacer(Modifier.height(24.dp))
            listOf(
                "Weekly and daily reports with pickups and notifications",
                "Downtime: schedule time away from your screen",
                "App Limits for apps, categories and websites",
                "Content & Privacy: blocked apps, adult sites, installs, locked settings",
                "A separate Screen Time passcode, needed to loosen anything",
                "Emergency breaks: a few minutes off the leash, twice a day",
                "Hard to uninstall while Screen Time is on",
            ).forEach { Text("•  $it", modifier = Modifier.padding(vertical = 4.dp)) }
            Spacer(Modifier.height(24.dp))
            Text(
                "Tip: if you want it really strict, have someone else type the passcode, and keep the recovery code somewhere you can't easily reach.",
                color = Muted, fontSize = 13.sp,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
        }
        1 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) {
            Text("Permissions", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
            Footnote("Turn these on now. Once the passcode is set, these settings pages are locked.")
            val checks = rememberChecks { context.safeStart(it) }
            Checklist(checks)
            Footnote("On OnePlus: also open Settings › Apps › Hourglass › Battery and choose Unrestricted, and lock Hourglass in Recents.")
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { step = 2 },
                enabled = checks.first().ok,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) { Text(if (checks.all { it.ok || !it.required }) "Continue" else "Continue anyway") }
            if (!checks.first().ok) Footnote("Usage access is required.")
        }
        2 -> PinPad(
            title = if (first == null) "Create a Screen Time Passcode" else "Re-enter Passcode",
            subtitle = if (mismatch) "Passcodes didn't match. Try again." else "You'll need it to change settings or get more time.",
            onCancel = { step = 1; first = null },
            onComplete = { c ->
                val f = first
                when {
                    f == null -> { first = c; mismatch = false }
                    f == c -> { code = c; step = 3 }
                    else -> { first = null; mismatch = true }
                }
            },
        )
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Spacer(Modifier.height(40.dp))
            Text("Recovery code", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("If you forget your passcode, this code resets it. Write it down now. It won't be shown again.", color = Muted)
            Spacer(Modifier.height(24.dp))
            SelectionContainer {
                Text(recovery, fontSize = 30.sp, fontFamily = FontFamily.Monospace, color = Accent)
            }
            Spacer(Modifier.height(32.dp))
            Button(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val pass = withContext(Dispatchers.Default) { Passcode.hash(code) }
                        val rec = withContext(Dispatchers.Default) { Passcode.hash(Passcode.normalizeRecovery(recovery)) }
                        val defaults = setOfNotNull(Apps.defaultDialer(context), Apps.defaultSms(context))
                        Store.update { it.copy(passcode = pass, recovery = rec, enabled = true, alwaysAllowed = it.alwaysAllowed + defaults) }
                        MonitorService.start(context)
                        Engine.poke()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("I've written it down") }
        }
    }
}

// ---------- Settings tab ----------

@Composable
fun SettingsScreen(gate: Gate, unlocked: Boolean, push: (Route) -> Unit, onLock: () -> Unit) {
    val context = LocalContext.current
    val s by Store.state.collectAsState()
    var changing by remember { mutableStateOf(false) }
    var recovering by remember { mutableStateOf(false) }
    var breaksEdit by remember { mutableStateOf<BreakRules?>(null) }
    var confirmOff by remember { mutableStateOf(false) }
    val now = nowMs()
    val paused = s.protectionPausedUntil > now

    // System pages that list Hourglass are guarded, so opening one from here needs the passcode and pauses the guard briefly.
    val checks = rememberChecks { intent ->
        gate {
            Store.update { it.copy(protectionPausedUntil = nowMs() + 2 * MINUTE_MS) }
            context.safeStart(intent)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))

        SectionLabel("Protection")
        Checklist(checks)
        if (paused) Footnote("Protection is paused until ${formatClock(s.protectionPausedUntil)}.")

        SectionLabel("Screen Time")
        Card(Modifier.padding(horizontal = 16.dp)) {
            ListRow("Emergency Breaks", value = "${s.breaks.perDay} × ${s.breaks.minutes} min", onClick = { breaksEdit = s.breaks })
            Divider()
            ListRow("Change Screen Time Passcode", onClick = { gate { changing = true } })
            Divider()
            ListRow("Forgot Passcode?", onClick = { recovering = true })
            Divider()
            ListRow(
                "Pause Protection for 10 Minutes",
                subtitle = "For updating Hourglass or changing a locked system setting. Limits stay on.",
                onClick = {
                    gate {
                        Store.update { it.copy(protectionPausedUntil = nowMs() + 10 * MINUTE_MS) }
                        context.toast("Protection paused for 10 minutes")
                    }
                },
            )
            Divider()
            if (unlocked) {
                ListRow("Lock Settings Now", onClick = onLock)
                Divider()
            }
            ListRow("Tamper Log", value = "${s.tamper.size}", onClick = { push(Route.Tamper) })
        }

        Spacer(Modifier.height(16.dp))
        Card(Modifier.padding(horizontal = 16.dp)) {
            if (s.enabled) {
                ListRow("Turn Off Screen Time", titleColor = Danger, onClick = { gate { confirmOff = true } })
            } else {
                ListRow("Turn On Screen Time", titleColor = Accent, onClick = {
                    Store.update { it.copy(enabled = true) }
                    Engine.poke()
                })
            }
        }
        Footnote(
            "Turning Screen Time off stops all limits and protection and removes uninstall protection, so Hourglass can be uninstalled. " +
                "Android can't stop a factory reset or Safe Mode; check the tamper log if you share this with someone.",
        )
        LockHint(unlocked)
    }

    if (changing) NewPasscodeDialog(onDismiss = { changing = false }) { changing = false }
    if (recovering) RecoveryDialog(onDismiss = { recovering = false })
    breaksEdit?.let { b ->
        AlertDialog(
            onDismissRequest = { breaksEdit = null },
            title = { Text("Emergency Breaks") },
            text = {
                Column {
                    Text("Breaks per day")
                    DurationlessStepper(b.perDay, 0, 5) { breaksEdit = b.copy(perDay = it) }
                    Text("Minutes per break")
                    DurationlessStepper(b.minutes, 1, 15) { breaksEdit = b.copy(minutes = it) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    breaksEdit = null
                    gate.unless(breaksStricter(s.breaks, b)) { Store.update { it.copy(breaks = b) } }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { breaksEdit = null }) { Text("Cancel") } },
        )
    }
    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Turn Off Screen Time?") },
            text = { Text("All limits, Downtime, restrictions and protection stop until you turn it back on. Usage is still measured.") },
            confirmButton = {
                TextButton(onClick = {
                    Store.update { it.copy(enabled = false).logTamper(TamperKind.SCREEN_TIME_OFF, nowMs()) }
                    removeAdmin(context)
                    confirmOff = false
                }) { Text("Turn Off", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DurationlessStepper(value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        TextButton(onClick = { onChange((value - 1).coerceAtLeast(min)) }) { Text("−", fontSize = 22.sp) }
        Text("$value", fontSize = 18.sp)
        TextButton(onClick = { onChange((value + 1).coerceAtMost(max)) }) { Text("+", fontSize = 22.sp) }
    }
}

/** Enter a new passcode twice and save it. */
@Composable
fun NewPasscodeDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            PinPad(
                title = if (first == null) "Enter New Passcode" else "Re-enter New Passcode",
                subtitle = if (mismatch) "Passcodes didn't match." else null,
                onCancel = onDismiss,
                onComplete = { c ->
                    val f = first
                    when {
                        f == null -> { first = c; mismatch = false }
                        f == c -> scope.launch {
                            val h = withContext(Dispatchers.Default) { Passcode.hash(c) }
                            Store.update { it.copy(passcode = h, failedAttempts = 0, lockedUntil = 0) }
                            onDone()
                        }
                        else -> { first = null; mismatch = true }
                    }
                },
            )
        }
    }
}

@Composable
private fun RecoveryDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var verified by remember { mutableStateOf(false) }
    if (verified) {
        NewPasscodeDialog(onDismiss = onDismiss) { onDismiss() }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset Passcode") },
        text = {
            Column {
                Text("Enter the recovery code you wrote down at setup.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("XXXX-XXXX-XXXX") })
                error?.let { Text(it, color = Danger) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val s = Store.state.value
                    val now = nowMs()
                    if (s.lockedUntil > now) {
                        error = "Too many tries. Try again at ${formatClock(s.lockedUntil)}."
                        return@launch
                    }
                    val ok = withContext(Dispatchers.Default) { Passcode.matches(Passcode.normalizeRecovery(text), s.recovery) }
                    if (ok) {
                        Store.update { it.copy(failedAttempts = 0, lockedUntil = 0).logTamper(TamperKind.PASSCODE_RESET, now) }
                        verified = true
                    } else {
                        // Recovery guesses share the passcode lockout.
                        Store.update {
                            val n = it.failedAttempts + 1
                            it.copy(failedAttempts = n, lockedUntil = now + com.blakeriding.hourglass.lockoutMs(n))
                                .logTamper(TamperKind.WRONG_PASSCODE, now, "recovery code")
                        }
                        error = "That code doesn't match."
                    }
                }
            }) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------- Tamper log ----------

@Composable
fun TamperScreen(onBack: () -> Unit) {
    val s by Store.state.collectAsState()
    val fmt = remember { DateTimeFormatter.ofPattern("EEE MMM d, h:mm a") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        TopBar("Tamper Log", onBack)
        Footnote("Attempts to get around Screen Time, newest first.")
        Card(Modifier.padding(horizontal = 16.dp)) {
            if (s.tamper.isEmpty()) ListRow("Nothing yet")
            s.tamper.asReversed().forEachIndexed { i, e ->
                if (i > 0) Divider()
                ListRow(
                    e.kind.label,
                    subtitle = Instant.ofEpochMilli(e.at).atZone(ZoneId.systemDefault()).format(fmt) +
                        if (e.detail.isNotEmpty()) " · ${e.detail}" else "",
                )
            }
        }
    }
}

