package com.blakeriding.hourglass.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.blakeriding.hourglass.AppInfo
import com.blakeriding.hourglass.AppLimit
import com.blakeriding.hourglass.Apps
import com.blakeriding.hourglass.Cat
import com.blakeriding.hourglass.DowntimeOverride
import com.blakeriding.hourglass.Engine
import com.blakeriding.hourglass.Gate
import com.blakeriding.hourglass.MINUTE_MS
import com.blakeriding.hourglass.Route
import com.blakeriding.hourglass.Store
import com.blakeriding.hourglass.WebMode
import com.blakeriding.hourglass.Window
import com.blakeriding.hourglass.breakUntil
import com.blakeriding.hourglass.breaksLeft
import com.blakeriding.hourglass.domainOf
import com.blakeriding.hourglass.downtimeActive
import com.blakeriding.hourglass.formatClock
import com.blakeriding.hourglass.formatDuration
import com.blakeriding.hourglass.formatMinuteOfDay
import com.blakeriding.hourglass.limitLeftMs
import com.blakeriding.hourglass.limitTitle
import com.blakeriding.hourglass.limitUsedMs
import com.blakeriding.hourglass.newId
import com.blakeriding.hourglass.nowZ
import com.blakeriding.hourglass.overrideEnd
import com.blakeriding.hourglass.startBreak
import com.blakeriding.hourglass.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.format.TextStyle

// ---------- Shared bits ----------

@Composable
fun TopBar(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun LockHint(unlocked: Boolean) {
    if (!unlocked) Footnote("🔒 Changes need your Screen Time passcode.")
}

@Composable
fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) =
    ListRow(title, subtitle = subtitle, trailing = { Switch(checked, onCheckedChange = onChange) })

@Composable
private fun isoDayName(d: Int): String = DayOfWeek.of(d).getDisplayName(TextStyle.FULL, LocalConfiguration.current.locales[0])

/** Hours and minutes with steppers. */
@Composable
fun DurationPicker(minutes: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Stepper("${minutes / 60} hr", onMinus = { onChange((minutes - 60).coerceAtLeast(0)) }, onPlus = { onChange((minutes + 60).coerceAtMost(23 * 60 + 55)) })
        Spacer(Modifier.width(16.dp))
        Stepper("${minutes % 60} min", onMinus = { onChange((minutes - 5).coerceAtLeast(0)) }, onPlus = { onChange((minutes + 5).coerceAtMost(23 * 60 + 55)) })
    }
}

@Composable
private fun Stepper(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onMinus) { Text("−") }
        Text(label, modifier = Modifier.width(64.dp).padding(horizontal = 8.dp))
        OutlinedButton(onClick = onPlus) { Text("+") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(title: String, minute: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val st = rememberTimePickerState(minute / 60, minute % 60, false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(st) },
        confirmButton = { TextButton(onClick = { onPick(st.hour * 60 + st.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Full-screen app chooser with search. */
@Composable
fun AppPickerDialog(title: String, selected: Set<String>, onDismiss: () -> Unit, onDone: (Set<String>) -> Unit) {
    val context = LocalContext.current
    val apps by produceState<List<AppInfo>?>(null) { value = withContext(Dispatchers.IO) { Apps.launchable(context) } }
    var picked by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onDone(picked) }) { Text("Done") }
                }
                OutlinedTextField(
                    query, { query = it }, placeholder = { Text("Search") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                val list = apps
                if (list == null) {
                    Footnote("Loading apps…")
                } else {
                    val shown = list.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
                    LazyColumn {
                        items(shown, key = { it.pkg }) { a ->
                            ListRow(
                                a.label,
                                subtitle = a.cat.label,
                                leading = { AppIcon(a.pkg) },
                                trailing = { Checkbox(a.pkg in picked, onCheckedChange = null) },
                                onClick = { picked = if (a.pkg in picked) picked - a.pkg else picked + a.pkg },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A list of website domains with add/remove. */
@Composable
fun SiteList(sites: List<String>, onChange: (List<String>) -> Unit) {
    val context = LocalContext.current
    var adding by remember { mutableStateOf("") }
    sites.forEachIndexed { i, d ->
        if (i > 0) Divider()
        ListRow(d, leading = { SiteIcon(d, 28) }, trailing = { TextButton(onClick = { onChange(sites - d) }) { Text("Remove", color = Danger) } })
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(adding, { adding = it }, placeholder = { Text("example.com") }, singleLine = true, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            val d = domainOf(adding)
            if (d == null) context.toast("That doesn't look like a website") else if (d !in sites) onChange(sites + d)
            adding = ""
        }) { Text("Add") }
    }
}

// ---------- Main Screen Time list ----------

@Composable
fun LimitsScreen(gate: Gate, unlocked: Boolean, push: (Route) -> Unit) {
    val context = LocalContext.current
    val s by Store.state.collectAsState()
    val today by Engine.today.collectAsState()
    val web by Engine.webToday.collectAsState()
    var confirmBreak by remember { mutableStateOf(false) }
    val now = nowZ()
    val nowMs = now.toInstant().toEpochMilli()
    val inDowntime = downtimeActive(s, now)
    val breakEnd = breakUntil(s, nowMs)
    val left = breaksLeft(s, now)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text("Screen Time", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        if (!s.enabled) Footnote("Screen Time is off. Turn it on in Settings.")

        Card(Modifier.padding(horizontal = 16.dp)) {
            ListRow(
                "Emergency breaks",
                subtitle = when {
                    breakEnd != null -> "On a break until ${formatClock(breakEnd)}"
                    else -> "$left of ${s.breaks.perDay} left today · ${s.breaks.minutes} min each"
                },
                trailing = {
                    Button(onClick = { confirmBreak = true }, enabled = left > 0 && breakEnd == null && s.enabled) { Text("Take one") }
                },
            )
            Divider()
            if (inDowntime) {
                ListRow(
                    "Downtime is on",
                    subtitle = s.downtimeOverride?.takeIf { it.on }?.let { "Until ${formatClock(it.until)}" } ?: "Scheduled",
                    trailing = {
                        TextButton(onClick = {
                            gate { Store.update { it.copy(downtimeOverride = DowntimeOverride(false, overrideEnd(it, nowZ()))) } }
                        }) { Text("Turn Off") }
                    },
                )
            } else {
                // Turning Downtime on is stricter, so it doesn't need the passcode.
                ListRow(
                    "Turn On Downtime Until Tomorrow",
                    titleColor = Accent,
                    onClick = {
                        Store.update { it.copy(downtimeOverride = DowntimeOverride(true, overrideEnd(it, nowZ()))) }
                        Engine.poke()
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(Modifier.padding(horizontal = 16.dp)) {
            val d = s.downtime
            ListRow(
                "Downtime",
                subtitle = when {
                    !d.enabled -> "Schedule time away from the screen"
                    d.everyDay -> "${formatMinuteOfDay(d.window.start)} – ${formatMinuteOfDay(d.window.end)}"
                    else -> "Custom schedule on ${d.days.size} day${if (d.days.size == 1) "" else "s"}"
                },
                onClick = { push(Route.Downtime) },
            )
            Divider()
            ListRow("App Limits", subtitle = "${s.limits.count { it.enabled }} on · daily time limits for apps and sites", onClick = { push(Route.AppLimits) })
            Divider()
            ListRow("Always Allowed", subtitle = "${s.alwaysAllowed.size} apps you can use at any time", onClick = { push(Route.AlwaysAllowed) })
            Divider()
            ListRow("Content & Privacy Restrictions", subtitle = "Blocked apps, websites, installs, locked settings", onClick = { push(Route.Content) })
        }

        if (s.limits.isNotEmpty()) {
            SectionLabel("Today's limits")
            Card(Modifier.padding(horizontal = 16.dp)) {
                val u = com.blakeriding.hourglass.Usage(today.apps, web) { Apps.category(context, it) }
                s.limits.filter { it.enabled }.forEachIndexed { i, l ->
                    if (i > 0) Divider()
                    val leftMs = limitLeftMs(l, u, now)
                    ListRow(
                        limitTitle(l) { Apps.label(context, it) },
                        subtitle = "${formatDuration(limitUsedMs(l, u))} used",
                        value = if (leftMs <= 0) "Limit reached" else "${formatDuration(leftMs)} left",
                        onClick = { push(Route.LimitEdit(l.id)) },
                    )
                }
            }
        }
        LockHint(unlocked)
    }

    if (confirmBreak) {
        AlertDialog(
            onDismissRequest = { confirmBreak = false },
            title = { Text("Take an emergency break?") },
            text = { Text("Everything unlocks for ${s.breaks.minutes} minutes. You'll have ${left - 1} left today.") },
            confirmButton = {
                Button(onClick = {
                    confirmBreak = false
                    if (!startBreak()) context.toast("No breaks left today")
                }) { Text("Start break") }
            },
            dismissButton = { TextButton(onClick = { confirmBreak = false }) { Text("Cancel") } },
        )
    }
}

// ---------- Downtime ----------

@Composable
fun DowntimeScreen(gate: Gate, unlocked: Boolean, onBack: () -> Unit) {
    val saved by Store.state.collectAsState()
    var d by remember { mutableStateOf(saved.downtime) }
    var picking by remember { mutableStateOf<Pair<Int, Boolean>?>(null) } // (isoDay or 0 for every day, isStart)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        TopBar("Downtime", onBack) {
            TextButton(onClick = {
                gate {
                    Store.update { it.copy(downtime = d) }
                    Engine.poke()
                    onBack()
                }
            }, enabled = d != saved.downtime) { Text("Save") }
        }
        Footnote("During Downtime only Always Allowed apps (and phone calls) work. You get a 5-minute warning before it starts.")
        Card(Modifier.padding(horizontal = 16.dp)) {
            SwitchRow("Scheduled", d.enabled) { d = d.copy(enabled = it) }
        }
        if (d.enabled) {
            Spacer(Modifier.height(16.dp))
            Card(Modifier.padding(horizontal = 16.dp)) {
                ListRow("Every Day", leading = { RadioButton(d.everyDay, null) }, onClick = { d = d.copy(everyDay = true) })
                Divider()
                ListRow("Customize Days", leading = { RadioButton(!d.everyDay, null) }, onClick = {
                    d = d.copy(everyDay = false, days = d.days.ifEmpty { (1..7).associateWith { d.window } })
                })
            }
            Spacer(Modifier.height(16.dp))
            Card(Modifier.padding(horizontal = 16.dp)) {
                if (d.everyDay) {
                    ListRow("From", value = formatMinuteOfDay(d.window.start), onClick = { picking = 0 to true })
                    Divider()
                    ListRow("To", value = formatMinuteOfDay(d.window.end), onClick = { picking = 0 to false })
                } else {
                    (1..7).forEachIndexed { i, day ->
                        if (i > 0) Divider()
                        val w = d.days[day]
                        ListRow(
                            isoDayName(day),
                            subtitle = w?.let { "${formatMinuteOfDay(it.start)} – ${formatMinuteOfDay(it.end)}" } ?: "Off",
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (w != null) {
                                        TextButton(onClick = { picking = day to true }) { Text("From") }
                                        TextButton(onClick = { picking = day to false }) { Text("To") }
                                    }
                                    Switch(w != null, onCheckedChange = { on ->
                                        d = d.copy(days = if (on) d.days + (day to d.window) else d.days - day)
                                    })
                                }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Card(Modifier.padding(horizontal = 16.dp)) {
                SwitchRow(
                    "Block at Downtime", d.blockAtDowntime,
                    subtitle = "On: needs the passcode for more time. Off: a reminder you can ignore.",
                ) { d = d.copy(blockAtDowntime = it) }
            }
        }
        LockHint(unlocked)
    }

    picking?.let { (day, isStart) ->
        val w = if (day == 0) d.window else d.days[day] ?: d.window
        TimeDialog(if (isStart) "From" else "To", if (isStart) w.start else w.end, onDismiss = { picking = null }) { m ->
            val nw = if (isStart) Window(m, w.end) else Window(w.start, m)
            d = if (day == 0) d.copy(window = nw) else d.copy(days = d.days + (day to nw))
            picking = null
        }
    }
}

// ---------- App limits ----------

@Composable
fun LimitsListScreen(gate: Gate, unlocked: Boolean, push: (Route) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val s by Store.state.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        TopBar("App Limits", onBack)
        Footnote("Set daily time limits for apps, categories and websites. Limits reset at midnight. You get a warning 5 minutes before.")
        Card(Modifier.padding(horizontal = 16.dp)) {
            s.limits.forEachIndexed { i, l ->
                if (i > 0) Divider()
                ListRow(
                    limitTitle(l) { Apps.label(context, it) },
                    subtitle = "${formatDuration(l.minutes * MINUTE_MS)} a day" +
                        (if (l.dayMinutes.isNotEmpty()) ", customized" else "") +
                        (if (!l.enabled) " · off" else ""),
                    onClick = { push(Route.LimitEdit(l.id)) },
                )
            }
            if (s.limits.isNotEmpty()) Divider()
            ListRow("Add Limit", titleColor = Accent, onClick = { gate { push(Route.LimitEdit(null)) } })
        }
        LockHint(unlocked)
    }
}

@Composable
fun LimitEditScreen(id: String?, presetApp: String?, gate: Gate, unlocked: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val s by Store.state.collectAsState()
    val existing = s.limits.firstOrNull { it.id == id }
    var l by remember {
        mutableStateOf(existing ?: AppLimit(newId(), apps = setOfNotNull(presetApp)))
    }
    var picker by remember { mutableStateOf(false) }
    var customize by remember { mutableStateOf(l.dayMinutes.isNotEmpty()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        TopBar(if (existing == null) "New Limit" else "Edit Limit", onBack) {
            TextButton(
                onClick = {
                    gate {
                        val final = if (customize) l else l.copy(dayMinutes = emptyMap())
                        Store.update { st ->
                            st.copy(limits = if (existing == null) st.limits + final else st.limits.map { if (it.id == final.id) final else it })
                        }
                        Engine.poke()
                        onBack()
                    }
                },
                enabled = (l.apps.isNotEmpty() || l.categories.isNotEmpty() || l.domains.isNotEmpty()) && l != existing,
            ) { Text(if (existing == null) "Add" else "Save") }
        }

        SectionLabel("Categories")
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Cat.entries.forEach { c ->
                FilterChip(
                    selected = c in l.categories,
                    onClick = { l = l.copy(categories = if (c in l.categories) l.categories - c else l.categories + c) },
                    label = { Text(c.label, fontSize = 12.sp) },
                )
            }
        }

        SectionLabel("Apps")
        Card(Modifier.padding(horizontal = 16.dp)) {
            l.apps.sortedBy { Apps.label(context, it) }.forEach { pkg ->
                ListRow(Apps.label(context, pkg), leading = { AppIcon(pkg, 28) })
                Divider()
            }
            ListRow("Choose Apps", titleColor = Accent, onClick = { picker = true })
        }

        SectionLabel("Websites")
        Card(Modifier.padding(horizontal = 16.dp)) {
            SiteList(l.domains.sorted()) { l = l.copy(domains = it.toSet()) }
        }
        Footnote("Website time is tracked in Chrome, Samsung Internet, Firefox, Edge, Brave, DuckDuckGo and Opera.")

        SectionLabel("Time")
        Card(Modifier.padding(horizontal = 16.dp)) {
            DurationPicker(l.minutes) { l = l.copy(minutes = it) }
            Divider()
            SwitchRow("Customize Days", customize) {
                customize = it
                if (it && l.dayMinutes.isEmpty()) l = l.copy(dayMinutes = (1..7).associateWith { l.minutes })
            }
            if (customize) {
                (1..7).forEach { day ->
                    Divider()
                    Text(isoDayName(day), modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                    DurationPicker(l.dayMinutes[day] ?: l.minutes) { l = l.copy(dayMinutes = l.dayMinutes + (day to it)) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Card(Modifier.padding(horizontal = 16.dp)) {
            SwitchRow(
                "Block at End of Limit", l.blockAtEnd,
                subtitle = "On: needs the passcode for more time. Off: a reminder you can ignore.",
            ) { l = l.copy(blockAtEnd = it) }
            Divider()
            SwitchRow("Limit On", l.enabled) { l = l.copy(enabled = it) }
        }
        if (existing != null) {
            Spacer(Modifier.height(16.dp))
            Card(Modifier.padding(horizontal = 16.dp)) {
                ListRow("Delete Limit", titleColor = Danger, onClick = {
                    gate {
                        Store.update { st -> st.copy(limits = st.limits.filter { it.id != existing.id }) }
                        onBack()
                    }
                })
            }
        }
        LockHint(unlocked)
    }

    if (picker) {
        AppPickerDialog("Choose Apps", l.apps, onDismiss = { picker = false }) {
            l = l.copy(apps = it)
            picker = false
        }
    }
}

// ---------- Always allowed ----------

@Composable
fun AlwaysAllowedScreen(gate: Gate, unlocked: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val s by Store.state.collectAsState()
    var picker by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        TopBar("Always Allowed", onBack)
        Footnote("These apps work during Downtime and aren't counted against App Limits. Phone calls and Settings always work.")
        Card(Modifier.padding(horizontal = 16.dp)) {
            s.alwaysAllowed.sortedBy { Apps.label(context, it) }.forEach { pkg ->
                ListRow(Apps.label(context, pkg), leading = { AppIcon(pkg, 28) }, trailing = {
                    TextButton(onClick = { gate { Store.update { it.copy(alwaysAllowed = it.alwaysAllowed - pkg) } } }) { Text("Remove", color = Danger) }
                })
                Divider()
            }
            ListRow("Choose Apps", titleColor = Accent, onClick = { gate { picker = true } })
        }
        LockHint(unlocked)
    }
    if (picker) {
        AppPickerDialog("Always Allowed", s.alwaysAllowed, onDismiss = { picker = false }) { set ->
            Store.update { it.copy(alwaysAllowed = set) }
            picker = false
        }
    }
}

// ---------- Content & privacy ----------

@Composable
fun ContentScreen(gate: Gate, unlocked: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val saved by Store.state.collectAsState()
    var c by remember { mutableStateOf(saved.content) }
    var picker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        TopBar("Content & Privacy", onBack) {
            TextButton(onClick = {
                gate {
                    Store.update { it.copy(content = c) }
                    Engine.poke()
                    onBack()
                }
            }, enabled = c != saved.content) { Text("Save") }
        }

        SectionLabel("Blocked apps")
        Card(Modifier.padding(horizontal = 16.dp)) {
            c.blockedApps.sortedBy { Apps.label(context, it) }.forEach { pkg ->
                ListRow(Apps.label(context, pkg), leading = { AppIcon(pkg, 28) })
                Divider()
            }
            ListRow("Choose Apps", titleColor = Accent, onClick = { picker = true })
        }
        Footnote("Blocked apps never open, at any time of day.")

        SectionLabel("App Store")
        Card(Modifier.padding(horizontal = 16.dp)) {
            SwitchRow("Block Installing & Deleting Apps", c.blockInstalls, subtitle = "Blocks the Play Store and the app installer") {
                c = c.copy(blockInstalls = it)
            }
        }

        SectionLabel("Web content")
        Card(Modifier.padding(horizontal = 16.dp)) {
            listOf(
                WebMode.UNRESTRICTED to "Unrestricted",
                WebMode.LIMIT_ADULT to "Limit Adult Websites",
                WebMode.ALLOWED_ONLY to "Allowed Websites Only",
            ).forEachIndexed { i, (m, label) ->
                if (i > 0) Divider()
                ListRow(label, leading = { RadioButton(c.webMode == m, null) }, onClick = { c = c.copy(webMode = m) })
            }
        }
        if (c.webMode == WebMode.LIMIT_ADULT) {
            Footnote("Blocks well-known adult sites and addresses with adult words in them. For stronger filtering, set Private DNS to a family filter (e.g. family-filter-dns.cleanbrowsing.org) and lock it below.")
        }
        if (c.webMode != WebMode.ALLOWED_ONLY) {
            SectionLabel("Never allow")
            Card(Modifier.padding(horizontal = 16.dp)) { SiteList(c.blockedSites) { c = c.copy(blockedSites = it) } }
        }
        if (c.webMode != WebMode.UNRESTRICTED) {
            SectionLabel(if (c.webMode == WebMode.ALLOWED_ONLY) "Allowed websites" else "Always allow")
            Card(Modifier.padding(horizontal = 16.dp)) { SiteList(c.allowedSites) { c = c.copy(allowedSites = it) } }
        }
        Footnote("Websites are checked in Chrome, Samsung Internet, Firefox, Edge, Brave, DuckDuckGo and Opera. Block other browsers with Blocked apps.")

        SectionLabel("Home screen")
        Card(Modifier.padding(horizontal = 16.dp)) {
            SwitchRow("Block Google Discover", c.blockDiscover, subtitle = "The news feed when you swipe to the left page of the home screen") {
                c = c.copy(blockDiscover = it)
            }
        }
        Footnote("The Google app itself (search, Lens) still works. Emergency breaks unblock Discover too.")

        SectionLabel("Lock system settings")
        Card(Modifier.padding(horizontal = 16.dp)) {
            SwitchRow("Date & Time", c.lockDateTime, subtitle = "Stops changing the clock to get around limits") { c = c.copy(lockDateTime = it) }
            Divider()
            SwitchRow("Accounts", c.lockAccounts, subtitle = "Adding or removing accounts") { c = c.copy(lockAccounts = it) }
            Divider()
            SwitchRow("Developer Options", c.lockDeveloper, subtitle = "USB and wireless debugging (ADB can switch Hourglass off)") { c = c.copy(lockDeveloper = it) }
            Divider()
            SwitchRow("Private DNS", c.lockPrivateDns, subtitle = "Keeps a filtering DNS in place") { c = c.copy(lockPrivateDns = it) }
        }
        Footnote("Hourglass's own app info, uninstall, device admin and accessibility screens are always protected while Screen Time is on.")
        LockHint(unlocked)
    }

    if (picker) {
        AppPickerDialog("Blocked Apps", c.blockedApps, onDismiss = { picker = false }) {
            c = c.copy(blockedApps = it)
            picker = false
        }
    }
}
