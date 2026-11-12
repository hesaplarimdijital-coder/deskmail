package com.deskmail.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.deskmail.R
import com.deskmail.core.log.AppLog
import com.deskmail.core.mail.FolderRole
import com.deskmail.mail.AccountActions
import com.deskmail.mail.DraftKind
import com.deskmail.mail.MailAccount
import com.deskmail.mail.MailComposer
import com.deskmail.mail.MailContent
import com.deskmail.mail.MailSource
import com.deskmail.mail.MailView
import com.deskmail.settings.AppSettings
import com.deskmail.ui.compose.ComposeActions
import com.deskmail.ui.compose.ComposeScreen
import com.deskmail.ui.compose.OutboxScreen
import com.deskmail.ui.mail.FoldersScreen
import com.deskmail.ui.mail.LocalMailClock
import com.deskmail.ui.mail.MailActions
import com.deskmail.ui.mail.MailClock
import com.deskmail.ui.mail.MailScreen
import com.deskmail.ui.mail.MailUiState
import com.deskmail.ui.mail.WebBodyPresence
import com.deskmail.ui.mail.WebMessageBody
import com.deskmail.ui.mail.counterpart
import com.deskmail.ui.mail.openMessage
import com.deskmail.ui.mail.rememberAttachmentHandler
import com.deskmail.ui.mail.rememberDateLabels
import com.deskmail.ui.mail.shellGlass
import com.deskmail.ui.settings.SettingsPage
import com.deskmail.ui.settings.SettingsScreen
import com.deskmail.ui.settings.SettingsUiState
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
 * @param mail where the mail comes from: the accounts' in the app, made-up mail in the tests.
 * @param composer writes and sends messages; null where nothing can be written.
 * @param accounts adds, reconnects and removes accounts (Settings); null where nothing can.
 * @param settings the app's own settings, shared by every window.
 * @param log the app's log; Settings shows it.
 * @param ui what this window shows of the mail (the open messages, the filters); the activity keeps it.
 * @param settingsUi what this window's Settings screen shows; the activity keeps it too.
 * @param resumes how often the window came back to the front: each time is a reason to look for new mail.
 * @param startRoute where a window opened from another one starts.
 * @param clock the time the lists are dated against; the device's own unless a test pins one.
 * @param transitions how the shell moves between screens; tests turn it off.
 * @param palette the command palette's state. The activity holds it, to leave the keys alone while it is open.
 * @param commands where the activity finds the window's commands, for the keys it routes.
 * @param messageBody draws a message's body instead of the WebView: the plain text where there is no
 *   WebView to draw with (the screenshot tests). The flag says whether its remote images are shown.
 */
@Composable
fun DeskMailApp(
    mail: MailSource,
    settings: AppSettings,
    log: AppLog,
    composer: MailComposer? = null,
    accounts: AccountActions? = null,
    ui: MailUiState = remember { MailUiState() },
    settingsUi: SettingsUiState = remember { SettingsUiState() },
    resumes: Int = 0,
    startRoute: String? = null,
    prefs: DeskPreferences = rememberDeskPreferences(),
    clock: MailClock = remember { MailClock() },
    transitions: ShellTransitions = ShellTransitions.Default,
    palette: CommandPaletteState = remember { CommandPaletteState() },
    commands: CommandHost? = null,
    messageBody: (@Composable (MailContent.Ready, Boolean, Modifier) -> Unit)? = null,
) {
    val navController = rememberNavController()
    val navigator = remember(navController) { ShellNavigator(navController) }
    val activity = LocalActivity.current
    val resources = appResources()
    val locales = LocalConfiguration.current.locales
    val scope = rememberCoroutineScope()
    val toasts = rememberToastState()
    val folders = mail.folders
    // The kit builds its navigation graph from the spec: a new one only when the accounts' names or
    // the folders themselves change, not with every message that is read or every sync that ends
    // (the counts are the badges' business, how an account is doing the lists').
    val named = mail.accounts.map { MailAccount(it.id, it.name, it.address) }
    val tree = folders.map { it.copy(unread = 0, total = 0) }
    val spec = remember(locales, named, tree, startRoute) { buildShellSpec(resources, named, tree, startRoute) }
    val waiting = composer?.outbox?.size ?: 0
    val badges = remember(folders, waiting) { navBadges(folders, waiting) }
    val entry by navController.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route ?: spec.startRoute

    // A new set of routes (a folder made on the server, an account added or removed) makes the
    // kit build a new navigation graph, and the shell is back at its start screen
    // (docs/KIT-ISTEKLERI.md, 3). The window goes back to where it was, if that is still there.
    // While an account is being added the start screen is where to be: its mail arrives there.
    val routes = remember(spec) { spec.items.map { it.route } }
    var routesSeen by remember { mutableStateOf(routes) }
    var at by remember { mutableStateOf(spec.startRoute) }
    if (routesSeen == routes) SideEffect { at = currentRoute }
    LaunchedEffect(routes) {
        if (routesSeen != routes) {
            val back = at
            val here = navController.currentBackStackEntry?.destination?.route
            if (!settingsUi.setup.busy && back != here && back in routes) navigator.navigate(back)
            routesSeen = routes
        }
    }

    val notify: (String) -> Unit = { text -> scope.launch { toasts.show(text) } }
    // a tapped link is never followed inside a message: its real address is shown instead
    val showLink: (String) -> Unit = { url -> scope.launch { toasts.show(url.take(LINK_LENGTH), duration = ToastDuration.Long) } }
    val body: @Composable (MailContent.Ready, Boolean, Modifier) -> Unit = messageBody ?: { content, showImages, modifier ->
        // Counted while it is on screen, and whether it is too tall for a layer of its own: what
        // the shell's glass goes by (shellGlass).
        var tall by remember { mutableStateOf(false) }
        WebBodyPresence(ui, tall)
        WebMessageBody(
            content,
            modifier,
            showImages = showImages,
            darken = settings.darkenMessages,
            layer = settings.webLayer,
            onLink = showLink,
            onTall = { tall = it },
        )
    }
    // archive, move and delete, with their notices and the way back; the files of messages
    val actions = remember(mail, ui, resources) { MailActions(mail, ui, scope, toasts, resources) }
    val files = rememberAttachmentHandler(mail, notify)
    // writing: a new message, a reply, what the outbox holds, and "Send" with its way back
    val compose = remember(composer, mail, ui, resources) { composer?.let { ComposeActions(it, mail, ui, scope, toasts, resources) } }
    SideEffect { compose?.front = { navController.currentBackStackEntry?.destination?.route ?: spec.startRoute } }
    // "Add an account", from wherever it is asked for: the page in Settings, with nothing typed
    val addAccount: () -> Unit = {
        settingsUi.addAccount()
        navigator.navigate(Routes.SETTINGS)
    }
    val openAccount: (Long) -> Unit = { id ->
        settingsUi.page = SettingsPage.Account(id)
        navigator.navigate(Routes.SETTINGS)
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
    val withOpen: (String, (Long) -> Unit) -> Boolean = { route, action ->
        val id = ui.openedId(route)
        if (id != null) action(id)
        id != null
    }
    val step: (String, Int) -> Boolean = { route, by ->
        val id = ui.neighbour(route, by)
        if (id != null) {
            openMessage(mail, ui, route, id)
            scope.launch { ui.reveal(route, id) }
        }
        id != null
    }
    val perform: (String) -> Boolean = { id ->
        // read when the command runs: the screen in front then
        val route = navController.currentBackStackEntry?.destination?.route ?: spec.startRoute
        // a message is being written there: the list and the open message behind it are out of reach
        val writing = compose != null && ui.writingOver(route) != null
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
                compose?.begin(route)
                compose != null
            }
            Commands.REFRESH -> {
                mail.refresh(mailView(route))
                true
            }
            // the latest thing first: a message that was just handed over, then what was done to one
            Commands.UNDO -> compose?.undoSend() == true || (!writing && actions.undoLast())
            Commands.SEND -> {
                val send = if (writing) compose?.host?.send else null
                send?.invoke()
                send != null
            }
            Commands.CLOSE -> if (writing) {
                compose?.close()
                true
            } else {
                withOpen(route) { ui.close(route) }
            }
            else -> if (writing) false else when (id) {
                Commands.NEXT -> step(route, 1)
                Commands.PREVIOUS -> step(route, -1)
                Commands.STAR -> withOpen(route) { mail.toggleStarred(it) }
                Commands.TOGGLE_READ -> withOpen(route) { mail.toggleUnread(it) }
                Commands.REPLY -> compose != null && withOpen(route) { compose.begin(route, DraftKind.Reply, it) }
                Commands.REPLY_ALL -> compose != null && withOpen(route) { compose.begin(route, DraftKind.ReplyAll, it) }
                Commands.FORWARD -> compose != null && withOpen(route) { compose.begin(route, DraftKind.Forward, it) }
                Commands.ARCHIVE -> withOpen(route) { actions.archive(listOf(it)) }
                Commands.DELETE -> withOpen(route) { actions.delete(listOf(it)) }
                Commands.MOVE -> withOpen(route) { id -> mail.cachedMessage(id)?.let { ui.moving = it } }
                else -> false
            }
        }
    }
    SideEffect { commands?.perform = perform }

    // The experiments as checkable entries: in the phone's "⋯" sheet, one tap away from an open
    // message, and in the View menu of a large window.
    val experiments = listOf(
        MenuEntry.Item(stringResource(R.string.experiment_glass), { settings.updateGlass(!settings.glass) }, checked = settings.glass),
        MenuEntry.Item(
            stringResource(R.string.experiment_glass_over_messages),
            { settings.updateGlassOverMessages(!settings.glassOverMessages) },
            checked = settings.glassOverMessages,
        ),
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
    // open, what steps through a list while the screen in front is not one; while a message is
    // being written in front, the list and the open message behind it are out of reach.
    val writingInFront = compose != null && ui.writingOver(currentRoute) != null
    val listInFront = !writingInFront && mailView(currentRoute) != null
    val messageOpen = !writingInFront && ui.openedId(currentRoute) != null
    val menus = shortcuts.menus(
        resources = resources,
        perform = { perform(it) },
        enabled = { id ->
            when (id) {
                Commands.NEW_MESSAGE -> compose != null
                Commands.SEND -> writingInFront
                Commands.NEXT, Commands.PREVIOUS -> listInFront
                Commands.STAR, Commands.TOGGLE_READ, Commands.ARCHIVE, Commands.DELETE, Commands.MOVE -> messageOpen
                Commands.REPLY, Commands.REPLY_ALL, Commands.FORWARD -> messageOpen && compose != null
                Commands.CLOSE -> messageOpen || writingInFront
                Commands.UNDO -> compose?.canUndo == true || (!writingInFront && actions.canUndo)
                else -> true
            }
        },
        extra = { group -> if (group == CommonShortcuts.ViewGroup) listOf<MenuEntry>(MenuEntry.Separator) + experiments else emptyList() },
    )
    val screensGroup = stringResource(R.string.palette_group_screens)
    val messagesGroup = stringResource(R.string.palette_group_messages)
    val noSubject = stringResource(R.string.message_no_subject)

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
                glass = shellGlass(settings.glass, settings.glassOverMessages, messageOpen, ui.webBodies, ui.tallWebBodies),
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
                            // the newest ones, each once ("All Mail" holds every message once more)
                            val allMail = mail.folders.filter { it.role == FolderRole.All }.map { it.id }.toSet()
                            val messages = mail.recent.filter { it.folderId !in allMail }.map { message ->
                                PaletteItem(
                                    id = "message/" + message.id,
                                    title = message.subject.ifBlank { noSubject },
                                    group = messagesGroup,
                                    onSelect = {
                                        val route = Routes.folder(message.folderId)
                                        // the message is asked for: it is not left hidden behind one that is being written there
                                        if (ui.writingOver(route) != null) compose?.close()
                                        navigator.navigate(route)
                                        openMessage(mail, ui, route, message.id)
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
                val writing = ui.writingOver(route)
                when {
                    // a message is being written over this screen: it has the screen's place until it is sent or put away
                    writing != null && composer != null && compose != null -> key(writing) {
                        ComposeScreen(
                            writing = writing,
                            composer = composer,
                            mail = mail,
                            scope = scope,
                            host = compose.host,
                            onClose = { compose.close() },
                            onDiscard = { compose.discard(writing) },
                            onSent = { check -> compose.sent(writing, check) },
                            onNotify = notify,
                        )
                    }
                    view != null -> MailScreen(
                        route = route,
                        view = view,
                        title = spec.item(route)?.label.orEmpty(),
                        mail = mail,
                        ui = ui,
                        prefs = prefs,
                        onBack = backFromFolder,
                        onCompose = { perform(Commands.NEW_MESSAGE) },
                        onWrite = compose?.let { writer -> { kind, message -> writer.begin(route, kind, message.id) } },
                        actions = actions,
                        files = files,
                        onAddAccount = addAccount,
                        onOpenAccount = openAccount,
                        readerUnderBars = settings.readerUnderBars,
                        body = body,
                        resumes = resumes,
                    )
                    route == Routes.FOLDERS -> FoldersScreen(
                        accounts = mail.accounts,
                        folders = folders,
                        onAddAccount = if (mail.loaded && mail.accounts.isEmpty()) addAccount else null,
                        onOpen = { folder ->
                            val target = Routes.folder(folder.id)
                            // The sidebar lists the folders itself and switches between them. On a phone or beside a
                            // rail the folder opens on top of this list, and Back returns here.
                            if (navKind == NavKind.Sidebar) navigator.navigate(target) else navController.navigate(target) { launchSingleTop = true }
                        },
                    )
                    route == Routes.SETTINGS -> SettingsScreen(
                        prefs = prefs,
                        settings = settings,
                        log = log,
                        mail = mail,
                        accounts = accounts,
                        ui = settingsUi,
                        scope = scope,
                        onNotify = notify,
                        onAccountAdded = { address ->
                            // To the inbox, where its mail is arriving. Said to the route keeper as
                            // well: the account's folders are about to change the set of routes, and
                            // whichever happens first, the inbox is where the window is to be.
                            settingsUi.close()
                            notify(resources.getString(R.string.setup_added, address))
                            at = Routes.INBOX
                            navigator.navigate(Routes.INBOX)
                        },
                    )
                    route == Routes.OUTBOX && composer != null && compose != null -> OutboxScreen(
                        composer = composer,
                        onOpen = { draft -> compose.takeUp(route, draft) },
                        onDelete = { draft -> compose.discard(draft) },
                    )
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
