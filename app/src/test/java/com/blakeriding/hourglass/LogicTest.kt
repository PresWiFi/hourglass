package com.blakeriding.hourglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class LogicTest {
    private val zone = ZoneId.of("America/New_York")
    private val secret = Secret("s", "h", 1)

    // 2026-11-16 is a Monday.
    private fun at(day: Int, h: Int, m: Int = 0): ZonedDateTime = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone)
    private fun ms(z: ZonedDateTime) = z.toInstant().toEpochMilli()

    private val cats = mapOf("com.insta" to Cat.SOCIAL, "com.game" to Cat.GAMES)
    private val catOf: (String) -> Cat = { cats[it] ?: Cat.OTHER }
    private fun usage(apps: Map<String, Long> = emptyMap(), web: Map<String, Long> = emptyMap()) = Usage(apps, web, catOf)
    private val noExempt: (String) -> Boolean = { it == "launcher" }
    private val base = AppState(passcode = secret)

    // ---------- Downtime ----------

    @Test fun overnightDowntimeCoversLateEveningAndEarlyMorning() {
        val d = Downtime(enabled = true, window = Window(22 * 60, 7 * 60))
        assertTrue(scheduledDowntime(d, at(16, 23)))
        assertTrue(scheduledDowntime(d, at(17, 6, 59)))
        assertFalse(scheduledDowntime(d, at(17, 7)))
        assertFalse(scheduledDowntime(d, at(17, 21, 59)))
    }

    @Test fun sameDayWindow() {
        val d = Downtime(enabled = true, window = Window(13 * 60, 14 * 60))
        assertTrue(scheduledDowntime(d, at(16, 13, 30)))
        assertFalse(scheduledDowntime(d, at(16, 14)))
    }

    @Test fun customDaysUseYesterdaysOvernightWindow() {
        // Only Friday night (5) has Downtime, 23:00-08:00.
        val d = Downtime(enabled = true, everyDay = false, days = mapOf(5 to Window(23 * 60, 8 * 60)))
        assertTrue(scheduledDowntime(d, at(20, 23, 30))) // Friday
        assertTrue(scheduledDowntime(d, at(21, 7))) // Saturday morning
        assertFalse(scheduledDowntime(d, at(21, 23, 30))) // Saturday night
        assertFalse(scheduledDowntime(d, at(16, 2))) // Monday morning
    }

    @Test fun disabledDowntimeNeverActive() {
        assertFalse(scheduledDowntime(Downtime(enabled = false), at(16, 23)))
    }

    @Test fun overrideBeatsSchedule() {
        val s = base.copy(downtime = Downtime(enabled = true), downtimeOverride = DowntimeOverride(false, ms(at(17, 7))))
        assertFalse(downtimeActive(s, at(16, 23)))
        val on = base.copy(downtimeOverride = DowntimeOverride(true, ms(at(17, 6))))
        assertTrue(downtimeActive(on, at(16, 15)))
        assertFalse(downtimeActive(on, at(17, 6, 1)))
    }

    @Test fun overrideEndIsScheduledEndWhenTurningOffDuringDowntime() {
        val s = base.copy(downtime = Downtime(enabled = true, window = Window(22 * 60, 7 * 60)))
        assertEquals(ms(at(17, 7)), overrideEnd(s, at(16, 23)))
        assertEquals(ms(at(17, 7)), overrideEnd(s, at(17, 3)))
    }

    @Test fun downtimeWarningCountsDown() {
        val s = base.copy(downtime = Downtime(enabled = true, window = Window(22 * 60, 7 * 60)))
        assertEquals(5, minutesUntilDowntime(s, at(16, 21, 55)))
        assertNull(minutesUntilDowntime(s, at(16, 22, 30)))
    }

    // ---------- Apps ----------

    @Test fun nothingBlockedWithoutPasscodeOrWhenOff() {
        val s = AppState(content = ContentRules(blockedApps = setOf("com.game")))
        assertNull(decideApp(s, "com.game", at(16, 12), usage(), noExempt))
        assertNull(decideApp(s.copy(passcode = secret, enabled = false), "com.game", at(16, 12), usage(), noExempt))
        assertEquals(BlockKind.APP_BLOCKED, decideApp(s.copy(passcode = secret), "com.game", at(16, 12), usage(), noExempt)?.kind)
    }

    @Test fun downtimeBlocksExceptAlwaysAllowedAndExempt() {
        val s = base.copy(downtime = Downtime(enabled = true), alwaysAllowed = setOf("com.sms"))
        val night = at(16, 23)
        assertEquals(BlockKind.DOWNTIME, decideApp(s, "com.insta", night, usage(), noExempt)?.kind)
        assertNull(decideApp(s, "com.sms", night, usage(), noExempt))
        assertNull(decideApp(s, "launcher", night, usage(), noExempt))
    }

    @Test fun downtimeReminderIsNotStrict() {
        val s = base.copy(downtime = Downtime(enabled = true, blockAtDowntime = false))
        assertFalse(decideApp(s, "com.insta", at(16, 23), usage(), noExempt)!!.strict)
    }

    @Test fun appLimitBlocksOnceReached() {
        val l = AppLimit("a", apps = setOf("com.insta"), minutes = 30)
        val s = base.copy(limits = listOf(l))
        assertNull(decideApp(s, "com.insta", at(16, 12), usage(mapOf("com.insta" to 29 * MINUTE_MS)), noExempt))
        val b = decideApp(s, "com.insta", at(16, 12), usage(mapOf("com.insta" to 30 * MINUTE_MS)), noExempt)
        assertEquals(BlockKind.LIMIT, b?.kind)
        assertEquals("a", b?.limitId)
        assertTrue(b!!.strict)
    }

    @Test fun categoryLimitSumsEveryAppInIt() {
        val l = AppLimit("s", categories = setOf(Cat.SOCIAL), minutes = 60)
        val s = base.copy(limits = listOf(l))
        val u = Usage(mapOf("com.insta" to 40 * MINUTE_MS, "com.tiktok" to 25 * MINUTE_MS), emptyMap()) {
            if (it == "com.tiktok" || it == "com.insta") Cat.SOCIAL else Cat.OTHER
        }
        assertEquals(BlockKind.LIMIT, decideApp(s, "com.tiktok", at(16, 12), u, noExempt)?.kind)
    }

    @Test fun limitIncludesWebsiteTime() {
        val l = AppLimit("y", apps = setOf("com.yt"), domains = setOf("youtube.com"), minutes = 60)
        val u = usage(mapOf("com.yt" to 30 * MINUTE_MS), mapOf("m.youtube.com" to 30 * MINUTE_MS))
        assertEquals(0L, limitLeftMs(l, u, at(16, 12)))
        assertEquals(BlockKind.LIMIT, decideWeb(base.copy(limits = listOf(l)), "m.youtube.com", at(16, 12), u)?.kind)
    }

    @Test fun customDayMinutes() {
        val l = AppLimit("a", apps = setOf("com.game"), minutes = 30, dayMinutes = mapOf(6 to 120))
        val s = base.copy(limits = listOf(l))
        val u = usage(mapOf("com.game" to 60 * MINUTE_MS))
        assertEquals(BlockKind.LIMIT, decideApp(s, "com.game", at(20, 12), u, noExempt)?.kind) // Friday: 30
        assertNull(decideApp(s, "com.game", at(21, 12), u, noExempt)) // Saturday: 120
    }

    @Test fun disabledLimitIgnored() {
        val s = base.copy(limits = listOf(AppLimit("a", apps = setOf("com.game"), minutes = 1, enabled = false)))
        assertNull(decideApp(s, "com.game", at(16, 12), usage(mapOf("com.game" to HOUR_MS)), noExempt))
    }

    @Test fun grantAndBreakLetEverythingThrough() {
        val now = at(16, 23)
        val s = base.copy(downtime = Downtime(enabled = true), content = ContentRules(blockedApps = setOf("com.game")))
        val granted = s.copy(grants = listOf(Grant("com.insta", ms(now) + MINUTE_MS)))
        assertNull(decideApp(granted, "com.insta", now, usage(), noExempt))
        assertEquals(BlockKind.DOWNTIME, decideApp(granted, "com.other", now, usage(), noExempt)?.kind)
        val expired = s.copy(grants = listOf(Grant("com.insta", ms(now) - 1)))
        assertEquals(BlockKind.DOWNTIME, decideApp(expired, "com.insta", now, usage(), noExempt)?.kind)
        val onBreak = s.copy(breaksTaken = listOf(ms(now) - 2 * MINUTE_MS))
        assertNull(decideApp(onBreak, "com.game", now, usage(), noExempt))
        val breakOver = s.copy(breaksTaken = listOf(ms(now) - 5 * MINUTE_MS))
        assertEquals(BlockKind.APP_BLOCKED, decideApp(breakOver, "com.game", now, usage(), noExempt)?.kind)
    }

    @Test fun installBlockBeatsExemption() {
        val s = base.copy(content = ContentRules(blockInstalls = true))
        val exemptAll: (String) -> Boolean = { true }
        assertEquals(BlockKind.INSTALLS, decideApp(s, "com.android.vending", at(16, 12), usage(), exemptAll)?.kind)
        assertEquals(BlockKind.INSTALLS, decideApp(s, "com.google.android.packageinstaller", at(16, 12), usage(), exemptAll)?.kind)
    }

    // ---------- Breaks ----------

    @Test fun breaksResetAtMidnight() {
        val s = base.copy(breaksTaken = listOf(ms(at(16, 9)), ms(at(16, 15))))
        assertEquals(0, breaksLeft(s, at(16, 20)))
        assertEquals(2, breaksLeft(s, at(17, 0, 1)))
        assertNull(breakUntil(s, ms(at(16, 15, 5))))
        assertEquals(ms(at(16, 15, 5)), breakUntil(s, ms(at(16, 15, 4))))
    }

    @Test fun pruneDropsOldData() {
        val now = at(16, 12)
        val s = base.copy(
            grants = listOf(Grant("a", ms(now) - 1), Grant("b", ms(now) + 1)),
            breaksTaken = listOf(ms(at(13, 9)), ms(at(16, 9))),
            oneMoreMinute = listOf("2026-11-15|x", "2026-11-16|y"),
            history = mapOf("2026-09-01" to DaySummary(), "2026-11-15" to DaySummary()),
        ).prune(now)
        assertEquals(listOf("b"), s.grants.map { it.target })
        assertEquals(1, s.breaksTaken.size)
        assertEquals(listOf("2026-11-16|y"), s.oneMoreMinute)
        assertEquals(setOf("2026-11-15"), s.history.keys)
    }

    // ---------- Websites ----------

    @Test fun domainParsing() {
        assertEquals("m.example.com", domainOf("https://m.Example.com/path?q=1"))
        assertEquals("example.com", domainOf("www.example.com"))
        assertEquals("example.com", domainOf("example.com:8080/x"))
        assertNull(domainOf("search words"))
        assertNull(domainOf("fac"))
        assertNull(domainOf(""))
    }

    @Test fun domainMatchingIncludesSubdomainsOnly() {
        assertTrue(domainMatches("m.reddit.com", "reddit.com"))
        assertTrue(domainMatches("reddit.com", "www.reddit.com"))
        assertFalse(domainMatches("notreddit.com", "reddit.com"))
    }

    @Test fun adultFilterAvoidsObviousFalsePositives() {
        assertTrue(isAdultDomain("pornhub.com"))
        assertTrue(isAdultDomain("de.xhamster.com"))
        assertTrue(isAdultDomain("free-porn-videos.net"))
        assertTrue(isAdultDomain("something.xxx"))
        assertFalse(isAdultDomain("essex.ac.uk"))
        assertFalse(isAdultDomain("sussex.gov.uk"))
        assertFalse(isAdultDomain("middlesex.edu"))
        assertFalse(isAdultDomain("wikipedia.org"))
    }

    @Test fun webModes() {
        val now = at(16, 12)
        val adult = base.copy(content = ContentRules(webMode = WebMode.LIMIT_ADULT, blockedSites = listOf("reddit.com")))
        assertEquals(BlockKind.SITE_ADULT, decideWeb(adult, "pornhub.com", now, usage())?.kind)
        assertEquals(BlockKind.SITE_BLOCKED, decideWeb(adult, "old.reddit.com", now, usage())?.kind)
        assertNull(decideWeb(adult, "wikipedia.org", now, usage()))
        val only = base.copy(content = ContentRules(webMode = WebMode.ALLOWED_ONLY, allowedSites = listOf("wikipedia.org")))
        assertNull(decideWeb(only, "en.wikipedia.org", now, usage()))
        assertEquals(BlockKind.SITE_NOT_ALLOWED, decideWeb(only, "reddit.com", now, usage())?.kind)
        val granted = only.copy(grants = listOf(Grant(webTarget("reddit.com"), ms(now) + 1000)))
        assertNull(decideWeb(granted, "reddit.com", now, usage()))
    }

    // ---------- Passcode ----------

    @Test fun lockoutGrows() {
        assertEquals(0L, lockoutMs(4))
        assertEquals(MINUTE_MS, lockoutMs(5))
        assertEquals(5 * MINUTE_MS, lockoutMs(6))
        assertEquals(15 * MINUTE_MS, lockoutMs(7))
        assertEquals(HOUR_MS, lockoutMs(12))
    }

    @Test fun passcodeFormat() {
        assertTrue(validPasscode("1234"))
        assertFalse(validPasscode("12a4"))
        assertFalse(validPasscode("123"))
    }

    // ---------- Usage folding ----------

    private fun fold(day: LocalDate = LocalDate.of(2026, 11, 16)) =
        DayFold(startOfDay(day, zone), { it == "launcher" }, catOf)

    @Test fun sessionsAddUpAndLauncherIsIgnored() {
        val f = fold()
        val t = f.dayStart + 9 * HOUR_MS
        f.feed(t, Ev.SCREEN_ON, "android")
        f.feed(t + 1000, Ev.RESUMED, "launcher")
        f.feed(t + 2000, Ev.PAUSED, "launcher")
        f.feed(t + 2000, Ev.RESUMED, "com.insta")
        f.feed(t + 2000 + 10 * MINUTE_MS, Ev.PAUSED, "com.insta")
        f.feed(t + 2000 + 10 * MINUTE_MS, Ev.RESUMED, "com.game")
        f.feed(t + 2000 + 15 * MINUTE_MS, Ev.SCREEN_OFF, "android")
        val s = f.snapshot(t + HOUR_MS)
        assertEquals(10 * MINUTE_MS, s.apps["com.insta"])
        assertEquals(5 * MINUTE_MS, s.apps["com.game"])
        assertNull(s.apps["launcher"])
        assertEquals(15 * MINUTE_MS, s.totalMs)
        assertEquals(1, s.pickups)
        assertEquals(mapOf("com.insta" to 1), s.firstAfterPickup)
        assertEquals(10 * MINUTE_MS, s.hours[9][Cat.SOCIAL])
        assertNull(f.top)
    }

    @Test fun notCountedAppIsLeftOutButStillOnTop() {
        val f = DayFold(startOfDay(LocalDate.of(2026, 11, 16), zone), { it == "launcher" || it == "com.maps" }, catOf)
        val t = f.dayStart + 17 * HOUR_MS
        f.feed(t, Ev.RESUMED, "com.insta")
        f.feed(t + 5 * MINUTE_MS, Ev.PAUSED, "com.insta")
        f.feed(t + 5 * MINUTE_MS, Ev.RESUMED, "com.maps")
        val s = f.snapshot(t + 45 * MINUTE_MS)
        assertEquals(5 * MINUTE_MS, s.totalMs)
        assertNull(s.apps["com.maps"])
        // Still known to be on screen, so Downtime can block it.
        assertEquals("com.maps", f.top)
        val limit = AppLimit("n", categories = setOf(Cat.OTHER), minutes = 10)
        assertEquals(10 * MINUTE_MS, limitLeftMs(limit, Usage(s.apps, emptyMap(), catOf), at(16, 17)))
    }

    @Test fun openAppCountsUpToNowAndIsTop() {
        val f = fold()
        val t = f.dayStart + 10 * HOUR_MS
        f.feed(t, Ev.RESUMED, "com.game")
        assertEquals("com.game", f.top)
        assertEquals(7 * MINUTE_MS, f.snapshot(t + 7 * MINUTE_MS).apps["com.game"])
    }

    @Test fun sessionAcrossMidnightOnlyCountsToday() {
        val f = fold()
        f.feed(f.dayStart - 30 * MINUTE_MS, Ev.RESUMED, "com.game")
        f.feed(f.dayStart + 20 * MINUTE_MS, Ev.PAUSED, "com.game")
        val s = f.snapshot(f.dayStart + HOUR_MS)
        assertEquals(20 * MINUTE_MS, s.apps["com.game"])
        assertEquals(0, s.pickups)
    }

    @Test fun sessionSplitsAcrossHours() {
        val f = fold()
        val t = f.dayStart + 13 * HOUR_MS + 50 * MINUTE_MS
        f.feed(t, Ev.RESUMED, "com.insta")
        f.feed(t + 20 * MINUTE_MS, Ev.PAUSED, "com.insta")
        val s = f.snapshot(t + HOUR_MS)
        assertEquals(10 * MINUTE_MS, s.hours[13][Cat.SOCIAL])
        assertEquals(10 * MINUTE_MS, s.hours[14][Cat.SOCIAL])
    }

    @Test fun activityChangesInsideAnAppDontDoubleCount() {
        val f = fold()
        val t = f.dayStart + 8 * HOUR_MS
        f.feed(t, Ev.RESUMED, "com.insta")
        f.feed(t + MINUTE_MS, Ev.PAUSED, "com.insta")
        f.feed(t + MINUTE_MS, Ev.RESUMED, "com.insta")
        f.feed(t + 3 * MINUTE_MS, Ev.PAUSED, "com.insta")
        assertEquals(3 * MINUTE_MS, f.snapshot(t + HOUR_MS).apps["com.insta"])
    }

    // ---------- Formatting ----------

    @Test fun formatting() {
        assertEquals("1h 5m", formatDuration(65 * MINUTE_MS))
        assertEquals("2h", formatDuration(2 * HOUR_MS))
        assertEquals("<1m", formatDuration(30_000))
        assertEquals("0m", formatDuration(0))
        assertEquals("10:00 PM", formatMinuteOfDay(22 * 60))
        assertEquals("12:30 AM", formatMinuteOfDay(30))
        assertEquals(-50, percentChange(100, 50))
        assertNull(percentChange(0, 50))
    }

    @Test fun weekTotalSumsSevenDays() {
        val h = (0..9).associate { dayKey(LocalDate.of(2026, 11, 16).minusDays(it.toLong())) to DaySummary(totalMs = HOUR_MS) }
        assertEquals(7 * HOUR_MS, weekTotal(h, LocalDate.of(2026, 11, 16)))
    }

    @Test fun limitTitles() {
        val names = mapOf("a" to "Instagram", "b" to "TikTok", "c" to "X")
        assertEquals("Social", limitTitle(AppLimit("1", categories = setOf(Cat.SOCIAL))) { names[it]!! })
        assertEquals("Instagram, TikTok + 1", limitTitle(AppLimit("1", apps = setOf("a", "b", "c"))) { names[it]!! })
    }

    // ---------- Stricter changes ----------

    @Test fun downtimeStricterOnlyWhenItCoversAtLeastTheSameTime() {
        val old = Downtime(enabled = true, window = Window(22 * 60, 7 * 60))
        assertTrue(downtimeStricter(old, old.copy(window = Window(21 * 60, 8 * 60))))
        assertFalse(downtimeStricter(old, old.copy(window = Window(23 * 60, 7 * 60))))
        assertFalse(downtimeStricter(old, old.copy(enabled = false)))
        assertFalse(downtimeStricter(old, old.copy(blockAtDowntime = false)))
        // Customizing days: dropping Saturday loosens, keeping every day as-is doesn't.
        val custom = old.copy(everyDay = false, days = (1..7).associateWith { old.window })
        assertTrue(downtimeStricter(old, custom))
        assertFalse(downtimeStricter(old, custom.copy(days = custom.days - 6)))
        assertTrue(downtimeStricter(Downtime(enabled = false), old))
    }

    @Test fun limitStricterNeedsMoreTargetsAndLessTime() {
        val old = AppLimit("1", apps = setOf("a"), domains = setOf("youtube.com"), minutes = 60)
        assertTrue(limitStricter(null, old))
        assertTrue(limitStricter(old, old.copy(minutes = 30, apps = setOf("a", "b"))))
        assertFalse(limitStricter(old, old.copy(minutes = 90)))
        assertFalse(limitStricter(old, old.copy(apps = setOf("b"))))
        assertFalse(limitStricter(old, old.copy(domains = setOf("m.youtube.com"))))
        assertFalse(limitStricter(old, old.copy(dayMinutes = mapOf(6 to 120))))
        assertFalse(limitStricter(old, old.copy(enabled = false)))
        assertFalse(limitStricter(old, old.copy(blockAtEnd = false)))
        assertTrue(limitStricter(old.copy(enabled = false), old.copy(minutes = 240)))
    }

    @Test fun breaksAndCategoriesStricter() {
        assertTrue(breaksStricter(BreakRules(2, 5), BreakRules(1, 5)))
        assertFalse(breaksStricter(BreakRules(2, 5), BreakRules(2, 10)))
        val limits = listOf(AppLimit("1", categories = setOf(Cat.SOCIAL)))
        assertTrue(categoryStricter(limits, Cat.OTHER, Cat.SOCIAL))
        assertFalse(categoryStricter(limits, Cat.SOCIAL, Cat.OTHER))
    }

    @Test fun contentStricterChecksEveryRule() {
        val old = ContentRules(blockedApps = setOf("a"), webMode = WebMode.LIMIT_ADULT, blockedSites = listOf("reddit.com"), allowedSites = emptyList())
        assertTrue(contentStricter(old, old.copy(blockedApps = setOf("a", "b"), blockInstalls = true, lockAccounts = true)))
        assertFalse(contentStricter(old, old.copy(blockedApps = emptySet())))
        assertFalse(contentStricter(old, old.copy(lockDateTime = false)))
        assertFalse(contentStricter(old, old.copy(webMode = WebMode.UNRESTRICTED)))
        assertFalse(contentStricter(old, old.copy(blockedSites = emptyList())))
        assertTrue(contentStricter(old, old.copy(blockedSites = listOf("reddit.com", "x.com"))))
        assertFalse(contentStricter(old, old.copy(allowedSites = listOf("pornhub.com"))))
        // Allowed Websites Only can't allow a site that was blocked.
        assertTrue(contentStricter(old, old.copy(webMode = WebMode.ALLOWED_ONLY, allowedSites = listOf("wikipedia.org"))))
        assertFalse(contentStricter(old, old.copy(webMode = WebMode.ALLOWED_ONLY, allowedSites = listOf("old.reddit.com"))))
        val only = ContentRules(webMode = WebMode.ALLOWED_ONLY, allowedSites = listOf("wikipedia.org"))
        assertTrue(contentStricter(only, only.copy(allowedSites = emptyList())))
        assertFalse(contentStricter(only, only.copy(allowedSites = listOf("wikipedia.org", "youtube.com"))))
    }
}
