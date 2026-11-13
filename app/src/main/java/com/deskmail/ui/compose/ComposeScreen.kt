package com.deskmail.ui.compose

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.deskmail.R
import com.deskmail.mail.Draft
import com.deskmail.mail.DraftFile
import com.deskmail.mail.DraftState
import com.deskmail.mail.MailComposer
import com.deskmail.mail.MailSource
import com.deskmail.mail.SendCheck
import com.deskmail.ui.MailIcons
import desktopkit.appResources
import desktopkit.components.ActionGroup
import desktopkit.components.DeskButton
import desktopkit.components.DeskChip
import desktopkit.components.DeskIconButton
import desktopkit.components.DeskIcons
import desktopkit.components.DeskTextField
import desktopkit.components.MenuEntry
import desktopkit.components.MoreSheet
import desktopkit.components.SegmentedChoice
import desktopkit.components.SettingRow
import desktopkit.screens.PageHeader
import desktopkit.shell.CompactTopBar
import desktopkit.shell.TopBarAction
import desktopkit.shell.TopBarBackIcon
import desktopkit.shell.WidthClass
import desktopkit.shell.currentWidthClass
import desktopkit.shell.keepsKeyboardOpen
import desktopkit.shell.rememberShellContentPadding
import desktopkit.theme.DeskTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A message of the user's own that is being written in a window: which one (its id in the
 * outbox), over which of the window's screens it was begun, and what is typed. The window keeps
 * it, not the screen: what is typed is still there when the shell lays its screens out anew.
 *
 * Writing goes on over the screen it was begun on. Another screen of the window shows what it
 * always shows; back on this one, the message is still being written.
 *
 * @param fresh the message was begun just now, for this: closed with nothing written into it, it
 *   is not worth keeping.
 */
@Stable
class Writing(val draftId: Long, val route: String, val fresh: Boolean = false) {
    val fields = ComposeFields()
}

/**
 * What is typed into the compose screen. It is read once from the draft as it is kept, and the
 * draft follows it from then on.
 */
@Stable
class ComposeFields {
    var loaded by mutableStateOf(false)
        private set
    var accountId by mutableLongStateOf(0)
    var to by mutableStateOf("")
    var cc by mutableStateOf("")
    var bcc by mutableStateOf("")
    var subject by mutableStateOf("")
    var body by mutableStateOf("")

    /** Cc and Bcc are shown once there is something in them, or once they were asked for. */
    var moreRecipients by mutableStateOf(false)

    /** What "Send" found wrong with the recipients, to say under the field. */
    var recipientsError by mutableStateOf<String?>(null)

    /** What the message held when it was read, and whether a file was added or taken out since. */
    private var asRead: List<Any> = emptyList()
    private var filesChanged = false

    /** Whether anything was written, attached or taken out since the message was read. */
    val touched: Boolean get() = loaded && (filesChanged || typed() != asRead)

    fun load(draft: Draft) {
        accountId = draft.accountId
        to = draft.to
        cc = draft.cc
        bcc = draft.bcc
        subject = draft.subject
        body = draft.body
        moreRecipients = draft.cc.isNotBlank() || draft.bcc.isNotBlank()
        asRead = typed()
        loaded = true
    }

    fun fileChanged() {
        filesChanged = true
    }

    /** Everything that is kept, as one value: what changes when something was typed. */
    fun typed(): List<Any> = listOf(accountId, to, cc, bcc, subject, body)

    /** The message was thrown away: there is nothing here to keep any more. */
    fun discarded() {
        loaded = false
    }
}

/** What a window's compose screen can be asked from outside: Ctrl+Enter sends. */
@Stable
class ComposeHost {
    var send: (() -> Unit)? = null
}

/** Names the tests find the fields by. */
object ComposeTags {
    const val TO = "compose-to"
    const val CC = "compose-cc"
    const val BCC = "compose-bcc"
    const val SUBJECT = "compose-subject"
    const val BODY = "compose-body"
}

/**
 * Writing a message: who it is from (where there is more than one account), who it goes to, the
 * subject, the files and the text. What is typed is kept as a draft while it is typed, so closing
 * the screen loses nothing; "Send" hands the message over and closes it.
 *
 * On a phone the actions are in the top bar, with a cross that closes; wider windows have them in
 * a row above the fields.
 *
 * @param writing which message, and what is typed into it; the window keeps it.
 * @param scope where the work the buttons start runs: the window's.
 * @param host where the window finds this screen's "Send" (Ctrl+Enter).
 * @param onClose the screen goes; the draft stays in the outbox.
 * @param onDiscard the message is to be thrown away, and the screen with it.
 * @param onSent the message was handed over and waits out its time to be taken back.
 * @param onNotify shows a short notice.
 */
@Composable
fun ComposeScreen(
    writing: Writing,
    composer: MailComposer,
    mail: MailSource,
    scope: CoroutineScope,
    host: ComposeHost,
    onClose: () -> Unit,
    onDiscard: () -> Unit,
    onSent: (SendCheck.Queued) -> Unit,
    onNotify: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val resources = appResources()
    val context = LocalContext.current
    val compact = currentWidthClass() == WidthClass.Compact
    val draftId = writing.draftId
    val fields = writing.fields
    // what the phone's bars cover: asked for here, so the page scrolls beneath them
    val barsPadding = rememberShellContentPadding()
    val draft by remember(composer, draftId) { composer.observe(draftId) }.collectAsState(initial = null)

    // read once; after that the screen is what is typed, and the draft follows it
    LaunchedEffect(draft) {
        val kept = draft
        when {
            // sent from another window, or thrown away: there is nothing left to write on
            kept == null -> if (fields.loaded) onClose()
            !kept.canBeWrittenOn() -> onClose()
            !fields.loaded -> fields.load(kept)
        }
    }
    // kept a moment after the typing pauses
    LaunchedEffect(fields.typed()) {
        delay(KEEP_AFTER_MS)
        if (fields.loaded) composer.save(draftId, fields.accountId, fields.to, fields.cc, fields.bcc, fields.subject, fields.body)
    }
    // and once more when the screen goes, by the composer itself: the window may be going with it
    DisposableEffect(composer, writing) {
        onDispose {
            if (fields.loaded) composer.keep(draftId, fields.accountId, fields.to, fields.cc, fields.bcc, fields.subject, fields.body)
        }
    }

    val send: () -> Unit = {
        scope.launch {
            if (!fields.loaded) return@launch
            // what is typed first, and waited for: the message goes as it stands on the screen
            composer.save(draftId, fields.accountId, fields.to, fields.cc, fields.bcc, fields.subject, fields.body)
            when (val check = composer.send(draftId)) {
                is SendCheck.Queued -> onSent(check)
                SendCheck.NoRecipients -> fields.recipientsError = resources.getString(R.string.compose_no_recipients)
                is SendCheck.BadAddresses -> fields.recipientsError = resources.getString(R.string.compose_bad_addresses, check.pieces.joinToString(", "))
                SendCheck.Gone -> onClose()
            }
        }
    }
    SideEffect { host.send = send }
    DisposableEffect(host) {
        onDispose { host.send = null }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = if (result.resultCode == Activity.RESULT_OK) result.data else null
        val chosen = buildList {
            data?.data?.let { add(it) }
            val clip = data?.clipData
            if (clip != null) for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let { add(it) }
        }.distinct()
        if (chosen.isNotEmpty()) {
            scope.launch {
                var failed = false
                for (uri in chosen) {
                    val (name, type) = describe(context, uri)
                    if (composer.attach(draftId, name, type) { context.contentResolver.openInputStream(uri) }) fields.fileChanged() else failed = true
                }
                if (failed) onNotify(resources.getString(R.string.compose_attach_failed))
            }
        }
    }
    val attach: () -> Unit = {
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            pick.launch(open)
        } catch (e: android.content.ActivityNotFoundException) {
            onNotify(resources.getString(R.string.compose_attach_failed))
        }
    }

    val title = fields.subject.ifBlank { stringResource(R.string.compose_title) }
    val sendLabel = stringResource(R.string.action_send)
    val attachLabel = stringResource(R.string.action_attach)
    val discardLabel = stringResource(R.string.action_discard)
    val closeLabel = stringResource(R.string.action_close_compose)
    // a phone: the cross, and the actions in the top bar (nothing happens on wider windows)
    CompactTopBar(
        title = title,
        onBack = onClose,
        backIcon = TopBarBackIcon.Close,
        actions = listOf(
            TopBarAction(MailIcons.Sent, sendLabel, send, primary = true),
            TopBarAction(MailIcons.Attachment, attachLabel, attach),
            TopBarAction(DeskIcons.Delete, discardLabel, onDiscard),
        ),
        showSearch = false,
    )
    // where the top bar has no back, Back still only closes the screen
    BackHandler(enabled = !compact) { onClose() }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(barsPadding),
    ) {
        // a large window names the screen here; its actions stand with the form, not at the far end of the window
        if (!compact) PageHeader(title)
        Column(
            // the limit first: asked to fill before it, the column would fill the window whatever the limit
            Modifier.widthIn(max = MAX_WIDTH.dp).fillMaxWidth().padding(horizontal = dimens.pagePadding).padding(top = dimens.gapS, bottom = dimens.gapXl),
            verticalArrangement = Arrangement.spacedBy(dimens.gapM),
        ) {
            if (!compact) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dimens.gapS), verticalAlignment = Alignment.CenterVertically) {
                    DeskButton(sendLabel, send, Modifier.keepsKeyboardOpen(), icon = MailIcons.Sent, primary = true)
                    DeskButton(attachLabel, attach, icon = MailIcons.Attachment)
                    Spacer(Modifier.weight(1f))
                    DeskIconButton(DeskIcons.Delete, discardLabel, onDiscard)
                    DeskIconButton(DeskIcons.Close, closeLabel, onClose)
                }
            }
            // who it is from, where there is a choice
            val accounts = mail.accounts
            if (accounts.size > 1) {
                SettingRow(stringResource(R.string.compose_from), Modifier.keepsKeyboardOpen()) {
                    SegmentedChoice(
                        options = accounts.map { it.id to it.name },
                        selected = fields.accountId,
                        onSelect = { fields.accountId = it },
                    )
                }
            }
            val recipientsHint = stringResource(R.string.compose_recipients_hint)
            DeskTextField(
                value = fields.to,
                onValueChange = {
                    fields.to = it
                    fields.recipientsError = null
                },
                modifier = Modifier.fillMaxWidth().testTag(ComposeTags.TO),
                hint = recipientsHint,
                label = stringResource(R.string.compose_to),
                error = fields.recipientsError != null,
                supportingText = fields.recipientsError,
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            )
            if (fields.moreRecipients) {
                DeskTextField(
                    value = fields.cc,
                    onValueChange = {
                        fields.cc = it
                        fields.recipientsError = null
                    },
                    modifier = Modifier.fillMaxWidth().testTag(ComposeTags.CC),
                    hint = recipientsHint,
                    label = stringResource(R.string.compose_cc),
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                )
                DeskTextField(
                    value = fields.bcc,
                    onValueChange = {
                        fields.bcc = it
                        fields.recipientsError = null
                    },
                    modifier = Modifier.fillMaxWidth().testTag(ComposeTags.BCC),
                    hint = recipientsHint,
                    label = stringResource(R.string.compose_bcc),
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                )
            } else {
                DeskButton(stringResource(R.string.compose_more_recipients), { fields.moreRecipients = true }, Modifier.keepsKeyboardOpen(), icon = DeskIcons.Plus)
            }
            DeskTextField(
                value = fields.subject,
                onValueChange = { fields.subject = it },
                modifier = Modifier.fillMaxWidth().testTag(ComposeTags.SUBJECT),
                label = stringResource(R.string.compose_subject),
                imeAction = ImeAction.Next,
            )
            val files = draft?.files.orEmpty()
            if (files.isNotEmpty()) {
                DraftFiles(files, context) { file ->
                    fields.fileChanged()
                    scope.launch { composer.detach(file.id) }
                }
            }
            DeskTextField(
                value = fields.body,
                onValueChange = { fields.body = it },
                modifier = Modifier.fillMaxWidth().testTag(ComposeTags.BODY),
                hint = stringResource(R.string.compose_body_hint),
                label = stringResource(R.string.compose_body),
                singleLine = false,
                minLines = BODY_LINES,
                maxLines = Int.MAX_VALUE,
            )
        }
    }
}

/** The files that go with the message, as chips on one line that scrolls sideways; a chip offers to take its file out. */
@Composable
private fun DraftFiles(files: List<DraftFile>, context: Context, onRemove: (DraftFile) -> Unit) {
    val dimens = DeskTheme.dimens
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).keepsKeyboardOpen(),
        horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        files.forEach { file ->
            // a chip is its file's: taking one out leaves the others as they are
            key(file.id) {
                var open by remember { mutableStateOf(false) }
                var height by remember { mutableIntStateOf(0) }
                val remove = MenuEntry.Item(stringResource(R.string.compose_file_remove), { onRemove(file) }, icon = DeskIcons.Delete, destructive = true)
                Box(Modifier.onSizeChanged { height = it.height }) {
                    DeskChip(
                        text = file.name,
                        selected = false,
                        onClick = { open = true },
                        icon = MailIcons.Attachment,
                        count = Formatter.formatShortFileSize(context, file.bytes),
                    )
                    MoreSheet(
                        visible = open,
                        onDismiss = { open = false },
                        groups = if (open) listOf(ActionGroup(null, listOf(remove))) else emptyList(),
                        title = file.name,
                        menuOffset = IntOffset(0, height),
                    )
                }
            }
        }
    }
}

/** What a chosen file is called and what kind it is, as the app that offers it says. */
internal fun describe(context: Context, uri: Uri): Pair<String, String> {
    var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0)
        }
    }
    val type = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "application/octet-stream"
    return name.ifBlank { "dosya" } to type
}

/** A draft that is still being written, or that failed: what the compose screen can take up. */
fun Draft.canBeWrittenOn(): Boolean = state == DraftState.Writing || state == DraftState.Failed

/** What was typed is kept once the typing has paused this long. */
private const val KEEP_AFTER_MS = 600L

/** The text field is this many lines tall before anything is written. */
private const val BODY_LINES = 10

/** Wider than this, lines of text stop being readable: the fields end here in a large window. */
private const val MAX_WIDTH = 880
