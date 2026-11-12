package com.deskmail.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.rememberNavController
import com.deskmail.R
import com.deskmail.demo.DemoMail
import com.deskmail.mail.MailMessage
import com.deskmail.mail.MailView
import com.deskmail.settings.AppSettings
import com.deskmail.ui.mail.FoldersScreen
import com.deskmail.ui.mail.LocalMailClock
import com.deskmail.ui.mail.MailClock
import com.deskmail.ui.mail.MailScreen
import com.deskmail.ui.mail.MailUiState
import com.deskmail.ui.mail.WebMessageBody
import com.deskmail.ui.settings.SettingsScreen
import desktopkit.DesktopKitRoot
import desktopkit.appResources
import desktopkit.components.ActionGroup
import desktopkit.components.DeskIcons
import desktopkit.components.MenuEntry
import desktopkit.components.ToastDuration
import desktopkit.components.rememberToastState
import desktopkit.screens.MessageScreen
import desktopkit.settings.DeskPreferences
import desktopkit.settings.rememberDeskPreferences
import desktopkit.shell.DesktopShell
import desktopkit.shell.LocalNavKind
import desktopkit.shell.NavKind
import desktopkit.shell.ShellTransitions
import desktopkit.shell.WidthClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The whole app inside one window: the kit's root (theme, density, insets) and its shell (bottom
 * bar, rail or sidebar by the window's width) around the screens.
 *
 * @param settings the app's own settings, shared by every window.
 * @param ui what this window shows of the mail (the open messages, the filters); the activity keeps it.
 * @param startRoute where a window opened from another one starts.
 * @param clock the time the lists are dated against; the device's own unless a test pins one.
 * @param transitions how the shell moves between screens; tests turn it off.
 * @param messageBody draws a message's body instead of the WebView: the plain text where there is no
 *   WebView to draw with (the screenshot tests).
 */
@Composable
fun DeskMailApp(
    mail: DemoMail,
    settings: AppSettings,
    ui: MailUiState = remember { MailUiState() },
    startRoute: String? = null,
    prefs: DeskPreferences = rememberDeskPreferences(),
    clock: MailClock = remember { MailClock() },
    transitions: ShellTransitions = ShellTransitions.Default,
    messageBody: (@Composable (MailMessage, Modifier) -> Unit)? = null,
) {
    val navController = rememberNavController()
    val resources = appResources()
    val locales = LocalConfiguration.current.locales
    val scope = rememberCoroutineScope()
    val toasts = rememberToastState()
    val accounts = mail.accounts
    val folders = mail.folders
    // The kit builds its navigation graph from the spec: a new one only when the folders themselves
    // change, not with every message that is read (the counts are the badges' business).
    val tree = folders.map { it.copy(unread = 0, total = 0) }
    val spec = remember(locales, accounts, tree, startRoute) { buildShellSpec(resources, accounts, tree, startRoute) }
    val badges = remember(folders) { navBadges(folders) }
    // a command this pre-release has a place for, but not yet the work behind it
    val notYet: (String) -> Unit = { name -> scope.launch { toasts.show(resources.getString(R.string.not_in_this_version, name)) } }
    val newMessage = stringResource(R.string.action_new_message)
    // a tapped link is never followed inside a message: its real address is shown instead
    val showLink: (String) -> Unit = { url -> scope.launch { toasts.show(url.take(LINK_LENGTH), duration = ToastDuration.Long) } }
    val body: @Composable (MailMessage, Modifier) -> Unit = messageBody ?: { message, modifier ->
        WebMessageBody(message, modifier, darken = settings.darkenMessages, layer = settings.webLayer, onLink = showLink)
    }
    // the width the list was dragged to outlives the window
    LaunchedEffect(ui, settings) {
        snapshotFlow { ui.listSplit.startWidthDp }.drop(1).collectLatest { width ->
            delay(SAVE_AFTER_MS)
            settings.updateListWidth(width)
        }
    }
    // the phone's "⋯" sheet: the experiments one tap away from an open message
    val experiments = ActionGroup(
        stringResource(R.string.settings_experiments),
        listOf(
            MenuEntry.Item(stringResource(R.string.experiment_glass), { settings.updateGlass(!settings.glass) }, checked = settings.glass),
            MenuEntry.Item(
                stringResource(R.string.experiment_under_bars),
                { settings.updateReaderUnderBars(!settings.readerUnderBars) },
                checked = settings.readerUnderBars,
            ),
            MenuEntry.Item(
                stringResource(R.string.experiment_darken),
                { settings.updateDarkenMessages(!settings.darkenMessages) },
                checked = settings.darkenMessages,
            ),
        ),
    )

    CompositionLocalProvider(LocalMailClock provides clock) {
        DesktopKitRoot(prefs) { widthClass ->
            DesktopShell(
                spec = spec,
                widthClass = widthClass,
                navController = navController,
                sidebarCollapsed = prefs.sidebarCollapsed,
                onSidebarCollapsedChange = prefs::updateSidebarCollapsed,
                badges = badges,
                transitions = transitions,
                moreActions = listOf(experiments),
                glass = settings.glass,
                toasts = toasts,
            ) { route, navigator ->
                val navKind = LocalNavKind.current
                val view = mailView(route)
                // a folder is no tab of the phone's bottom bar: it was opened from the Folders list, or from "⋯"
                val backFromFolder: (() -> Unit)? =
                    if (widthClass == WidthClass.Compact && view is MailView.Folder) ({ navController.popBackStack() }) else null
                when {
                    view != null -> MailScreen(
                        route = route,
                        view = view,
                        title = spec.item(route)?.label.orEmpty(),
                        mail = mail,
                        ui = ui,
                        prefs = prefs,
                        onBack = backFromFolder,
                        onCompose = { notYet(newMessage) },
                        onNotYet = notYet,
                        readerUnderBars = settings.readerUnderBars,
                        body = body,
                    )
                    route == Routes.FOLDERS -> FoldersScreen(
                        accounts = accounts,
                        folders = folders,
                        onOpen = { folder ->
                            val target = Routes.folder(folder.id)
                            // The sidebar lists the folders itself and switches between them. On a phone or beside a
                            // rail the folder opens on top of this list, and Back returns here.
                            if (navKind == NavKind.Sidebar) navigator.navigate(target) else navController.navigate(target) { launchSingleTop = true }
                        },
                    )
                    route == Routes.SETTINGS -> SettingsScreen(prefs, settings)
                    else -> MessageScreen(
                        title = stringResource(R.string.search_coming_title),
                        icon = DeskIcons.Search,
                        pageTitle = stringResource(R.string.nav_search),
                        description = stringResource(R.string.search_coming_text),
                    )
                }
            }
        }
    }
}

/** A link's address is shown whole up to this many characters. */
private const val LINK_LENGTH = 300

/** A dragged width is stored once the drag has rested this long. */
private const val SAVE_AFTER_MS = 500L

/** The mail a route lists, or null for a screen that is not a list of messages. */
fun mailView(route: String): MailView? = when (route) {
    Routes.INBOX -> MailView.Inbox
    Routes.UNREAD -> MailView.Unread
    Routes.STARRED -> MailView.Starred
    else -> Routes.folderId(route)?.let { MailView.Folder(it) }
}
