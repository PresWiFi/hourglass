package com.blakeriding.hourglass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.blakeriding.hourglass.ui.AlwaysAllowedScreen
import com.blakeriding.hourglass.ui.ContentScreen
import com.blakeriding.hourglass.ui.DowntimeScreen
import com.blakeriding.hourglass.ui.HourglassTheme
import com.blakeriding.hourglass.ui.LimitEditScreen
import com.blakeriding.hourglass.ui.LimitsListScreen
import com.blakeriding.hourglass.ui.LimitsScreen
import com.blakeriding.hourglass.ui.PasscodeDialog
import com.blakeriding.hourglass.ui.SettingsScreen
import com.blakeriding.hourglass.ui.SetupScreen
import com.blakeriding.hourglass.ui.TamperScreen
import com.blakeriding.hourglass.ui.UsageScreen

sealed interface Route {
    data object Downtime : Route
    data object AppLimits : Route
    data class LimitEdit(val id: String?, val presetApp: String? = null) : Route
    data object AlwaysAllowed : Route
    data object Content : Route
    data object Tamper : Route
}

enum class Tab(val label: String) { USAGE("Usage"), LIMITS("Screen Time"), SETTINGS("Settings") }

/**
 * Wraps changes in the Screen Time passcode, like iOS: once entered, settings stay unlocked until
 * you leave the app.
 */
class Gate(private val ask: (() -> Unit) -> Unit) {
    operator fun invoke(action: () -> Unit) = ask(action)
}

class MainActivity : ComponentActivity() {
    private var unlocked by mutableStateOf(false)
    private var pendingAction by mutableStateOf<(() -> Unit)?>(null)
    private var tab by mutableStateOf(Tab.USAGE)
    private var stack by mutableStateOf(listOf<Route>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Engine.start(this)
        MonitorService.start(this)
        val gate = Gate { action ->
            val s = Store.state.value
            if (s.passcode == null || unlocked) action() else pendingAction = action
        }
        setContent {
            HourglassTheme {
                val s by Store.state.collectAsState()
                if (s.passcode == null) {
                    SetupScreen()
                    return@HourglassTheme
                }
                Main(gate)
                pendingAction?.let { action ->
                    PasscodeDialog(
                        onDismiss = { pendingAction = null },
                        onSuccess = {
                            unlocked = true
                            pendingAction = null
                            action()
                        },
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Re-lock when you leave, like iOS.
        unlocked = false
    }

    @Composable
    private fun Main(gate: Gate) {
        val push: (Route) -> Unit = { stack = stack + it }
        val pop: () -> Unit = { stack = stack.dropLast(1) }
        BackHandler(enabled = stack.isNotEmpty()) { pop() }
        val top = stack.lastOrNull()
        if (top != null) {
            Box(Modifier.fillMaxSize()) {
                when (top) {
                    Route.Downtime -> DowntimeScreen(gate, unlocked, pop)
                    Route.AppLimits -> LimitsListScreen(gate, unlocked, push, pop)
                    is Route.LimitEdit -> LimitEditScreen(top.id, top.presetApp, gate, unlocked, pop)
                    Route.AlwaysAllowed -> AlwaysAllowedScreen(gate, unlocked, pop)
                    Route.Content -> ContentScreen(gate, unlocked, pop)
                    Route.Tamper -> TamperScreen(pop)
                }
            }
            return
        }
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = {
                                Icon(
                                    when (t) {
                                        Tab.USAGE -> Icons.Filled.DateRange
                                        Tab.LIMITS -> Icons.Filled.Lock
                                        Tab.SETTINGS -> Icons.Filled.Settings
                                    },
                                    null,
                                )
                            },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (tab) {
                    Tab.USAGE -> UsageScreen(gate, onAddLimit = { pkg -> gate { push(Route.LimitEdit(null, pkg)) } })
                    Tab.LIMITS -> LimitsScreen(gate, unlocked, push)
                    Tab.SETTINGS -> SettingsScreen(gate, unlocked, push, onLock = { unlocked = false })
                }
            }
        }
    }
}
