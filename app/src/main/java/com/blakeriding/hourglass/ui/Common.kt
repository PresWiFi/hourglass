package com.blakeriding.hourglass.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.blakeriding.hourglass.Apps
import com.blakeriding.hourglass.Cat
import com.blakeriding.hourglass.CheckResult
import com.blakeriding.hourglass.checkPasscode
import com.blakeriding.hourglass.formatClock
import com.blakeriding.hourglass.nowMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val Accent = Color(0xFF7D73FF)
val Danger = Color(0xFFFF6B6B)
val Muted = Color(0xFF9A9AA6)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Color(0xFF5AC8FA),
    tertiary = Color(0xFF34C759),
    background = Color(0xFF101014),
    surface = Color(0xFF101014),
    surfaceVariant = Color(0xFF1C1C22),
    surfaceContainer = Color(0xFF1C1C22),
    surfaceContainerHigh = Color(0xFF26262E),
    error = Danger,
)

@Composable
fun HourglassTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)

/** Category colors, in the spirit of iOS Screen Time's chart. */
fun catColor(c: Cat): Color = when (c) {
    Cat.SOCIAL -> Color(0xFF0A84FF)
    Cat.GAMES -> Color(0xFFBF5AF2)
    Cat.ENTERTAINMENT -> Color(0xFF5AC8FA)
    Cat.CREATIVITY -> Color(0xFFFF9F0A)
    Cat.PRODUCTIVITY -> Color(0xFF30D158)
    Cat.INFO -> Color(0xFFFFD60A)
    Cat.NAVIGATION -> Color(0xFF64D2FF)
    Cat.SHOPPING -> Color(0xFFFF375F)
    Cat.UTILITIES -> Color(0xFF8E8E93)
    Cat.OTHER -> Color(0xFF636366)
}

const val PASSCODE_LENGTH = 4

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) { Column(Modifier.padding(vertical = 4.dp), content = content) }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = Muted,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
fun Footnote(text: String) {
    Text(text, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

@Composable
fun ListRow(
    title: String,
    value: String? = null,
    subtitle: String? = null,
    titleColor: Color = Color.Unspecified,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 13.sp)
        }
        if (value != null) Text(value, color = Muted)
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (onClick != null && trailing == null) Text("  ›", color = Muted, fontSize = 18.sp)
    }
}

@Composable
fun Divider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = Color(0x22FFFFFF))

@Composable
fun AppIcon(pkg: String, size: Int = 32) {
    val context = LocalContext.current
    val bmp = remember(pkg) { Apps.icon(context, pkg) }
    if (bmp != null) {
        Image(bmp.asImageBitmap(), null, Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)))
    } else {
        Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF33333B)))
    }
}

@Composable
fun SiteIcon(domain: String, size: Int = 32) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2C3E66)),
        contentAlignment = Alignment.Center,
    ) { Text(domain.take(1).uppercase(), fontWeight = FontWeight.Bold) }
}

/**
 * A full-screen passcode prompt. Checks the Screen Time passcode (with lockout) and calls [onSuccess].
 * [extra] adds e.g. "Forgot passcode?" under the keypad.
 */
@Composable
fun PasscodeDialog(
    title: String = "Enter Screen Time Passcode",
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var lockedUntil by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(lockedUntil) {
        while (lockedUntil > nowMs()) {
            now = nowMs()
            delay(1000)
        }
        now = nowMs()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val locked = lockedUntil > now
            PinPad(
                title = title,
                subtitle = when {
                    locked -> "Too many tries. Try again at ${formatClock(lockedUntil)}."
                    else -> error
                },
                enabled = !busy && !locked,
                onCancel = onDismiss,
                onComplete = { code ->
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.Default) { checkPasscode(code, nowMs()) }
                        busy = false
                        when (r) {
                            CheckResult.Ok -> onSuccess()
                            CheckResult.Wrong -> error = "Wrong passcode"
                            is CheckResult.LockedOut -> {
                                error = null
                                lockedUntil = r.until
                            }
                        }
                    }
                },
                extra = extra,
            )
        }
    }
}

/** A 4-digit keypad, iOS style. Clears itself after each attempt. */
@Composable
fun PinPad(
    title: String,
    subtitle: String?,
    enabled: Boolean = true,
    onCancel: (() -> Unit)?,
    onComplete: (String) -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    var code by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontSize = 20.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(PASSCODE_LENGTH) { i ->
                Box(
                    Modifier.size(14.dp).clip(CircleShape)
                        .background(if (i < code.length) Color.White else Color(0x33FFFFFF)),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(subtitle ?: " ", color = Danger, textAlign = TextAlign.Center, modifier = Modifier.height(40.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.size(76.dp).clip(CircleShape)
                            .background(if (k.isEmpty() || k == "⌫") Color.Transparent else Color(0xFF2A2A31))
                            .then(
                                if (k.isEmpty() || !enabled) Modifier else Modifier.clickable {
                                    if (k == "⌫") {
                                        code = code.dropLast(1)
                                    } else if (code.length < PASSCODE_LENGTH) {
                                        code += k
                                        if (code.length == PASSCODE_LENGTH) {
                                            val c = code
                                            code = ""
                                            onComplete(c)
                                        }
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) { Text(k, fontSize = 28.sp, color = if (enabled) Color.White else Muted) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
        extra?.invoke()
    }
}
