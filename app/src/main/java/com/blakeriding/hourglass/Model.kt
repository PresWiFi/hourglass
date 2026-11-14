package com.blakeriding.hourglass

import kotlinx.serialization.Serializable

/** Everything Hourglass remembers, saved as one JSON file. */
@Serializable
data class AppState(
    /** Screen Time on/off. Turning it off needs the passcode. */
    val enabled: Boolean = true,
    val passcode: Secret? = null,
    /** Resets the passcode if you forget it. Shown once at setup. */
    val recovery: Secret? = null,
    val failedAttempts: Int = 0,
    val lockedUntil: Long = 0,
    val downtime: Downtime = Downtime(),
    val downtimeOverride: DowntimeOverride? = null,
    val limits: List<AppLimit> = emptyList(),
    /** Apps that work during Downtime and are never limited (like iOS "Always Allowed"). */
    val alwaysAllowed: Set<String> = emptySet(),
    val content: ContentRules = ContentRules(),
    val breaks: BreakRules = BreakRules(),
    /** Start times of emergency breaks, trimmed to the last two days. */
    val breaksTaken: List<Long> = emptyList(),
    /** "Ask for more time" approvals and "Ignore limit" choices. */
    val grants: List<Grant> = emptyList(),
    /** "yyyy-mm-dd|target" for each One More Minute used. */
    val oneMoreMinute: List<String> = emptyList(),
    /** Your own category for an app, when the app doesn't declare one (or declares the wrong one). */
    val categoryOverrides: Map<String, Cat> = emptyMap(),
    /** Finished days, keyed by yyyy-mm-dd. Trimmed to [HISTORY_DAYS]. */
    val history: Map<String, DaySummary> = emptyMap(),
    /** day -> package -> notifications received. */
    val notifCounts: Map<String, Map<String, Int>> = emptyMap(),
    /** day -> domain -> ms spent on it in a browser. */
    val webTime: Map<String, Map<String, Long>> = emptyMap(),
    /** The guard stands down until this time (entered with the passcode, e.g. to change a system setting). */
    val protectionPausedUntil: Long = 0,
    val tamper: List<TamperEvent> = emptyList(),
    val lastWeeklyReport: String = "",
)

@Serializable
data class Secret(val salt: String, val hash: String, val iterations: Int)

/** Minutes from midnight. When end <= start the window runs past midnight. */
@Serializable
data class Window(val start: Int, val end: Int)

@Serializable
data class Downtime(
    val enabled: Boolean = false,
    val everyDay: Boolean = true,
    val window: Window = Window(22 * 60, 7 * 60),
    /** Customized days: ISO day of week (1 = Monday) to window. A missing day has no Downtime. */
    val days: Map<Int, Window> = emptyMap(),
    /** Off = a reminder screen you can dismiss. On = needs the passcode, like iOS "Block at Downtime". */
    val blockAtDowntime: Boolean = true,
)

/** on = Downtime turned on by hand until [until]; off = scheduled Downtime skipped until [until]. */
@Serializable
data class DowntimeOverride(val on: Boolean, val until: Long)

@Serializable
data class AppLimit(
    val id: String,
    val apps: Set<String> = emptySet(),
    val categories: Set<Cat> = emptySet(),
    val domains: Set<String> = emptySet(),
    val minutes: Int = 60,
    /** Customized days: ISO day of week to minutes. Missing days use [minutes]. */
    val dayMinutes: Map<Int, Int> = emptyMap(),
    val blockAtEnd: Boolean = true,
    val enabled: Boolean = true,
)

@Serializable
enum class Cat(val label: String) {
    SOCIAL("Social"),
    GAMES("Games"),
    ENTERTAINMENT("Entertainment"),
    CREATIVITY("Creativity"),
    PRODUCTIVITY("Productivity & Finance"),
    INFO("Information & Reading"),
    NAVIGATION("Travel"),
    SHOPPING("Shopping & Food"),
    UTILITIES("Utilities"),
    OTHER("Other"),
}

@Serializable
enum class WebMode { UNRESTRICTED, LIMIT_ADULT, ALLOWED_ONLY }

@Serializable
data class ContentRules(
    /** Never allowed, even outside Downtime. */
    val blockedApps: Set<String> = emptySet(),
    /** Blocks the Play Store and app installer, like iOS "Installing Apps: Don't Allow". */
    val blockInstalls: Boolean = false,
    val webMode: WebMode = WebMode.UNRESTRICTED,
    /** "Never Allow" sites. Applies in every mode except Allowed Websites Only. */
    val blockedSites: List<String> = emptyList(),
    /** "Always Allow" sites, and the whole list in Allowed Websites Only mode. */
    val allowedSites: List<String> = DEFAULT_ALLOWED_SITES,
    /** Settings screens the guard backs out of while Screen Time is on. */
    val lockDateTime: Boolean = true,
    val lockAccounts: Boolean = false,
    val lockDeveloper: Boolean = false,
    val lockPrivateDns: Boolean = false,
)

val DEFAULT_ALLOWED_SITES = listOf("google.com", "wikipedia.org", "weather.gov")

@Serializable
data class BreakRules(val perDay: Int = 2, val minutes: Int = 5)

/** Lets [target] (a package, or "web:domain") through every block until [until]. */
@Serializable
data class Grant(val target: String, val until: Long)

@Serializable
data class DaySummary(
    val totalMs: Long = 0,
    val apps: Map<String, Long> = emptyMap(),
    /** 24 entries: ms of use per hour, split by category for the chart. */
    val hours: List<Map<Cat, Long>> = List(24) { emptyMap() },
    val pickups: Int = 0,
    val firstPickupAt: Long = 0,
    /** First app opened after each pickup. */
    val firstAfterPickup: Map<String, Int> = emptyMap(),
)

@Serializable
enum class TamperKind(val label: String) {
    BLOCKED_SCREEN("Backed out of a protected settings screen"),
    ADMIN_OFF_ATTEMPT("Tried to remove uninstall protection"),
    ADMIN_OFF("Uninstall protection was removed"),
    GUARD_OFF("Accessibility service was switched off"),
    WRONG_PASSCODE("Wrong passcode"),
    PASSCODE_RESET("Passcode reset with the recovery code"),
    SCREEN_TIME_OFF("Screen Time turned off"),
    FORCE_STOPPED("Hourglass was force-stopped"),
}

@Serializable
data class TamperEvent(val at: Long, val kind: TamperKind, val detail: String = "")

const val HISTORY_DAYS = 35
const val TAMPER_KEEP = 200
