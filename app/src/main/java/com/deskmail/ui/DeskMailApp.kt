package com.deskmail.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import com.deskmail.core.text.SearchQuery
import com.deskmail.mail.AccountActions
import com.deskmail.mail.DraftKind
import com.deskmail.mail.FetchedFile
import com.deskmail.mail.MailAccount
import com.deskmail.mail.MailComposer
import com.deskmail.mail.MailContent
import com.deskmail.mail.MailSource
import com.deskmail.mail.MailView
import com.deskmail.notify.NotifyGate
import com.deskmail.notify.OpenRequest
import com.deskmail.settings.AppSettings
import com.deskmail.ui.compose.ComposeActions
import com.deskmail.ui.compose.ComposeScreen
import com.deskmail.ui.compose.OutboxScreen
import com.deskmail.ui.compose.WriteApart
import com.deskmail.ui.mail.FoldersScreen
import com.deskmail.ui.mail.LocalMailClock
import com.deskmail.ui.mail.MESSAGE_DRAG
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
import desktopkit.shell.NavDrop
import desktopkit.shell.NavKind
import desktopkit.shell.ShellNavigator
import desktopkit.shell.ShellTransitions
import desktopkit.shell.WidthClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

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
 * @param notifications the system's leave to show notifications, and the way to ask for it; null
 *   where there is nobody to ask (the tests).
 * @param writeApart writes messages in windows of their own where this window is one of a
 *   desktop's (DeX); null where everything is written in this window.
 * @param onFrontList told which list of mail the window shows (null for none): new mail that
 *   arrives in it needs no notification.
 * @param onCheckChosen the user chose how often the mail is looked for in the background.
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
    notifications: NotifyGate? = null,
    writeApart: WriteApart? = null,
    onFrontList: ((MailView?) -> Unit)? = null,
    onCheckChosen: () -> Unit = {},
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
    val saved = mail.savedSearches
    val spec = remember(locales, named, tree, startRoute, saved) { buildShellSpec(resources, named, tree, startRoute, saved) }
    val waiting = composer?.outbox?.size ?: 0
    val badges = remember(folders, waiting) { navBadges(folders, waiting) }
    val entry by navController.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route ?: spec.startRoute

    // A new set of routes (a folder made on the server, an account added or removed) keeps the open
    // screen: the kit adds them to its navigation graph (desktop-kit 2.8.0; docs/KIT-ISTEKLERI.md, 3).
    val routes = remember(spec) { spec.items.map { it.route } }

    val notify: (String) -> Unit = { text -> scope.launch { toasts.show(text) } }
    // a tapped link is never followed inside a message: its real address is shown instead
    val showLink: (String) -> Unit = { url -> scope.launch { toasts.show(url.take(LINK_LENGTH), duration = ToastDuration.Long) } }
    val body: @Composable (MailContent.Ready, Boolean, Modifier) -> Unit = messageBody ?: { content, showImages, modifier ->
        // counted while it is on screen: what the shell's glass goes by (shellGlass)
        WebBodyPresence(ui)
        WebMessageBody(
            content,
            modifier,
            showImages = showImages,
            darken = settings.darkenMessages,
            onLink = showLink,
            // a picture of the message's own: from the device, or from its server (a WebView thread waits for it)
            picture = { part -> runBlocking { withTimeoutOrNull(PICTURE_WAIT_MS) { (mail.fetch(part) as? FetchedFile.Ready)?.file } } },
        )
    }
    // archive, move and delete, with their notices and the way back; the files of messages
    val actions = remember(mail, ui, resources) { MailActions(mail, ui, scope, toasts, resources) }
    // Messages dragged out of a list are moved to the folder of the sidebar they are dropped on.
    // (To a folder of their own account: what cannot go there stays where it is, and that is said.)
    val folderDrop = remember(routes, actions) {
        NavDrop(
            kinds = routes.filter { Routes.folderId(it) != null }.associateWith { setOf(MESSAGE_DRAG) },
            onDrop = { route, payload ->
                val folderId = Routes.folderId(route)
                val ids = payload.keys.mapNotNull { it.toLongOrNull() }
                if (folderId == null || ids.isEmpty()) {
                    false
                } else {
                    actions.move(ids, folderId)
                    true
                }
            },
        )
    }
    val files = rememberAttachmentHandler(mail, notify)
    // writing: a new message, a reply, what the outbox holds, and "Send" with its way back
    val compose = remember(composer, mail, ui, resources, writeApart) {
        composer?.let { ComposeActions(it, mail, ui, scope, toasts, resources, writeApart) }
    }
    SideEffect { compose?.front = { navController.currentBackStackEntry?.destination?.route ?: spec.startRoute } }
    // what a window of its own did with its message is told here
    DisposableEffect(compose, writeApart) {
        writeApart?.home(compose)
        onDispose { writeApart?.home(null) }
    }
    // "Add an account", from wherever it is asked for: the page in Settings, with nothing typed
    val addAccount: () -> Unit = {
        settingsUi.addAccount()
        navigator.navigate(Routes.SETTINGS)
    }
    val openAccount: (Long) -> Unit = { id ->
        settingsUi.page = SettingsPage.Account(id)
        navigator.navigate(Routes.SETTINGS)
    }
    // A notification was tapped: its message opens in the inbox (a reminder's, among those an
    // answer is waited for), in front of whatever the window showed.
    val request = ui.openRequest
    LaunchedEffect(request) {
        if (request != null) {
            ui.openRequest = null
            val target = if (request.place == OpenRequest.Place.Awaiting) Routes.AWAITING else Routes.INBOX
            if (ui.writingOver(target) != null) compose?.close()
            val here = navController.currentBackStackEntry?.destination?.route
            if (here != null && here != target) navigator.navigate(target)
            if (request.messageId > 0) openMessage(mail, ui, target, request.messageId)
        }
    }
    // Another app asked for a message to be written (a mailto link, something shared): it is
    // begun over the screen in front.
    val wanted = ui.composeRequest
    LaunchedEffect(wanted) {
        if (wanted != null) {
            ui.composeRequest = null
            compose?.begin(navController.currentBackStackEntry?.destination?.route ?: spec.startRoute, wanted)
        }
    }
    // New mail can only be told of with the user's leave. It is asked for once, unasked: when
    // there is an account whose mail could arrive.
    val hasAccounts = mail.loaded && mail.accounts.isNotEmpty()
    LaunchedEffect(hasAccounts, notifications) {
        if (hasAccounts && notifications != null && !notifications.allowed && !notifications.asked) notifications.ask()
    }
    // Mail is told of as it comes only where the system leaves the app alone in the background.
    // The user is led to that setting once, unasked: when there is an account, mail is to be
    // told of as it comes, and the question about notifications is behind them.
    if (hasAccounts && notifications != null && settings.checkMinutes == AppSettings.CHECK_INSTANT &&
        !notifications.unrestricted && !notifications.batteryAsked && (notifications.allowed || notifications.asked)
    ) {
        BatteryQuestion(onAllow = notifications.askBattery, onLater = notifications.skipBattery)
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
    // What a command is done to: several selected messages, or the open one.
    val withTargets: (String, (List<Long>) -> Unit) -> Boolean = { route, action ->
        val ids = ui.targets(route)
        if (ids.isNotEmpty()) action(ids)
        ids.isNotEmpty()
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
            CommonShortcuts.SEARCH -> {
                palette.open()
                true
            }
            // "/": the search screen, with its field ready to be typed into
            Commands.FIND -> {
                ui.searchFocusAsked = true
                navigator.navigate(Routes.SEARCH)
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
                mail.refresh(mailView(route, ui.searchQuery))
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
                Commands.STAR -> withTargets(route) { ids ->
                    if (ids.size == 1) mail.toggleStarred(ids.first()) else mail.markStarred(ids, starred = !ids.all { mail.cachedMessage(it)?.starred == true })
                }
                Commands.TOGGLE_READ -> withTargets(route) { ids ->
                    if (ids.size == 1) mail.toggleUnread(ids.first()) else mail.markUnread(ids, unread = ids.none { mail.cachedMessage(it)?.unread == true })
                }
                Commands.REPLY -> compose != null && withOpen(route) { compose.begin(route, DraftKind.Reply, it) }
                Commands.REPLY_ALL -> compose != null && withOpen(route) { compose.begin(route, DraftKind.ReplyAll, it) }
                Commands.FORWARD -> compose != null && withOpen(route) { compose.begin(route, DraftKind.Forward, it) }
                Commands.ARCHIVE -> withTargets(route) { actions.archive(it) }
                Commands.DELETE -> withTargets(route) { actions.delete(it) }
                Commands.MOVE -> withTargets(route) { ids ->
                    val anchor = ids.firstNotNullOfOrNull { mail.cachedMessage(it) }
                    if (anchor != null) {
                        if (ids.size == 1) ui.moving = anchor else ui.moveSeveral(anchor, ids)
                    }
                }
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
    val listInFront = !writingInFront && mailView(currentRoute, ui.searchQuery) != null
    // which list the window shows, for whoever tells of new mail
    val frontList = if (writingInFront) null else mailView(currentRoute, ui.searchQuery)
    LaunchedEffect(frontList) { onFrontList?.invoke(frontList) }
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
    // A screen's name: a folder's with the folders it is in ("Projeler / 2026"), where the sidebar
    // shows that by indenting it.
    val screenTitle: (String) -> String = { route ->
        Routes.folderId(route)?.let { mail.folder(it) }?.let { folderPathLabel(resources, it) } ?: spec.item(route)?.label.orEmpty()
    }
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
                navDrop = folderDrop,
                menus = menus,
                onSearch = { palette.open() },
                searchHint = stringResource(R.string.palette_hint),
                transitions = transitions,
                moreActions = listOf(ActionGroup(stringResource(R.string.settings_experiments), experiments)),
                glass = shellGlass(settings.glass, settings.glassOverMessages, messageOpen, ui.webBodies),
                toasts = toasts,
                overlays = {
                    // Ctrl+K: screens and folders, the messages, and every command of the shortcut table
                    CommandPalette(
                        state = palette,
                        items = { typed ->
                            // what is typed, looked for among the messages on the search screen
                            val wanted = typed.trim()
                            val search = if (SearchQuery.match(wanted) == null) emptyList() else listOf(
                                // found by what is typed, under any title that matches: the last resort, not the first
                                PaletteItem(
                                    id = "search",
                                    title = resources.getString(R.string.palette_search_mail),
                                    group = messagesGroup,
                                    onSelect = {
                                        navigator.navigate(Routes.SEARCH)
                                        ui.search(wanted)
                                    },
                                    subtitle = "“$wanted”",
                                    icon = DeskIcons.Search,
                                ),
                            )
                            val screens = spec.items.map { item ->
                                PaletteItem(
                                    id = item.route,
                                    title = screenTitle(item.route),
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
                            search + screens + messages + shortcuts.paletteItems(resources) { id -> perform(id) }
                        },
                        filters = listOf(screensGroup, messagesGroup),
                        hint = stringResource(R.string.palette_hint),
                    )
                },
            ) { route, _ ->
                val navKind = LocalNavKind.current
                val view = mailView(route, ui.searchQuery)
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
                        title = screenTitle(route),
                        mail = mail,
                        ui = ui,
                        prefs = prefs,
                        onCompose = { perform(Commands.NEW_MESSAGE) },
                        onWrite = compose?.let { writer -> { kind, message -> writer.begin(route, kind, message.id) } },
                        actions = actions,
                        files = files,
                        onAddAccount = addAccount,
                        onOpenAccount = openAccount,
                        readerUnderBars = settings.readerUnderBars,
                        body = body,
                        resumes = resumes,
                        onNotify = notify,
                        conversations = settings.conversations,
                    )
                    route == Routes.FOLDERS -> FoldersScreen(
                        accounts = mail.accounts,
                        folders = folders,
                        onAddAccount = if (mail.loaded && mail.accounts.isEmpty()) addAccount else null,
                        onOpen = { folder ->
                            val target = Routes.folder(folder.id)
                            // The sidebar lists the folders itself and switches between them. On a phone or beside a
                            // rail the folder opens over this list as a page of it: its top bar has a Back button,
                            // Back returns here, and the Folders entry stays marked (desktop-kit 2.8.0).
                            if (navKind == NavKind.Sidebar) navigator.navigate(target) else navigator.open(target)
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
                        composer = composer,
                        notifications = notifications,
                        onCheckChosen = onCheckChosen,
                        onAccountAdded = { address ->
                            // To the inbox, where its mail is arriving. The account's folders change the
                            // set of routes a moment later, and the inbox stays open (desktop-kit 2.8.0).
                            settingsUi.close()
                            notify(resources.getString(R.string.setup_added, address))
                            navigator.navigate(Routes.INBOX)
                        },
                    )
                    route == Routes.OUTBOX && composer != null && compose != null -> OutboxScreen(
                        composer = composer,
                        onOpen = { draft -> compose.takeUp(route, draft) },
                        onDelete = { draft -> compose.discard(draft) },
                    )
                    // the outbox where nothing can be written: there is nothing in it
                    else -> MessageScreen(
                        title = stringResource(R.string.outbox_empty_title),
                        icon = MailIcons.Sent,
                        pageTitle = stringResource(R.string.nav_outbox),
                        description = stringResource(R.string.outbox_empty_text),
                    )
                }
            }
        }
    }
}

/** A link's address is shown whole up to this many characters. */
private const val LINK_LENGTH = 300

/** How long a message's page waits for one of its own pictures to come from the server; then it goes without. */
private const val PICTURE_WAIT_MS = 30_000L

/** A dragged divider is stored once the drag has rested this long. */
private const val SAVE_AFTER_MS = 500L

/**
 * The mail a route lists, or null for a screen that is not a list of messages. The search screen
 * lists what is found for [search], the window's query.
 */
fun mailView(route: String, search: String = ""): MailView? = when (route) {
    Routes.INBOX -> MailView.Inbox
    Routes.UNREAD -> MailView.Unread
    Routes.STARRED -> MailView.Starred
    Routes.VIP -> MailView.Vip
    Routes.AWAITING -> MailView.Awaiting
    Routes.SNOOZED -> MailView.Snoozed
    Routes.SEARCH -> MailView.Search(search)
    else -> Routes.folderId(route)?.let { MailView.Folder(it) } ?: Routes.savedId(route)?.let { MailView.Saved(it) }
}
