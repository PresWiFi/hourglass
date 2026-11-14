package com.blakeriding.hourglass

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Three jobs:
 * 1. Tells [Engine] the moment the screen changes, so blocks are instant.
 * 2. Reads the address bar of common browsers, for website rules and website time.
 * 3. While Screen Time is on, backs out of system screens that could uninstall Hourglass, switch it off,
 *    or get around it (app info, uninstall dialog, device admin, accessibility, usage access, and the
 *    optional Date & time / Accounts / Developer options / Private DNS locks).
 */
class GuardService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastToastAt = 0L
    private var lastLogAt = 0L
    private var lastUrlReadAt = 0L
    private val recheck = Runnable { guard() }

    override fun onServiceConnected() {
        instance = this
        Store.init(this)
        Engine.start(this)
        MonitorService.start(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        if (protectionActive(Store.state.value, nowMs())) Store.update { it.logTamper(TamperKind.GUARD_OFF, nowMs()) }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) Engine.poke()
        if (pkg == packageName) return
        BROWSERS[pkg]?.let { ids -> readUrl(pkg, ids, event.eventType) }
        if (isSensitive(pkg) && protectionActive(Store.state.value, nowMs())) guard()
    }

    private fun readUrl(pkg: String, ids: List<String>, type: Int) {
        val t = SystemClock.elapsedRealtime()
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && t - lastUrlReadAt < 400) return
        lastUrlReadAt = t
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val bar = ids.firstNotNullOfOrNull { id -> root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull() } ?: return
        // While you're typing in the address bar the text isn't a page yet.
        if (bar.isFocused) return
        val domain = domainOf(bar.text?.toString().orEmpty()) ?: return
        Engine.setBrowserSite(pkg, domain)
    }

    private fun guard() {
        val s = Store.state.value
        if (!protectionActive(s, nowMs())) return
        val label = getString(R.string.app_name)
        val hit = windows.firstNotNullOfOrNull { window ->
            val root = window.root ?: return@firstNotNullOfOrNull null
            val pkg = root.packageName?.toString()
            if (!isSensitive(pkg)) return@firstNotNullOfOrNull null
            when {
                // Anything about Hourglass itself in Settings, the installer or a store.
                root.has(label) && (pkg?.contains("packageinstaller") == true || PROTECT_WORDS.any { root.has(it) }) -> "Hourglass settings"
                s.content.lockDateTime && DATE_WORDS.any { root.has(it) } -> "Date & time"
                s.content.lockAccounts && ACCOUNT_WORDS.any { root.has(it) } -> "Accounts"
                s.content.lockDeveloper && DEV_WORDS.any { root.has(it) } -> "Developer options"
                s.content.lockPrivateDns && DNS_WORDS.any { root.has(it) } -> "Private DNS"
                s.content.blockInstalls && root.has("Install unknown apps") -> "Install unknown apps"
                else -> null
            }
        } ?: return
        performGlobalAction(GLOBAL_ACTION_BACK)
        val now = SystemClock.elapsedRealtime()
        // One log entry per attempt, not one per back press.
        if (now - lastLogAt > 30_000L) Store.update { it.logTamper(TamperKind.BLOCKED_SCREEN, nowMs(), hit) }
        lastLogAt = now
        if (now - lastToastAt > 3000L) {
            lastToastAt = now
            toast(getString(R.string.protected_toast))
        }
        // Keep backing out until the screen is gone, even if no further event arrives.
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, 300L)
    }

    private fun AccessibilityNodeInfo.has(text: String) = findAccessibilityNodeInfosByText(text).isNotEmpty()

    private fun isSensitive(pkg: String?) = pkg != null && SENSITIVE.any { it in pkg }

    companion object {
        @Volatile var instance: GuardService? = null
            private set

        // Settings apps, installers and OEM phone-manager apps (OnePlus/Oppo use oplus/coloros names).
        private val SENSITIVE = listOf(
            "settings", "packageinstaller", "permissioncontroller", "securitycenter", "safecenter",
            "com.oplus.battery", "com.coloros", "com.android.vending",
        )

        // Shown with the app's name on the screens that can disable or remove it.
        private val PROTECT_WORDS = listOf(
            "Uninstall", "Force stop", "Deactivate", "device admin", "Accessibility", "Usage access",
            "Display over other apps", "Clear storage", "Clear data", "Disable", "Notification access",
            "Battery", "Use Hourglass", "Permit usage", "Remove",
        )
        private val DATE_WORDS = listOf("Set time automatically", "Automatic date", "Set date", "Use network-provided time", "Set time zone automatically")
        private val ACCOUNT_WORDS = listOf("Add account", "Remove account")
        private val DEV_WORDS = listOf("USB debugging", "Wireless debugging")
        private val DNS_WORDS = listOf("Private DNS provider hostname", "Private DNS mode")

        /** Browser package to address-bar view ids. */
        private val BROWSERS = mapOf(
            "com.android.chrome" to listOf("url_bar"),
            "com.chrome.beta" to listOf("url_bar"),
            "com.chrome.dev" to listOf("url_bar"),
            "com.brave.browser" to listOf("url_bar"),
            "com.microsoft.emmx" to listOf("url_bar"),
            "com.vivaldi.browser" to listOf("url_bar"),
            "com.kiwibrowser.browser" to listOf("url_bar"),
            "com.sec.android.app.sbrowser" to listOf("location_bar_edit_text"),
            "org.mozilla.firefox" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
            "org.mozilla.firefox_beta" to listOf("mozac_browser_toolbar_url_view"),
            "org.mozilla.focus" to listOf("display_url", "mozac_browser_toolbar_url_view"),
            "com.duckduckgo.mobile.android" to listOf("omnibarTextInput"),
            "com.opera.browser" to listOf("url_field"),
            "com.opera.mini.native" to listOf("url_field"),
            "com.heytap.browser" to listOf("url_text"),
        )
        val browserPackages: Set<String> get() = BROWSERS.keys
    }
}
