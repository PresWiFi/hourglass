package com.blakeriding.hourglass.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blakeriding.hourglass.Apps
import com.blakeriding.hourglass.Cat
import com.blakeriding.hourglass.DaySummary
import com.blakeriding.hourglass.Engine
import com.blakeriding.hourglass.Gate
import com.blakeriding.hourglass.Store
import com.blakeriding.hourglass.covers
import com.blakeriding.hourglass.coversDomain
import com.blakeriding.hourglass.dayKey
import com.blakeriding.hourglass.formatClock
import com.blakeriding.hourglass.formatDuration
import com.blakeriding.hourglass.hasUsageAccess
import com.blakeriding.hourglass.percentChange
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** One thing on the Most Used list: an app, a website or a category. */
private data class Item(val key: String, val name: String, val ms: Long, val kind: Int, val cat: Cat? = null)

private const val APP = 0
private const val SITE = 1
private const val CATEGORY = 2

@Composable
fun UsageScreen(gate: Gate, onAddLimit: (String) -> Unit) {
    val context = LocalContext.current
    val s by Store.state.collectAsState()
    val todaySummary by Engine.today.collectAsState()
    val webToday by Engine.webToday.collectAsState()
    var week by remember { mutableStateOf(false) }
    var dayOffset by remember { mutableIntStateOf(0) }
    var byCategory by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<String?>(null) }

    val today = LocalDate.now()
    val locale = LocalConfiguration.current.locales[0]
    fun summary(d: LocalDate): DaySummary? = if (d == today) todaySummary else s.history[dayKey(d)]
    fun webOf(d: LocalDate): Map<String, Long> = if (d == today) webToday else s.webTime[dayKey(d)].orEmpty()
    fun notifsOf(d: LocalDate): Map<String, Int> = s.notifCounts[dayKey(d)].orEmpty()

    val days: List<LocalDate> = if (week) (6 downTo 0).map { today.minusDays(it.toLong()) } else listOf(today.minusDays(dayOffset.toLong()))
    val summaries = days.map { summary(it) ?: DaySummary() }
    val apps = HashMap<String, Long>().also { m -> summaries.forEach { d -> d.apps.forEach { (k, v) -> m.merge(k, v, Long::plus) } } }
    val sites = HashMap<String, Long>().also { m -> days.forEach { d -> webOf(d).forEach { (k, v) -> m.merge(k, v, Long::plus) } } }
    val notifs = HashMap<String, Int>().also { m -> days.forEach { d -> notifsOf(d).forEach { (k, v) -> m.merge(k, v, Int::plus) } } }
    val firsts = HashMap<String, Int>().also { m -> summaries.forEach { d -> d.firstAfterPickup.forEach { (k, v) -> m.merge(k, v, Int::plus) } } }
    val total = summaries.sumOf { it.totalMs }
    val pickups = summaries.sumOf { it.pickups }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text("Screen Time", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        if (!hasUsageAccess(context)) {
            Footnote("Usage access is off, so there's nothing to show. Turn it on in Settings › Protection.")
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            SegmentedButton(selected = week, onClick = { week = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Week") }
            SegmentedButton(selected = !week, onClick = { week = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Day") }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(16.dp)) {
                if (week) {
                    val avg = total / 7
                    val prev = (7..13).sumOf { (summary(today.minusDays(it.toLong())) ?: DaySummary()).totalMs }
                    Text("Daily Average", color = Muted, fontSize = 13.sp)
                    Text(formatDuration(avg), fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                    percentChange(prev, total)?.let { pc ->
                        Text(
                            if (pc <= 0) "${-pc}% less than last week" else "$pc% more than last week",
                            color = if (pc <= 0) MaterialThemeGreen else Danger, fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    WeekChart(days, summaries)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(dayLabel(days[0], today, locale), color = Muted, fontSize = 13.sp)
                            Text(formatDuration(total), fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(onClick = { if (dayOffset < 6) dayOffset++ }, enabled = dayOffset < 6) { Text("‹", fontSize = 22.sp) }
                        TextButton(onClick = { if (dayOffset > 0) dayOffset-- }, enabled = dayOffset > 0) { Text("›", fontSize = 22.sp) }
                    }
                    Spacer(Modifier.height(12.dp))
                    HourChart(summaries[0])
                }
                Spacer(Modifier.height(12.dp))
                CategoryLegend(summaries)
            }
        }

        // ---------- Most used ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Most Used")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { byCategory = !byCategory }, modifier = Modifier.padding(top = 12.dp, end = 8.dp)) {
                Text(if (byCategory) "Show Apps & Websites" else "Show Categories", fontSize = 13.sp)
            }
        }
        val items: List<Item> = if (byCategory) {
            val m = HashMap<Cat, Long>()
            apps.forEach { (pkg, ms) -> m.merge(Apps.category(context, pkg), ms, Long::plus) }
            m.map { (c, ms) -> Item(c.name, c.label, ms, CATEGORY, c) }
        } else {
            apps.map { (pkg, ms) -> Item(pkg, Apps.label(context, pkg), ms, APP) } +
                sites.map { (d, ms) -> Item(d, d, ms, SITE) }
        }.filter { it.ms >= 1000 }.sortedByDescending { it.ms }
        Card(Modifier.padding(horizontal = 16.dp)) {
            if (items.isEmpty()) ListRow("No usage yet")
            val max = items.firstOrNull()?.ms ?: 1
            items.take(30).forEachIndexed { i, it ->
                if (i > 0) Divider()
                val limited = when (it.kind) {
                    APP -> s.limits.any { l -> l.enabled && l.covers(it.key) { p -> Apps.category(context, p) } }
                    SITE -> s.limits.any { l -> l.enabled && l.coversDomain(it.key) }
                    else -> s.limits.any { l -> l.enabled && it.cat in l.categories }
                }
                UsageRow(it, max, limited, onClick = if (it.kind == APP) ({ detail = it.key }) else null)
            }
        }

        // ---------- Pickups ----------
        SectionLabel("Pickups")
        Card(Modifier.padding(horizontal = 16.dp)) {
            ListRow(
                if (week) "Daily Average" else "Total Pickups",
                value = if (week) "${pickups / 7}" else "$pickups",
            )
            val first = summaries.firstOrNull()?.firstPickupAt ?: 0
            if (!week && first > 0) {
                Divider()
                ListRow("First Pickup", value = formatClock(first))
            }
            if (firsts.isNotEmpty()) {
                Divider()
                Text("FIRST USED AFTER PICKUP", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
                firsts.entries.sortedByDescending { it.value }.take(8).forEach { (pkg, n) ->
                    ListRow(Apps.label(context, pkg), value = "$n", leading = { AppIcon(pkg, 28) })
                }
            }
        }

        // ---------- Notifications ----------
        SectionLabel("Notifications")
        Card(Modifier.padding(horizontal = 16.dp)) {
            val n = notifs.values.sum()
            ListRow(if (week) "Daily Average" else "Total", value = if (week) "${n / 7}" else "$n")
            notifs.entries.sortedByDescending { it.value }.take(12).forEach { (pkg, c) ->
                Divider()
                ListRow(Apps.label(context, pkg), value = "$c", leading = { AppIcon(pkg, 28) })
            }
        }
        if (notifs.isEmpty()) Footnote("Turn on notification access in Settings to count notifications.")
    }

    detail?.let { pkg ->
        AppDetailDialog(pkg, apps[pkg] ?: 0, week, gate, onDismiss = { detail = null }, onAddLimit = {
            detail = null
            onAddLimit(pkg)
        })
    }
}

private val MaterialThemeGreen = Color(0xFF34C759)

private fun dayLabel(d: LocalDate, today: LocalDate, locale: Locale) = when (d) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, locale) + ", " +
        d.month.getDisplayName(TextStyle.SHORT, locale) + " " + d.dayOfMonth
}

@Composable
private fun UsageRow(it: Item, max: Long, limited: Boolean, onClick: (() -> Unit)?) {
    ListRow(
        title = it.name + if (limited) "  ⌛" else "",
        leading = {
            when (it.kind) {
                APP -> AppIcon(it.key)
                SITE -> SiteIcon(it.key)
                else -> Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(catColor(it.cat!!)))
            }
        },
        subtitle = null,
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                Text(formatDuration(it.ms), color = Muted)
                Box(
                    Modifier.width((80 * it.ms / max.coerceAtLeast(1)).toInt().coerceAtLeast(2).dp).height(4.dp)
                        .clip(RoundedCornerShape(2.dp)).background(it.cat?.let(::catColor) ?: Accent),
                )
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun HourChart(d: DaySummary) {
    val max = (d.hours.maxOfOrNull { it.values.sum() } ?: 0L).coerceAtLeast(60_000L)
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val slot = size.width / 24f
        val bar = slot * 0.6f
        for (h in 0 until 24) {
            var y = size.height
            Cat.entries.forEach { c ->
                val ms = d.hours.getOrNull(h)?.get(c) ?: 0L
                if (ms <= 0) return@forEach
                val hgt = size.height * ms / max
                drawRoundRect(catColor(c), Offset(h * slot + (slot - bar) / 2, y - hgt), Size(bar, hgt), CornerRadius(2f))
                y -= hgt
            }
            if (h % 6 == 0) drawLine(Color(0x33FFFFFF), Offset(h * slot, 0f), Offset(h * slot, size.height))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        listOf("12a", "6a", "12p", "6p").forEach { Text(it, color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f)) }
    }
}

@Composable
private fun WeekChart(days: List<LocalDate>, sums: List<DaySummary>) {
    val max = (sums.maxOfOrNull { it.totalMs } ?: 0L).coerceAtLeast(60_000L)
    val avg = sums.sumOf { it.totalMs } / 7f
    val locale = LocalConfiguration.current.locales[0]
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        val slot = size.width / 7f
        val bar = slot * 0.55f
        sums.forEachIndexed { i, d ->
            var y = size.height
            Cat.entries.forEach { c ->
                val ms = d.hours.sumOf { it[c] ?: 0L }
                if (ms <= 0) return@forEach
                val hgt = size.height * ms / max
                drawRoundRect(catColor(c), Offset(i * slot + (slot - bar) / 2, y - hgt), Size(bar, hgt), CornerRadius(3f))
                y -= hgt
            }
        }
        val ay = size.height - size.height * avg / max
        drawLine(MaterialThemeGreen, Offset(0f, ay), Offset(size.width, ay), strokeWidth = 2f)
    }
    Row(Modifier.fillMaxWidth()) {
        days.forEach {
            Text(
                it.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun CategoryLegend(sums: List<DaySummary>) {
    val m = HashMap<Cat, Long>()
    sums.forEach { d -> d.hours.forEach { h -> h.forEach { (c, ms) -> m.merge(c, ms, Long::plus) } } }
    val top = m.entries.sortedByDescending { it.value }.take(3)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        top.forEach { (c, ms) ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(catColor(c)))
                    Spacer(Modifier.width(4.dp))
                    Text(c.label, color = Muted, fontSize = 11.sp, maxLines = 1)
                }
                Text(formatDuration(ms), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun AppDetailDialog(pkg: String, ms: Long, week: Boolean, gate: Gate, onDismiss: () -> Unit, onAddLimit: () -> Unit) {
    val context = LocalContext.current
    var cat by remember { mutableStateOf(Apps.category(context, pkg)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Apps.label(context, pkg)) },
        text = {
            Column {
                Text("${formatDuration(ms)} ${if (week) "this week" else "on this day"}", color = Muted)
                Spacer(Modifier.height(12.dp))
                Text("Category", fontSize = 13.sp, color = Muted)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Cat.entries.forEach { c ->
                        FilterChip(selected = cat == c, onClick = {
                            // Moving an app out of a limited category would get around the limit, so it needs the passcode.
                            gate {
                                cat = c
                                Store.update { it.copy(categoryOverrides = it.categoryOverrides + (pkg to c)) }
                                Apps.invalidateCategories()
                                Engine.recount()
                            }
                        }, label = { Text(c.label, fontSize = 12.sp) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onAddLimit) { Text("Add Limit") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
