package com.deskmail.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.deskmail.R
import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.FolderRole
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
import com.deskmail.ui.mail.counterpart
import com.deskmail.ui.mail.openMessage
import com.deskmail.ui.mail.rememberDateLabels
import com.deskmail.ui.settings.SettingsScreen
import desktopkit.DesktopKitRoot
import desktopkit.appResources
import desktopkit.components.ActionGroup
import desktopkit.components.CommandPalette
import desktopkit.components.CommandPaletteState
import desktopkit.components.DeskIcons
import desktopkit.components.MenuEntry
import desktopkit.components.PaletteItem
import desktopkit.components.ToastDuration
import desktopkit.components.paletteItems
import desktopkit.components.rememberToastState
import desktopkit.input.CommonShortcuts
import desktopkit.screens.MessageScreen
import desktopkit.settings.DeskPreferences
import desktopkit.settings.rememberDeskPreferences
import desktopkit.shell.DeskWindows
import desktopkit.shell.DesktopShell
import desktopkit.shell.LocalNavKind
import desktopkit.shell.NavKind
import desktopkit.shell.ShellNavigator
import desktopkit.shell.ShellTransitions
import desktopkit.shell.WidthClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The whole app inside one window: the kit's root (theme, density, insets) and its shell (bottom
 * bar, rail or sidebar by the window's width) around the screens, with the menu bar, the command
 * palette and the commands behind the shortcut table.
 *
 * @param settings the app's own settings, shared by every window.
 * @param log the app's log; Settings shows it.
 * @param ui what this window shows of the mail (the open messages, the filters); the activity keeps it.
 * @param startRoute where a window opened from another one starts.
 * @param clock the time the lists are dated against; the device's own unless a test pins one.
 * @param transitions how the shell moves between screens; tests turn it off.
 * @param palette the command palette's state. The activity holds it, to leave the keys alone while it is open.
 * @param commands where the activity finds the window's commands, for the keys it routes.
 * @param messageBody draws a message's body instead of the WebView: the plain text where there is no
 *   WebView to draw with (the screenshot tests).
 */
@Composable
fun DeskMailApp(
    mail: DemoMail,
    settings: AppSettings,
    log: AppLog,
    ui: MailUiState = remember { MailUiState() },
    startRoute: String? = null,
    prefs: DeskPreferences = rememberDeskPreferences(),
    clock: MailClock = remember { MailClock() },
    transitions: ShellTransitions = ShellTransitions.Default,
    palette: CommandPaletteState = remember { CommandPaletteState() },
    commands: CommandHost? = null,
    messageBody: (@Composable (MailMessage, Modifier) -> Unit)? = null,
) {
    val navController = rememberNavController()
    val navigator = remember(navController) { ShellNavigator(navController) }
    val activity = LocalActivity.current
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
    val entry by navController.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route ?: spec.startRoute

    val notify: (String) -> Unit = { text -> scope.launch { toasts.show(text) } }
    // a command this pre-release has a place for, but not yet the work behind it
    val notYet: (String) -> Unit = { name -> notify(resources.getString(R.string.not_in_this_version, name)) }
    val newMessage = stringResource(R.string.action_new_message)
    // a tapped link is never followed inside a message: its real address is shown instead
    val showLink: (String) -> Unit = { url -> scope.launch { toasts.show(url.take(LINK_LENGTH), duration = ToastDuration.Long) } }
    val body: @Composable (MailMessage, Modifier) -> Unit = messageBody ?: { message, modifier ->
        WebMessageBody(message, modifier, darken = settings.darkenMessages, layer = settings.webLayer, onLink = showLink)
    }
    // What the divider between list and reader was dragged to outlives the window: once a drag
    // has rested, the window's state takes it as the list's share and stores it.
    LaunchedEffect(ui) {
        snapshotFlow { ui.listSplit.startWidthDp }.drop(1).collectLatest {
            delay(SAVE_AFTER_MS)
            ui.noteDraggedList()
        }
    }

    // ------------------------------------------------------------------ commands

    // the open message of the screen in front, for the commands that act on it
    val withOpen: (String, (MailMessage) -> Unit) -> Boolean = { route, action ->
        val message = ui.openedId(route)?.let { mail.message(it) }
        if (message != null) action(message)
        message != null
    }
    val step: (String, Int) -> Boolean = { route, by ->
        val message = ui.neighbour(route, by)?.let { mail.message(it) }
        if (message != null) {
            openMessage(mail, ui, route, message)
            scope.launch { ui.reveal(route, message.id) }
        }
        message != null
    }
    val perform: (String) -> Boolean = { id ->
        // read when the command runs: the screen in front then
        val route = navController.currentBackStackEntry?.destination?.route ?: spec.startRoute
        when (id) {
            CommonShortcuts.SEARCH, Commands.FIND -> {
                palette.open()
                true
            }
            CommonShortcuts.NEW_WINDOW -> {
                if (activity != null) DeskWindows.openNewWindow(activity)
                activity != null
            }
            // the list of shortcuts is a section of Settings
            CommonShortcuts.SETTINGS, CommonShortcuts.SHORTCUT_LIST -> {
                navigator.navigate(Routes.SETTINGS)
                true
            }
            CommonShortcuts.TOGGLE_SIDEBAR -> {
                prefs.updateSidebarCollapsed(!prefs.sidebarCollapsed)
                true
            }
            Commands.NEW_MESSAGE -> {
                notYet(newMessage)
                true
            }
            Commands.NEXT -> step(route, 1)
            Commands.PREVIOUS -> step(route, -1)
            Commands.STAR -> withOpen(route) { mail.setStarred(it.id, !it.starred) }
            Commands.TOGGLE_READ -> withOpen(route) { mail.setUnread(it.id, !it.unread) }
            Commands.CLOSE -> withOpen(route) { ui.close(route) }
            else -> false
        }
    }
    SideEffect { commands?.perform = perform }

    // The experiments as checkable entries: in the phone's "⋯" sheet, one tap away from an open
    // message, and in the View menu of a large window.
    val experiments = listOf(
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
    )
    // The menu bar, from the shortcut table. What acts on a message is greyed out while none is
    // open, what steps through a list while the screen in front is not one.
    val listInFront = mailView(currentRoute) != null
    val messageOpen = ui.openedId(currentRoute) != null
    val menus = shortcuts.menus(
        resources = resources,
        perform = { perform(it) },
        enabled = { id ->
            when (id) {
                Commands.NEXT, Commands.PREVIOUS -> listInFront
                Commands.STAR, Commands.TOGGLE_READ, Commands.CLOSE -> messageOpen
                else -> true
            }
        },
        extra = { group -> if (group == CommonShortcuts.ViewGroup) listOf<MenuEntry>(MenuEntry.Separator) + experiments else emptyList() },
    )
    val screensGroup = stringResource(R.string.palette_group_screens)
    val messagesGroup = stringResource(R.string.palette_group_messages)

    CompositionLocalProvider(LocalMailClock provides clock) {
        val dates = rememberDateLabels()
        DesktopKitRoot(prefs) { widthClass ->
            // which layout a report is about: the window's size and what the shell made of it
            val configuration = LocalConfiguration.current
            LaunchedEffect(widthClass) {
                log.info("window", "$widthClass, ${configuration.screenWidthDp} x ${configuration.screenHeightDp} dp")
            }
            DesktopShell(
                spec = spec,
                widthClass = widthClass,
                navController = navController,
                sidebarCollapsed = prefs.sidebarCollapsed,
                onSidebarCollapsedChange = prefs::updateSidebarCollapsed,
                badges = badges,
                menus = menus,
                onSearch = { palette.open() },
                searchHint = stringResource(R.string.palette_hint),
                transitions = transitions,
                moreActions = listOf(ActionGroup(stringResource(R.string.settings_experiments), experiments)),
                glass = settings.glass,
                toasts = toasts,
                overlays = {
                    // Ctrl+K: screens and folders, the messages, and every command of the shortcut table
                    CommandPalette(
                        state = palette,
                        items = {
                            val screens = spec.items.map { item ->
                                PaletteItem(
                                    id = item.route,
                                    title = item.label,
                                    group = screensGroup,
                                    onSelect = { navigator.navigate(item.route) },
                                    subtitle = Routes.folderId(item.route)?.let { id -> mail.folder(id) }?.let { mail.account(it.accountId)?.name },
                                    icon = item.icon,
                                    // Ctrl+Enter: the screen in a window of its own (tablet and DeX)
                                    onOpenInNewWindow = if (widthClass == WidthClass.Compact || activity == null) null else {
                                        { DeskWindows.openNewWindow(activity, route = item.route) }
                                    },
                                )
                            }
                            // "All Mail" holds every message once more: one entry each is enough
                            val allMail = mail.folders.filter { it.role == FolderRole.All }.map { it.id }.toSet()
                            val messages = mail.messages.filter { it.folderId !in allMail }.map { message ->
                                PaletteItem(
                                    id = "message/" + message.id,
                                    title = message.subject,
                                    group = messagesGroup,
                                    onSelect = {
                                        val route = Routes.folder(message.folderId)
                                        navigator.navigate(route)
                                        openMessage(mail, ui, route, message)
                                    },
                                    subtitle = counterpart(message) + " · " + dates.short(message.sentAt),
                                    icon = MailIcons.Mail,
                                    keywords = listOf(message.fromName, message.fromAddress, message.to),
                                )
                            }
                            screens + messages + shortcuts.paletteItems(resources) { id -> perform(id) }
                        },
                        filters = listOf(screensGroup, messagesGroup),
                        hint = stringResource(R.string.palette_hint),
                    )
                },
            ) { route, _ ->
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
                        onCompose = { perform(Commands.NEW_MESSAGE) },
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
                    route == Routes.SETTINGS -> SettingsScreen(prefs, settings, log, onNotify = notify)
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

/** A dragged divider is stored once the drag has rested this long. */
private const val SAVE_AFTER_MS = 500L

/** The mail a route lists, or null for a screen that is not a list of messages. */
fun mailView(route: String): MailView? = when (route) {
    Routes.INBOX -> MailView.Inbox
    Routes.UNREAD -> MailView.Unread
    Routes.STARRED -> MailView.Starred
    else -> Routes.folderId(route)?.let { MailView.Folder(it) }
}
