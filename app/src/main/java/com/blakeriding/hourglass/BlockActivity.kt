package com.blakeriding.hourglass

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blakeriding.hourglass.ui.Accent
import com.blakeriding.hourglass.ui.HourglassTheme
import com.blakeriding.hourglass.ui.Muted
import com.blakeriding.hourglass.ui.PasscodeDialog

/** The screen that covers a blocked app or site, like iOS's "Time Limit" screen. */
class BlockActivity : ComponentActivity() {
    private var block by mutableStateOf<Block?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        block = pending
        if (block == null) {
            finish()
            return
        }
        setContent {
            HourglassTheme {
                val b = block ?: return@HourglassTheme
                BackHandler { goHome() }
                BlockScreen(b, onOk = ::goHome, onLetThrough = ::finish)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pending?.let { block = it }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        @Volatile var pending: Block? = null
    }
}

private enum class Ask { NONE, IGNORE, PASSCODE, APPROVE, BREAK }

@Composable
private fun BlockScreen(b: Block, onOk: () -> Unit, onLetThrough: () -> Unit) {
    val context = LocalContext.current
    val s by Store.state.collectAsState()
    var ask by remember { mutableStateOf(Ask.NONE) }
    val now = nowZ()
    val nowMs = now.toInstant().toEpochMilli()
    val today = dayKey(now.toLocalDate())
    val isWeb = b.target.startsWith("web:")
    val name = if (isWeb) b.target.removePrefix("web:") else Apps.label(context, b.target)
    val left = breaksLeft(s, now)
    val oneMoreUsed = oneMoreKey(today, b.target) in s.oneMoreMinute
    val endOfDay = startOfDay(now.toLocalDate().plusDays(1), now.zone)

    fun grant(until: Long) {
        Store.update { it.copy(grants = it.grants.filter { g -> g.target != b.target } + Grant(b.target, until)) }
        onLetThrough()
    }

    val (title, body) = when (b.kind) {
        BlockKind.LIMIT -> "Time Limit" to "You've reached your ${formatDuration(b.minutes * MINUTE_MS)} limit on $name today."
        BlockKind.DOWNTIME -> "Downtime" to "$name isn't available during Downtime."
        BlockKind.APP_BLOCKED -> "Not Allowed" to "$name is blocked in Content & Privacy Restrictions."
        BlockKind.INSTALLS -> "Not Allowed" to "Installing and removing apps is turned off in Screen Time."
        BlockKind.SITE_ADULT -> "Restricted Site" to "$name looks like an adult website and is blocked."
        BlockKind.SITE_BLOCKED -> "Restricted Site" to "$name is on your Never Allow list."
        BlockKind.SITE_NOT_ALLOWED -> "Restricted Site" to "Only approved websites are allowed. $name isn't one of them."
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painterResource(R.drawable.ic_launcher_foreground), null,
                Modifier.size(120.dp), colorFilter = ColorFilter.tint(Accent),
            )
            Text(title, fontSize = 28.sp)
            Spacer(Modifier.height(10.dp))
            Text(body, color = Muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(36.dp))
            Button(onClick = onOk, modifier = Modifier.fillMaxWidth()) { Text("OK") }
            val limitLike = b.kind == BlockKind.LIMIT || b.kind == BlockKind.DOWNTIME
            if (limitLike && !b.strict) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { ask = Ask.IGNORE }, modifier = Modifier.fillMaxWidth()) { Text("Ignore Limit") }
            }
            if (limitLike && b.strict && !oneMoreUsed) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    Store.update { it.copy(oneMoreMinute = it.oneMoreMinute + oneMoreKey(today, b.target)) }
                    grant(nowMs + MINUTE_MS)
                }, modifier = Modifier.fillMaxWidth()) { Text("One More Minute") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { ask = Ask.PASSCODE }, modifier = Modifier.fillMaxWidth()) { Text("Ask For More Time") }
            Spacer(Modifier.height(24.dp))
            if (left > 0) {
                TextButton(onClick = { ask = Ask.BREAK }) {
                    Text("Emergency break (${s.breaks.minutes} min) · $left left today", color = MaterialTheme.colorScheme.error)
                }
            } else {
                Text("No emergency breaks left today", color = Muted, fontSize = 13.sp)
            }
        }
    }

    when (ask) {
        Ask.IGNORE -> AlertDialog(
            onDismissRequest = { ask = Ask.NONE },
            title = { Text("Ignore Limit") },
            text = {
                Column {
                    TextButton(onClick = { grant(nowMs + 15 * MINUTE_MS) }) { Text("Remind Me in 15 Minutes") }
                    TextButton(onClick = { grant(endOfDay) }) { Text("Ignore Limit for Today") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { ask = Ask.NONE }) { Text("Cancel") } },
        )
        Ask.PASSCODE -> PasscodeDialog(onDismiss = { ask = Ask.NONE }, onSuccess = { ask = Ask.APPROVE })
        Ask.APPROVE -> AlertDialog(
            onDismissRequest = { ask = Ask.NONE },
            title = { Text("Approve $name") },
            text = {
                Column {
                    TextButton(onClick = { grant(nowMs + 15 * MINUTE_MS) }) { Text("Approve for 15 Minutes") }
                    TextButton(onClick = { grant(nowMs + 60 * MINUTE_MS) }) { Text("Approve for an Hour") }
                    TextButton(onClick = { grant(endOfDay) }) { Text("Approve for the Day") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { ask = Ask.NONE }) { Text("Cancel") } },
        )
        Ask.BREAK -> AlertDialog(
            onDismissRequest = { ask = Ask.NONE },
            title = { Text("Take an emergency break?") },
            text = {
                Text(
                    "Everything unlocks for ${s.breaks.minutes} minutes. You'll have ${left - 1} " +
                        "break${if (left - 1 == 1) "" else "s"} left today.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        startBreak()
                        onLetThrough()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Start break") }
            },
            dismissButton = { TextButton(onClick = { ask = Ask.NONE }) { Text("Cancel") } },
        )
        Ask.NONE -> {}
    }
}

/** Starts an emergency break if one is left today. Returns false if none are left. */
fun startBreak(): Boolean {
    var ok = false
    Store.update {
        val now = nowZ()
        if (breaksLeft(it, now) <= 0 || breakUntil(it, now.toInstant().toEpochMilli()) != null) return@update it
        ok = true
        it.copy(breaksTaken = it.breaksTaken + now.toInstant().toEpochMilli())
    }
    Engine.poke()
    return ok
}
