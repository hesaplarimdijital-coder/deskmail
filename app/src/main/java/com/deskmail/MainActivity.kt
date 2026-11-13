package com.deskmail

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import com.deskmail.mail.MailView
import com.deskmail.notify.NotifyGate
import com.deskmail.notify.OpenRequest
import com.deskmail.ui.CommandHost
import com.deskmail.ui.DeskMailApp
import com.deskmail.ui.DesktopWindow
import com.deskmail.ui.KeyRouter
import com.deskmail.ui.compose.ComposeActions
import com.deskmail.ui.compose.ComposeRequest
import com.deskmail.ui.compose.WriteApart
import com.deskmail.ui.isTyping
import com.deskmail.ui.mail.MailUiState
import com.deskmail.ui.settings.SettingsUiState
import com.deskmail.ui.shortcuts
import desktopkit.components.CommandPaletteState
import desktopkit.shell.DeskWindows

/**
 * One window of the app. There can be several (DeX, a tablet's split screen): each is an activity
 * of its own with its own shell, opened at the route the other window asked for.
 */
class MainActivity : ComponentActivity() {

    private val state: WindowState by lazy { ViewModelProvider(this)[WindowState::class.java] }

    /** Ctrl+K: the command palette. Kept here, so the keys can be left alone while it is open. */
    private val palette = CommandPaletteState()

    /** The composition says what performs the window's commands. */
    private val commands = CommandHost()

    /** How often the window came to the front; the screens read it. */
    private var resumes by mutableIntStateOf(0)

    /** Whether the window is in front, and which list of mail it shows there: new mail in that list needs no notification. */
    private var inFront = false
    private var listShown: MailView? = null

    /** The system's question whether the app may show notifications; what was answered shows in the gate. */
    private val askToNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        gate.allowed = deskMail.notifier.allowed
        deskMail.log.info("notify", if (granted) "the user allows notifications" else "the user does not allow notifications")
    }

    /** What the window knows of the leave to notify, and how it asks for it. */
    private val gate: NotifyGate by lazy {
        val store = deskMail.notifyStore
        NotifyGate(
            ask = {
                // The system asks twice at most; after that the way leads through its settings.
                if (!store.asked || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    store.asked = true
                    gate.asked = true
                    askToNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openNotificationSettings()
                }
            },
            openSystemSettings = ::openNotificationSettings,
            askBattery = {
                store.batteryAsked = true
                gate.batteryAsked = true
                askForBattery()
            },
            skipBattery = {
                store.batteryAsked = true
                gate.batteryAsked = true
            },
            openBatterySettings = ::openAppSettings,
        ).also {
            it.asked = store.asked
            it.allowed = deskMail.notifier.allowed
            it.batteryAsked = store.batteryAsked
            it.unrestricted = unrestricted()
        }
    }

    /**
     * On a desktop (DeX, Android's desktop windowing) a message is written in a window of its own
     * ([WriteActivity]); this window says what came of it.
     */
    private val writeApart = object : WriteApart {
        /** The writing of this window, as told by its composition. */
        private var told: ComposeActions? = null

        override fun now(): Boolean = DesktopWindow.isDesktop(this@MainActivity)

        override fun open(draftId: Long, fresh: Boolean) {
            // being written already: its window comes to the front
            val task = deskMail.writeWindows.taskOf(draftId)
            val existing = task?.let { id ->
                // a task that went meanwhile says so by throwing
                getSystemService(ActivityManager::class.java).appTasks.firstOrNull { runCatching { it.taskInfo.taskId == id }.getOrDefault(false) }
            }
            if (existing != null && runCatching { existing.moveToFront() }.isSuccess) return
            DeskWindows.openNewWindow(this@MainActivity, intent = WriteActivity.intent(this@MainActivity, draftId, fresh, opener = taskId), adjacent = false)
        }

        override fun home(actions: ComposeActions?) {
            val windows = deskMail.writeWindows
            told?.let { windows.leave(taskId, it) }
            told = actions
            actions?.let { windows.home(taskId, it) }
        }
    }

    private val keys = KeyRouter(
        registry = shortcuts,
        paletteOpen = { palette.isOpen },
        typing = { isTyping(window.decorView, currentFocus) },
        perform = { id -> commands.perform(id) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // started by a tapped notification: the message it told of is opened
        if (savedInstanceState == null) OpenRequest.from(intent, deskMail.notifyStore.token)?.let { state.mail.openRequest = it }
        // started by a mailto link or by something shared with the app: a message is begun with it
        if (savedInstanceState == null) ComposeRequest.from(this, intent)?.let { state.mail.composeRequest = it }
        setContent {
            DeskMailApp(
                mail = deskMail.mail,
                settings = deskMail.settings,
                log = deskMail.log,
                composer = deskMail.composer,
                accounts = deskMail.accounts,
                ui = state.mail,
                settingsUi = state.settings,
                resumes = resumes,
                startRoute = DeskWindows.startRoute(intent),
                palette = palette,
                commands = commands,
                notifications = gate,
                writeApart = writeApart,
                onFrontList = { view ->
                    listShown = view
                    tellFront()
                },
                onCheckChosen = { deskMail.lookForMail(restart = true) },
            )
        }
        // the first message is to open like every other one
        deskMail.webWarmUp.schedule(this)
        // new mail is looked for while no window is open
        deskMail.lookForMail(restart = false)
    }

    /** A notification was tapped while this window was there: it opens the message. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        OpenRequest.from(intent, deskMail.notifyStore.token)?.let { state.mail.openRequest = it }
        ComposeRequest.from(this, intent)?.let { state.mail.composeRequest = it }
    }

    /** Back in front: the screen that shows mail looks for what is new, unless it just did. */
    override fun onResume() {
        super.onResume()
        resumes++
        inFront = true
        tellFront()
        // the system's settings may have been visited in between
        gate.allowed = deskMail.notifier.allowed
        gate.unrestricted = unrestricted()
    }

    override fun onPause() {
        inFront = false
        tellFront()
        super.onPause()
    }

    override fun onDestroy() {
        deskMail.front.show(this, null)
        super.onDestroy()
    }

    private fun tellFront() = deskMail.front.show(this, if (inFront) listShown else null)

    private fun openNotificationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (e: Exception) {
            deskMail.log.warn("notify", "the system's notification settings could not be opened", e)
        }
    }

    /** Whether the system leaves the app alone in the background: battery optimisation is off for it. */
    private fun unrestricted(): Boolean =
        try {
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        } catch (e: Exception) {
            false
        }

    /** The system's own question whether the app may run in the background unhindered; its list of apps where it does not ask. */
    private fun askForBattery() {
        val app = Uri.fromParts("package", packageName, null)
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, app))
        } catch (e: Exception) {
            deskMail.log.warn("watch", "the system's battery question could not be asked", e)
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                openAppSettings()
            }
        }
    }

    /** The system's page for the app: its battery use is set from there. */
    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (e: Exception) {
            deskMail.log.warn("watch", "the system's page for the app could not be opened", e)
        }
    }

    /** Every key passes here first, whatever has the focus: the shortcut table decides. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = keys.route(event) || super.dispatchKeyEvent(event)

    /** Meta+/ (the system's list of keyboard shortcuts) shows the same table. */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        data.addAll(shortcuts.keyboardShortcutGroups(resources))
    }
}

/** What this window shows, kept when something recreates its activity. */
class WindowState(application: Application) : AndroidViewModel(application) {
    val mail = MailUiState(application.deskMail.settings)
    val settings = SettingsUiState()
}
