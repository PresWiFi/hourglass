package com.blakeriding.hourglass

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Telephony
import android.telecom.TelecomManager
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

data class AppInfo(val pkg: String, val label: String, val cat: Cat)

/** Names, categories and icons of installed apps, cached. */
object Apps {
    private val labels = ConcurrentHashMap<String, String>()
    private val declared = ConcurrentHashMap<String, Cat>()
    private val icons = ConcurrentHashMap<String, Bitmap>()
    @Volatile private var launchers: Set<String> = emptySet()
    @Volatile private var launchable: List<AppInfo>? = null

    fun label(context: Context, pkg: String): String = labels.getOrPut(pkg) {
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg.substringAfterLast('.')
        }
    }

    fun category(context: Context, pkg: String): Cat {
        Store.state.value.categoryOverrides[pkg]?.let { return it }
        return declared.getOrPut(pkg) { declaredCategory(context, pkg) }
    }

    private fun declaredCategory(context: Context, pkg: String): Cat {
        KNOWN[pkg]?.let { return it }
        val info = try {
            context.packageManager.getApplicationInfo(pkg, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return Cat.OTHER
        }
        return when (info.category) {
            ApplicationInfo.CATEGORY_GAME -> Cat.GAMES
            ApplicationInfo.CATEGORY_SOCIAL -> Cat.SOCIAL
            ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> Cat.ENTERTAINMENT
            ApplicationInfo.CATEGORY_IMAGE -> Cat.CREATIVITY
            ApplicationInfo.CATEGORY_NEWS -> Cat.INFO
            ApplicationInfo.CATEGORY_MAPS -> Cat.NAVIGATION
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> Cat.PRODUCTIVITY
            ApplicationInfo.CATEGORY_ACCESSIBILITY -> Cat.UTILITIES
            else -> if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) Cat.UTILITIES else Cat.OTHER
        }
    }

    fun icon(context: Context, pkg: String): Bitmap? = icons[pkg] ?: try {
        context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).also { icons[pkg] = it }
    } catch (_: Exception) {
        null
    }

    /** Apps with a launcher icon, sorted by name. Slow the first time. */
    fun launchable(context: Context, refresh: Boolean = false): List<AppInfo> {
        if (!refresh) launchable?.let { return it }
        val pm = context.packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .map { AppInfo(it, label(context, it), category(context, it)) }
            .sortedBy { it.label.lowercase() }
        launchable = list
        return list
    }

    fun launchers(context: Context): Set<String> {
        if (launchers.isEmpty()) {
            launchers = context.packageManager
                .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .map { it.activityInfo.packageName }
                .toSet()
        }
        return launchers
    }

    /** Never blocked and not counted as screen time: home screens, system UI, Hourglass itself. */
    fun isSystemSurface(context: Context, pkg: String): Boolean =
        pkg == context.packageName || pkg in launchers(context) || pkg in SYSTEM_SEARCH || SURFACE.any { pkg.contains(it) }

    /** Never blocked, but counted: phone, emergency, and Settings (so you can always fix Wi-Fi). */
    fun isExempt(context: Context, pkg: String): Boolean =
        isSystemSurface(context, pkg) || EXEMPT.any { pkg.contains(it) } || pkg == defaultDialer(context)

    fun defaultDialer(context: Context): String? =
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage

    fun defaultSms(context: Context): String? = Telephony.Sms.getDefaultSmsPackage(context)

    fun forget(pkg: String) {
        labels.remove(pkg); declared.remove(pkg); icons.remove(pkg)
        launchable = null
    }

    fun invalidateCategories() = declared.clear()

    // The phone's own search, opened by swiping down on the home screen: part of the home screen, not an app.
    // Exact names, because "quicksearchbox" is also in the Google app's package name.
    private val SYSTEM_SEARCH = setOf(
        "com.heytap.quicksearchbox", "com.oplus.quicksearchbox", "com.oppo.quicksearchbox",
        "com.coloros.quicksearchbox", "com.android.quicksearchbox", "com.samsung.android.app.galaxyfinder",
    )
    private val SURFACE = listOf("com.android.systemui", "inputmethod", "com.google.android.permissioncontroller")
    private val EXEMPT = listOf(
        "com.android.settings", "com.oplus.settings", "com.coloros.settings", "dialer", "telecom", "incallui",
        "emergency", "com.android.phone", "com.android.server.telecom", "com.android.packageinstaller",
        "com.google.android.packageinstaller",
    )

    // Popular apps that don't declare a category in their manifest.
    private val KNOWN = mapOf(
        "com.instagram.android" to Cat.SOCIAL,
        "com.zhiliaoapp.musically" to Cat.SOCIAL,
        "com.ss.android.ugc.trill" to Cat.SOCIAL,
        "com.snapchat.android" to Cat.SOCIAL,
        "com.facebook.katana" to Cat.SOCIAL,
        "com.facebook.orca" to Cat.SOCIAL,
        "com.twitter.android" to Cat.SOCIAL,
        "com.reddit.frontpage" to Cat.SOCIAL,
        "com.discord" to Cat.SOCIAL,
        "com.pinterest" to Cat.SOCIAL,
        "com.bereal.ft" to Cat.SOCIAL,
        "org.thunderdog.challegram" to Cat.SOCIAL,
        "com.whatsapp" to Cat.SOCIAL,
        "org.telegram.messenger" to Cat.SOCIAL,
        "com.instagram.barcelona" to Cat.SOCIAL,
        "com.google.android.youtube" to Cat.ENTERTAINMENT,
        "com.netflix.mediaclient" to Cat.ENTERTAINMENT,
        "com.spotify.music" to Cat.ENTERTAINMENT,
        "tv.twitch.android.app" to Cat.ENTERTAINMENT,
        "com.disney.disneyplus" to Cat.ENTERTAINMENT,
        "com.hulu.plus" to Cat.ENTERTAINMENT,
        "com.amazon.avod.thirdpartyclient" to Cat.ENTERTAINMENT,
        "com.amazon.mShop.android.shopping" to Cat.SHOPPING,
        "com.ubercab.eats" to Cat.SHOPPING,
        "com.dd.doordash" to Cat.SHOPPING,
        "com.android.chrome" to Cat.PRODUCTIVITY,
        "com.google.android.gm" to Cat.PRODUCTIVITY,
        "com.google.android.apps.maps" to Cat.NAVIGATION,
    )
}
