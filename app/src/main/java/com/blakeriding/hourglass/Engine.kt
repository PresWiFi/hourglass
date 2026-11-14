package com.blakeriding.hourglass

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The enforcement loop. Once a second while the screen is on it replays new usage events to learn
 * what's on screen and how long each app has been used today, then shows the block screen when
 * Downtime, a limit or a content rule says so.
 */
@android.annotation.SuppressLint("StaticFieldLeak") // Holds the application context only.
object Engine {
    private lateinit var app: Context
    private var handler: Handler? = null
    private var fold: DayFold? = null
    private var foldDay: LocalDate? = null

    private val _today = MutableStateFlow(DaySummary())
    /** Today's usage so far, refreshed every tick. */
    val today: StateFlow<DaySummary> = _today.asStateFlow()

    private val _web = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Today's time per website. */
    val webToday: StateFlow<Map<String, Long>> = _web.asStateFlow()

    /** The app Android's usage events say is on screen, as of the last tick. */
    @Volatile var top: String? = null
        private set

    /** Browser package and the site it shows, reported by [GuardService]. */
    @Volatile private var browser: Pair<String, String>? = null
    private var web = HashMap<String, Long>()
    private var webDay = ""
    private var lastTick = 0L
    private var lastWebFlush = 0L
    private val warned = HashSet<String>()
    private var lastShownAt = 0L
    private var lastShownTarget = ""

    @Synchronized
    fun start(context: Context) {
        if (handler != null) return
        app = context.applicationContext
        val t = HandlerThread("hourglass-engine").apply { start() }
        handler = Handler(t.looper).also { it.post(loop) }
    }

    /** Re-check now (something changed on screen). */
    fun poke() {
        handler?.post { safeTick() }
    }

    fun setBrowserSite(pkg: String, domain: String?) {
        val next = domain?.let { pkg to it }
        if (next != browser) {
            browser = next
            poke()
        }
    }

    private val loop = object : Runnable {
        override fun run() {
            safeTick()
            val on = app.getSystemService(PowerManager::class.java).isInteractive
            handler?.postDelayed(this, if (on) 1000L else 10_000L)
        }
    }

    private val lock = Any()

    private fun safeTick() = synchronized(lock) {
        try {
            tick()
        } catch (e: Exception) {
            Log.e("Hourglass", "tick failed", e)
        }
    }

    /**
     * The app on screen right now, read fresh from the usage events. [top] can be up to a second old,
     * which is long enough to mistake a just-opened app for the home screen.
     */
    fun freshTop(): String? = synchronized(lock) {
        val f = fold ?: return top
        try {
            if (hasUsageAccess(app)) feed(f, maxOf(f.lastTime + 1, f.dayStart - 4 * HOUR_MS), System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e("Hourglass", "freshTop failed", e)
        }
        top = f.top
        f.top
    }

    private fun catOf(pkg: String) = Apps.category(app, pkg)

    /** Not counted as screen time: the home screen and system UI, plus the user's Not Counted apps. */
    private fun ignored(pkg: String) = Apps.isSystemSurface(app, pkg) || pkg in Store.state.value.notCounted

    private fun tick() {
        if (!hasUsageAccess(app)) return
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val nowMs = now.toInstant().toEpochMilli()
        val f = foldFor(now)
        feed(f, maxOf(f.lastTime + 1, f.dayStart - 4 * HOUR_MS), nowMs)
        val interactive = app.getSystemService(PowerManager::class.java).isInteractive
        val top = f.top
        this.top = top
        trackWeb(top, now, interactive)
        val snap = f.snapshot(nowMs)
        _today.value = snap

        val s = Store.state.value
        if (!s.enabled || s.passcode == null) return
        notifyUpcoming(s, now)
        if (!interactive || top == null) return
        val u = Usage(snap.apps, web, ::catOf)
        val block = decideApp(s, top, now, u) { Apps.isExempt(app, it) }
            ?: browser?.takeIf { it.first == top }?.let { decideWeb(s, it.second, now, u) }
        if (block == null) {
            warnLimits(s, top, now, u)
            return
        }
        // Usage events can name an app that isn't really on screen: e.g. the Google app resumes an invisible
        // overlay when the app switcher opens. Only block when the app's own window is the active one.
        val active = GuardService.instance?.activeAppPackage()
        if (active != null && active != top) return
        show(block)
    }

    /** Today's fold; on a new day, files yesterday into history and fills any gaps from the event log. */
    private fun foldFor(now: ZonedDateTime): DayFold {
        val day = now.toLocalDate()
        val current = fold
        if (current != null && foldDay == day) return current
        val zone = now.zone
        if (current != null && foldDay != null) {
            val prevKey = dayKey(foldDay!!)
            val snap = current.snapshot(startOfDay(day, zone))
            Store.update { it.copy(history = it.history + (prevKey to snap)) }
        }
        val f = DayFold(startOfDay(day, zone), ::ignored, ::catOf)
        fold = f
        foldDay = day
        warned.clear()
        backfill(day, zone)
        Store.update { it.prune(now) }
        return f
    }

    private fun backfill(today: LocalDate, zone: ZoneId) {
        val have = Store.state.value.history
        val found = HashMap<String, DaySummary>()
        for (i in 1L..7L) {
            val d = today.minusDays(i)
            val key = dayKey(d)
            if (key in have) continue
            val start = startOfDay(d, zone)
            val end = startOfDay(d.plusDays(1), zone)
            val f = DayFold(start, ::ignored, ::catOf)
            feed(f, start - 4 * HOUR_MS, end)
            val snap = f.snapshot(end)
            if (snap.totalMs > 0 || snap.pickups > 0) found[key] = snap
        }
        if (found.isNotEmpty()) Store.update { it.copy(history = it.history + found) }
    }

    private fun feed(f: DayFold, from: Long, to: Long) {
        if (to <= from) return
        val usm = app.getSystemService(UsageStatsManager::class.java)
        val events = usm.queryEvents(from, to) ?: return
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            f.feed(e.timeStamp, e.eventType, e.packageName ?: continue)
        }
    }

    private fun trackWeb(top: String?, now: ZonedDateTime, interactive: Boolean) {
        val nowMs = now.toInstant().toEpochMilli()
        val key = dayKey(now.toLocalDate())
        if (key != webDay) {
            flushWeb()
            webDay = key
            web = HashMap(Store.state.value.webTime[key].orEmpty())
        }
        val b = browser
        if (interactive && lastTick > 0 && b != null && b.first == top) {
            web.merge(b.second, (nowMs - lastTick).coerceIn(0L, 5000L), Long::plus)
            _web.value = HashMap(web)
        }
        lastTick = nowMs
        if (nowMs - lastWebFlush > 30_000L) flushWeb()
    }

    private fun flushWeb() {
        lastWebFlush = System.currentTimeMillis()
        if (webDay.isEmpty() || web.isEmpty()) return
        val copy = HashMap(web)
        Store.update { it.copy(webTime = it.webTime + (webDay to copy)) }
    }

    private fun show(block: Block) {
        val t = SystemClock.elapsedRealtime()
        // Usage events lag the screen slightly; don't stack block screens.
        if (block.target == lastShownTarget && t - lastShownAt < 2000) return
        lastShownAt = t
        lastShownTarget = block.target
        BlockActivity.pending = block
        val i = Intent(app, BlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        // An accessibility service may start activities from the background; fall back to the overlay permission.
        (GuardService.instance ?: app).startActivity(i)
    }

    private fun warnLimits(s: AppState, top: String, now: ZonedDateTime, u: Usage) {
        val day = dayKey(now.toLocalDate())
        val nowMs = now.toInstant().toEpochMilli()
        if (breakUntil(s, nowMs) != null || hasGrant(s, top, nowMs) || top in s.alwaysAllowed) return
        for (l in s.limits) {
            if (!l.enabled || !l.covers(top, ::catOf)) continue
            val left = limitLeftMs(l, u, now)
            if (left in 1..5 * MINUTE_MS && warned.add("$day|${l.id}")) {
                Notifs.limitSoon(app, limitTitle(l) { Apps.label(app, it) }, left)
            }
        }
    }

    private fun notifyUpcoming(s: AppState, now: ZonedDateTime) {
        val day = dayKey(now.toLocalDate())
        val nowMs = now.toInstant().toEpochMilli()
        minutesUntilDowntime(s, now)?.let { m ->
            if (m in 1..5 && warned.add("$day|downtime")) Notifs.downtimeSoon(app, m)
        }
        breakUntil(s, nowMs)?.let { end ->
            if (end - nowMs <= MINUTE_MS && warned.add("break|$end")) Notifs.breakEnding(app)
        }
    }

    /** Usage for one day: live for today, otherwise from history. */
    fun summaryFor(day: LocalDate): DaySummary? =
        if (day == foldDay || day == LocalDate.now()) today.value else Store.state.value.history[dayKey(day)]

    fun webFor(day: LocalDate): Map<String, Long> =
        if (day == LocalDate.now()) webToday.value else Store.state.value.webTime[dayKey(day)].orEmpty()

    /** Forget the cached fold so category changes recount. */
    fun recount() {
        handler?.post {
            synchronized(lock) {
                fold = null
                foldDay = null
            }
            safeTick()
        }
    }

    /**
     * Recount today and the past week from Android's usage log (it keeps about a week), e.g. after the
     * Not Counted list changes. Older days stay as they were recorded.
     */
    fun recountWeek() {
        handler?.post {
            synchronized(lock) {
                val today = LocalDate.now()
                val keys = (1L..7L).map { dayKey(today.minusDays(it)) }.toSet()
                Store.update { it.copy(history = it.history - keys) }
                fold = null
                foldDay = null
            }
            safeTick()
        }
    }
}
