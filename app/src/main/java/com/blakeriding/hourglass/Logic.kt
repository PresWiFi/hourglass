package com.blakeriding.hourglass

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

// Pure rules: no Android, no wall clock. Everything here is unit tested.

const val MINUTE_MS = 60_000L
const val HOUR_MS = 60 * MINUTE_MS

fun dayKey(d: LocalDate): String = d.toString()
fun dayKey(ms: Long, zone: ZoneId): String = dayKey(Instant.ofEpochMilli(ms).atZone(zone).toLocalDate())
fun startOfDay(d: LocalDate, zone: ZoneId): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()

// ---------- Downtime ----------

fun Downtime.windowFor(isoDay: Int): Window? = if (everyDay) window else days[isoDay]

private fun minuteOf(now: ZonedDateTime) = now.hour * 60 + now.minute

/** Is the schedule in Downtime right now (ignoring any override)? Handles windows that run past midnight. */
fun scheduledDowntime(d: Downtime, now: ZonedDateTime): Boolean {
    if (!d.enabled) return false
    val m = minuteOf(now)
    d.windowFor(now.dayOfWeek.value)?.let { w ->
        if (w.start < w.end && m >= w.start && m < w.end) return true
        if (w.start > w.end && m >= w.start) return true
    }
    // Yesterday's overnight window spilling into this morning.
    d.windowFor(now.minusDays(1).dayOfWeek.value)?.let { w ->
        if (w.start > w.end && m < w.end) return true
    }
    return false
}

fun downtimeActive(s: AppState, now: ZonedDateTime): Boolean {
    val o = s.downtimeOverride
    if (o != null && now.toInstant().toEpochMilli() < o.until) return o.on
    return scheduledDowntime(s.downtime, now)
}

/** Minutes until scheduled Downtime starts today, or null if it doesn't start later today. */
fun minutesUntilDowntime(s: AppState, now: ZonedDateTime): Int? {
    if (downtimeActive(s, now) || !s.downtime.enabled) return null
    val w = s.downtime.windowFor(now.dayOfWeek.value) ?: return null
    if (w.start == w.end) return null
    val m = minuteOf(now)
    return (w.start - m).takeIf { it > 0 }
}

/** When "Turn on Downtime until tomorrow" or "skip tonight" should end: the next time the schedule changes, or 6 AM. */
fun overrideEnd(s: AppState, now: ZonedDateTime): Long {
    val tomorrow = now.toLocalDate().plusDays(1)
    val w = s.downtime.windowFor(tomorrow.dayOfWeek.value)
    // Turning Downtime off by hand while it's scheduled: until the scheduled end.
    if (scheduledDowntime(s.downtime, now)) {
        val today = s.downtime.windowFor(now.dayOfWeek.value)
        val yest = s.downtime.windowFor(now.minusDays(1).dayOfWeek.value)
        val m = minuteOf(now)
        val endToday = when {
            yest != null && yest.start > yest.end && m < yest.end -> now.toLocalDate().atStartOfDay(now.zone).plusMinutes(yest.end.toLong())
            today != null && today.start < today.end -> now.toLocalDate().atStartOfDay(now.zone).plusMinutes(today.end.toLong())
            today != null -> tomorrow.atStartOfDay(now.zone).plusMinutes(today.end.toLong())
            else -> null
        }
        if (endToday != null) return endToday.toInstant().toEpochMilli()
    }
    val endMinute = w?.takeIf { it.start > it.end }?.end ?: (6 * 60)
    return tomorrow.atStartOfDay(now.zone).plusMinutes(endMinute.toLong()).toInstant().toEpochMilli()
}

// ---------- Limits ----------

fun AppLimit.minutesOn(isoDay: Int): Int = dayMinutes[isoDay] ?: minutes

class Usage(
    val apps: Map<String, Long>,
    val web: Map<String, Long>,
    val catOf: (String) -> Cat,
)

fun AppLimit.covers(pkg: String, catOf: (String) -> Cat) = pkg in apps || catOf(pkg) in categories

fun AppLimit.coversDomain(domain: String) = domains.any { domainMatches(domain, it) }

fun limitUsedMs(l: AppLimit, u: Usage): Long =
    u.apps.entries.sumOf { (pkg, ms) -> if (l.covers(pkg, u.catOf)) ms else 0L } +
        u.web.entries.sumOf { (d, ms) -> if (l.coversDomain(d)) ms else 0L }

fun limitLeftMs(l: AppLimit, u: Usage, now: ZonedDateTime): Long =
    l.minutesOn(now.dayOfWeek.value) * MINUTE_MS - limitUsedMs(l, u)

/** A short name for a limit: one app/category/site, or "Instagram, TikTok + 2". */
fun limitTitle(l: AppLimit, appName: (String) -> String): String {
    val names = l.categories.map { it.label } + l.apps.map(appName).sorted() + l.domains.sorted()
    return when {
        names.isEmpty() -> "Empty limit"
        names.size <= 2 -> names.joinToString(", ")
        else -> "${names.take(2).joinToString(", ")} + ${names.size - 2}"
    }
}

// ---------- Breaks, grants ----------

fun breaksUsedToday(s: AppState, now: ZonedDateTime): Int {
    val start = startOfDay(now.toLocalDate(), now.zone)
    return s.breaksTaken.count { it >= start }
}

fun breaksLeft(s: AppState, now: ZonedDateTime): Int = (s.breaks.perDay - breaksUsedToday(s, now)).coerceAtLeast(0)

/** End of the running emergency break, or null. */
fun breakUntil(s: AppState, nowMs: Long): Long? =
    s.breaksTaken.maxOrNull()?.plus(s.breaks.minutes * MINUTE_MS)?.takeIf { it > nowMs }

fun hasGrant(s: AppState, target: String, nowMs: Long) = s.grants.any { it.target == target && it.until > nowMs }

fun oneMoreKey(day: String, target: String) = "$day|$target"

fun AppState.prune(now: ZonedDateTime): AppState {
    val nowMs = now.toInstant().toEpochMilli()
    val yesterday = startOfDay(now.toLocalDate().minusDays(1), now.zone)
    val today = dayKey(now.toLocalDate())
    val oldest = dayKey(now.toLocalDate().minusDays(HISTORY_DAYS.toLong()))
    return copy(
        grants = grants.filter { it.until > nowMs },
        breaksTaken = breaksTaken.filter { it >= yesterday },
        oneMoreMinute = oneMoreMinute.filter { it.startsWith(today) },
        history = history.filterKeys { it >= oldest },
        notifCounts = notifCounts.filterKeys { it >= oldest },
        webTime = webTime.filterKeys { it >= oldest },
        downtimeOverride = downtimeOverride?.takeIf { it.until > nowMs },
    )
}

// ---------- Deciding what to block ----------

enum class BlockKind { DOWNTIME, LIMIT, APP_BLOCKED, INSTALLS, SITE_ADULT, SITE_BLOCKED, SITE_NOT_ALLOWED }

/**
 * Something on screen that Screen Time stops. [target] is a package, or "web:domain".
 * [strict] = needs the passcode; otherwise "Ignore" works without it.
 */
data class Block(
    val kind: BlockKind,
    val target: String,
    val strict: Boolean = true,
    val limitId: String? = null,
    val minutes: Int = 0,
)

fun webTarget(domain: String) = "web:$domain"

/** What, if anything, should stop [pkg] right now. Packages in [exempt] (launcher, dialer, Settings...) are never blocked. */
fun decideApp(s: AppState, pkg: String, now: ZonedDateTime, u: Usage, exempt: (String) -> Boolean): Block? {
    val nowMs = now.toInstant().toEpochMilli()
    if (!s.enabled || s.passcode == null) return null
    if (breakUntil(s, nowMs) != null || hasGrant(s, pkg, nowMs)) return null
    // Before the exemptions: the app installer is otherwise exempt, like Settings.
    if (s.content.blockInstalls && pkg in STORE_PACKAGES) return Block(BlockKind.INSTALLS, pkg)
    if (exempt(pkg) || pkg in s.alwaysAllowed) return null
    if (pkg in s.content.blockedApps) return Block(BlockKind.APP_BLOCKED, pkg)
    if (downtimeActive(s, now)) return Block(BlockKind.DOWNTIME, pkg, strict = s.downtime.blockAtDowntime)
    return s.limits
        .filter { it.enabled && it.covers(pkg, u.catOf) && limitLeftMs(it, u, now) <= 0 }
        .minByOrNull { if (it.blockAtEnd) 0 else 1 }
        ?.let { Block(BlockKind.LIMIT, pkg, strict = it.blockAtEnd, limitId = it.id, minutes = it.minutesOn(now.dayOfWeek.value)) }
}

/** What, if anything, should stop the website [domain] open in a browser. */
fun decideWeb(s: AppState, domain: String, now: ZonedDateTime, u: Usage): Block? {
    val nowMs = now.toInstant().toEpochMilli()
    if (!s.enabled || s.passcode == null) return null
    val target = webTarget(domain)
    if (breakUntil(s, nowMs) != null || hasGrant(s, target, nowMs)) return null
    val c = s.content
    val allowed = c.allowedSites.any { domainMatches(domain, it) }
    when (c.webMode) {
        WebMode.ALLOWED_ONLY -> if (!allowed) return Block(BlockKind.SITE_NOT_ALLOWED, target)
        WebMode.LIMIT_ADULT -> if (!allowed && isAdultDomain(domain)) return Block(BlockKind.SITE_ADULT, target)
        WebMode.UNRESTRICTED -> {}
    }
    if (c.webMode != WebMode.ALLOWED_ONLY && c.blockedSites.any { domainMatches(domain, it) }) {
        return Block(BlockKind.SITE_BLOCKED, target)
    }
    return s.limits
        .filter { it.enabled && it.coversDomain(domain) && limitLeftMs(it, u, now) <= 0 }
        .minByOrNull { if (it.blockAtEnd) 0 else 1 }
        ?.let { Block(BlockKind.LIMIT, target, strict = it.blockAtEnd, limitId = it.id, minutes = it.minutesOn(now.dayOfWeek.value)) }
}

/** App stores and the system installer (installing and uninstalling apps). */
val STORE_PACKAGES = setOf(
    "com.android.vending", "com.sec.android.app.samsungapps", "com.heytap.market", "com.oppo.market",
    "com.android.packageinstaller", "com.google.android.packageinstaller",
)

// ---------- Passcode lockout ----------

/** Like iOS: a few free tries, then growing waits. */
fun lockoutMs(failures: Int): Long = when {
    failures < 5 -> 0L
    failures == 5 -> MINUTE_MS
    failures == 6 -> 5 * MINUTE_MS
    failures == 7 -> 15 * MINUTE_MS
    else -> HOUR_MS
}

fun validPasscode(code: String) = code.length in 4..8 && code.all { it.isDigit() }

// ---------- Websites ----------

/** "https://m.Example.com/path" -> "m.example.com"; null for text that isn't an address yet. */
fun domainOf(text: String): String? {
    var t = text.trim().lowercase()
    if (t.isEmpty() || ' ' in t) return null
    t = t.substringAfter("://")
    t = t.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
    t = t.removePrefix("www.")
    if ('.' !in t || t.startsWith('.') || t.endsWith('.')) return null
    if (!t.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
    return t
}

/** example.com matches example.com and any subdomain of it. */
fun domainMatches(domain: String, rule: String): Boolean {
    val r = domainOf(rule) ?: return false
    return domain == r || domain.endsWith(".$r")
}

private val ADULT_DOMAINS = listOf(
    "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com", "youporn.com", "onlyfans.com",
    "chaturbate.com", "stripchat.com", "spankbang.com", "eporner.com", "tube8.com", "brazzers.com",
    "fansly.com", "livejasmin.com", "bongacams.com", "cam4.com", "motherless.com", "rule34.xxx", "e-hentai.org",
    "nhentai.net", "hanime.tv", "manyvids.com", "camsoda.com", "porn.com", "beeg.com", "txxx.com", "hqporner.com",
)
private val ADULT_WORDS = listOf("porn", "xxx", "hentai", "nsfw", "camgirl", "xvideo", "nude")

/** A best-effort adult filter: a list of big sites plus words in the address. Use with Private DNS for more. */
fun isAdultDomain(domain: String): Boolean {
    if (ADULT_DOMAINS.any { domainMatches(domain, it) }) return true
    if (domain.endsWith(".xxx") || domain.endsWith(".porn") || domain.endsWith(".adult") || domain.endsWith(".sex")) return true
    val tokens = domain.split('.', '-')
    return tokens.any { tok -> ADULT_WORDS.any { tok.contains(it) } || tok == "sex" || tok.startsWith("sexy") }
}

// ---------- Usage from Android's event log ----------

object Ev {
    const val RESUMED = 1
    const val PAUSED = 2
    const val SCREEN_ON = 15
    const val SCREEN_OFF = 16
    const val STOPPED = 23
}

/**
 * Builds a day's usage by replaying Android usage events in order. Feed it events (it ignores anything
 * before [dayStart] except to know what was open at midnight), then [snapshot] at any time.
 */
class DayFold(
    val dayStart: Long,
    private val ignore: (String) -> Boolean,
    private val catOf: (String) -> Cat,
) {
    private val apps = HashMap<String, Long>()
    private val hours = Array(24) { HashMap<Cat, Long>() }
    private val firstAfter = HashMap<String, Int>()
    private var pickups = 0
    private var firstPickupAt = 0L
    private var counted: String? = null
    private var since = 0L
    private var awaitingFirst = false

    /** The app on screen (including launchers), or null when the screen is off. */
    var top: String? = null
        private set
    var lastTime = 0L
        private set

    fun feed(time: Long, type: Int, pkg: String) {
        if (time < lastTime) return
        lastTime = time
        when (type) {
            Ev.RESUMED -> {
                top = pkg
                if (ignore(pkg)) {
                    close(time)
                } else if (counted != pkg) {
                    close(time)
                    counted = pkg
                    since = time
                    if (awaitingFirst && time >= dayStart) firstAfter.merge(pkg, 1, Int::plus)
                    awaitingFirst = false
                }
            }
            Ev.PAUSED, Ev.STOPPED -> {
                if (pkg == counted) close(time)
                if (pkg == top && type == Ev.PAUSED) top = null
            }
            Ev.SCREEN_OFF -> {
                close(time)
                top = null
            }
            Ev.SCREEN_ON -> if (time >= dayStart) {
                pickups++
                if (firstPickupAt == 0L) firstPickupAt = time
                awaitingFirst = true
            }
        }
    }

    private fun close(time: Long) {
        val pkg = counted ?: return
        counted = null
        add(apps, hours, pkg, since, time)
    }

    private fun add(apps: HashMap<String, Long>, hours: Array<HashMap<Cat, Long>>, pkg: String, from: Long, to: Long) {
        var a = maxOf(from, dayStart)
        val end = minOf(to, dayStart + 24 * HOUR_MS)
        if (end <= a) return
        apps.merge(pkg, end - a, Long::plus)
        val cat = catOf(pkg)
        while (a < end) {
            val h = ((a - dayStart) / HOUR_MS).toInt().coerceIn(0, 23)
            val hourEnd = minOf(end, dayStart + (h + 1) * HOUR_MS).let { if (h == 23) end else it }
            hours[h].merge(cat, hourEnd - a, Long::plus)
            a = hourEnd
        }
    }

    /** Usage so far, counting the app still open up to [now]. */
    fun snapshot(now: Long): DaySummary {
        val a = HashMap(apps)
        val h = Array(24) { HashMap(hours[it]) }
        counted?.let { add(a, h, it, since, now) }
        return DaySummary(
            totalMs = a.values.sum(),
            apps = a,
            hours = h.map { it.toMap() },
            pickups = pickups,
            firstPickupAt = firstPickupAt,
            firstAfterPickup = HashMap(firstAfter),
        )
    }
}

// ---------- Reports ----------

fun weekTotal(history: Map<String, DaySummary>, lastDay: LocalDate): Long =
    (0L until 7L).sumOf { history[dayKey(lastDay.minusDays(it))]?.totalMs ?: 0L }

/** Percent change from [before] to [after], or null when there's nothing to compare. */
fun percentChange(before: Long, after: Long): Int? =
    if (before <= 0) null else (((after - before) * 100.0) / before).let { Math.round(it).toInt() }

fun formatDuration(ms: Long): String {
    val totalMin = ms / MINUTE_MS
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        ms in 1 until MINUTE_MS -> "<1m"
        else -> "${m}m"
    }
}

fun formatMinuteOfDay(m: Int): String {
    val h = (m / 60) % 24
    val mm = m % 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "%d:%02d %s".format(h12, mm, if (h < 12) "AM" else "PM")
}
