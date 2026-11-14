package com.blakeriding.hourglass

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.UUID

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notifs.createChannels(this)
        Engine.start(this)
        MonitorService.start(this)
        WeeklyReport.schedule(this)
        Thread { checkExits(this) }.start()
    }
}

fun nowMs(): Long = System.currentTimeMillis()
fun nowZ(): ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())
fun newId(): String = UUID.randomUUID().toString()
fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

/** Protection (the guard and uninstall block) is on whenever Screen Time is on, unless paused with the passcode. */
fun protectionActive(s: AppState, nowMs: Long) = s.enabled && s.passcode != null && nowMs >= s.protectionPausedUntil

// ---------- Uninstall protection ----------

/** While active, Android refuses to uninstall the app. Turning it off is guarded by [GuardService]. */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Store.init(context)
        Store.update { it.logTamper(TamperKind.ADMIN_OFF_ATTEMPT, nowMs()) }
        return context.getString(R.string.admin_disable_warning)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Store.init(context)
        Store.update { it.logTamper(TamperKind.ADMIN_OFF, nowMs()) }
    }
}

/** Android remembers why the app last stopped; a force-stop goes in the tamper log. */
private fun checkExits(context: Context) {
    if (Build.VERSION.SDK_INT < 30) return
    val prefs = context.getSharedPreferences("exits", Context.MODE_PRIVATE)
    val since = prefs.getLong("checked", 0L)
    val exits = try {
        context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 20)
    } catch (_: Exception) {
        return
    }.filter { it.timestamp > since }
    if (exits.isEmpty()) return
    prefs.edit { putLong("checked", exits.maxOf { it.timestamp }) }
    if (since == 0L) return
    val stops = exits.filter {
        it.reason == ApplicationExitInfo.REASON_USER_REQUESTED &&
            listOf("update", "replace", "install").none { w -> w in it.description.orEmpty().lowercase() }
    }
    if (stops.isNotEmpty() && Store.state.value.enabled) {
        Store.update { s -> stops.fold(s) { acc, e -> acc.logTamper(TamperKind.FORCE_STOPPED, e.timestamp) } }
    }
}

// ---------- Setup checks ----------

fun adminComponent(context: Context) = ComponentName(context, AdminReceiver::class.java)

fun isAdminActive(context: Context): Boolean =
    context.getSystemService(DevicePolicyManager::class.java).isAdminActive(adminComponent(context))

fun adminIntent(context: Context): Intent =
    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context))
        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.admin_description))

fun removeAdmin(context: Context) {
    context.getSystemService(DevicePolicyManager::class.java).removeActiveAdmin(adminComponent(context))
}

fun hasUsageAccess(context: Context): Boolean {
    val ops = context.getSystemService(AppOpsManager::class.java)
    val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    return mode == AppOpsManager.MODE_ALLOWED
}

fun usageAccessIntent(context: Context) =
    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:${context.packageName}".toUri())

fun isAccessibilityEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val me = ComponentName(context, GuardService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
}

fun canDrawOverlays(context: Context) = Settings.canDrawOverlays(context)

fun overlayIntent(context: Context) =
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())

fun canNotify(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

fun hasNotificationAccess(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    val me = ComponentName(context, NotifCounter::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
}

fun ignoresBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

@android.annotation.SuppressLint("BatteryLife") // Sideloaded screen time app: it must keep running.
fun batteryIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())

// ---------- Notifications ----------

object Notifs {
    const val STATUS = "status"
    const val ALERTS = "alerts"
    const val REPORT = "report"
    const val STATUS_ID = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(STATUS, "Screen Time status", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(ALERTS, "Limit warnings", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(REPORT, "Weekly report", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun post(context: Context, id: Int, channel: String, title: String, text: String) {
        if (!canNotify(context)) return
        val n = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    fun limitSoon(context: Context, what: String, leftMs: Long) {
        val mins = ((leftMs + MINUTE_MS - 1) / MINUTE_MS).coerceAtLeast(1)
        post(context, 10, ALERTS, "Time limit", "$mins minute${if (mins == 1L) "" else "s"} left today on $what.")
    }

    fun downtimeSoon(context: Context, minutes: Int) =
        post(context, 11, ALERTS, "Downtime", "Downtime starts in $minutes minute${if (minutes == 1) "" else "s"}.")

    fun breakEnding(context: Context) = post(context, 12, ALERTS, "Emergency break", "Your break ends in 1 minute.")

    fun protectionOff(context: Context) = post(
        context, 13, ALERTS, "Screen Time can't enforce limits",
        "Hourglass's accessibility service is off. Open Hourglass to turn it back on.",
    )

    fun report(context: Context, title: String, text: String) = post(context, 14, REPORT, title, text)
}

// ---------- Always-running monitor ----------

/**
 * Keeps Hourglass alive (OnePlus and others kill background apps) and shows today's total.
 * Also notices when the accessibility service has been switched off.
 */
class MonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var warnedOff = false
    private val refresh = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, MINUTE_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Engine.start(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            startForeground(Notifs.STATUS_ID, build(), type)
        } catch (_: Exception) {
            stopSelf()
        }
        handler.post(refresh)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun update() {
        getSystemService(NotificationManager::class.java).notify(Notifs.STATUS_ID, build())
        val s = Store.state.value
        val off = s.enabled && s.passcode != null && !isAccessibilityEnabled(this)
        if (off && !warnedOff) {
            Store.update { it.logTamper(TamperKind.GUARD_OFF, nowMs()) }
            Notifs.protectionOff(this)
        }
        warnedOff = off
    }

    private fun build(): Notification {
        val s = Store.state.value
        val now = nowZ()
        val parts = mutableListOf("Today ${formatDuration(Engine.today.value.totalMs)}")
        if (s.enabled && downtimeActive(s, now)) parts += "Downtime"
        breakUntil(s, now.toInstant().toEpochMilli())?.let { parts += "Break until ${formatClock(it)}" }
        return Notification.Builder(this, Notifs.STATUS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (s.enabled) "Screen Time is on" else "Screen Time is off")
            .setContentText(parts.joinToString(" · "))
            .setContentIntent(Notifs.openApp(this))
            .setOngoing(true)
            .build()
    }

    companion object {
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
            } catch (_: Exception) {
                // Background start not allowed right now; the accessibility service or next app open will start it.
            }
        }
    }
}

fun formatClock(ms: Long): String {
    val z = java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return formatMinuteOfDay(z.hour * 60 + z.minute)
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Store.init(context)
        MonitorService.start(context)
        WeeklyReport.schedule(context)
    }
}

// ---------- Notification counts ----------

/** Counts notifications per app for the dashboard. Optional; only counts, never reads their content. */
class NotifCounter : NotificationListenerService() {
    private val seen = LinkedHashSet<String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_FOREGROUND_SERVICE) != 0) return
        if (sbn.packageName == packageName || sbn.packageName == "android") return
        // Updates to a notification re-post it under the same key; count it once.
        if (!seen.add(sbn.key + "|" + n.`when`)) return
        if (seen.size > 500) seen.remove(seen.first())
        Store.init(this)
        val day = dayKey(LocalDate.now())
        Store.update { s ->
            val today = s.notifCounts[day].orEmpty()
            s.copy(notifCounts = s.notifCounts + (day to today + (sbn.packageName to (today[sbn.packageName] ?: 0) + 1)))
        }
    }
}

// ---------- Weekly report ----------

object WeeklyReport {
    fun schedule(context: Context) {
        val now = nowZ()
        var next = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).withHour(19).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusWeeks(1)
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, ReportReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pi)
    }

    fun text(s: AppState, today: LocalDate): Pair<String, String> {
        // This week = the 7 days ending yesterday plus today so far isn't stable; use the 7 days ending today.
        val thisWeek = weekTotal(s.history + (dayKey(today) to Engine.today.value), today)
        val lastWeek = weekTotal(s.history, today.minusDays(7))
        val avg = thisWeek / 7
        val change = percentChange(lastWeek, thisWeek)
        val trend = when {
            change == null -> ""
            change < 0 -> " That's ${-change}% less than the week before."
            change > 0 -> " That's $change% more than the week before."
            else -> " Same as the week before."
        }
        return "Weekly report" to "Your screen time averaged ${formatDuration(avg)} a day this week.$trend"
    }
}

class ReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        val today = LocalDate.now()
        val s = Store.state.value
        if (s.lastWeeklyReport != dayKey(today)) {
            val (title, text) = WeeklyReport.text(s, today)
            Notifs.report(context, title, text)
            Store.update { it.copy(lastWeeklyReport = dayKey(today)) }
        }
        WeeklyReport.schedule(context)
    }
}
