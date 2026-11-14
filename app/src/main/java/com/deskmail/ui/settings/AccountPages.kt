package com.deskmail.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.deskmail.R
import com.deskmail.core.mail.MailException
import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import com.deskmail.core.provider.AuthKind
import com.deskmail.mail.AccountActions
import com.deskmail.mail.AccountState
import com.deskmail.mail.MailAccount
import com.deskmail.mail.SetupPlan
import com.deskmail.mail.SetupResult
import com.deskmail.ui.BrowserTab
import desktopkit.components.BannerTone
import desktopkit.components.DeskButton
import desktopkit.components.DeskDialog
import desktopkit.components.DeskIcons
import desktopkit.components.DeskProgressBar
import desktopkit.components.DeskTextField
import desktopkit.components.KeyValueRow
import desktopkit.components.SegmentedChoice
import desktopkit.components.SettingRow
import desktopkit.components.StatusBanner
import desktopkit.screens.PageHeader
import desktopkit.settings.SettingsSection
import desktopkit.shell.CompactTopBar
import desktopkit.shell.HideBottomNavigation
import desktopkit.shell.LocalNavKind
import desktopkit.shell.NavKind
import desktopkit.shell.rememberShellContentPadding
import desktopkit.theme.DeskTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Names the tests find the fields of the account pages by. */
object AccountTags {
    const val ADDRESS = "account-address"
    const val PASSWORD = "account-password"
    const val SENDER_NAME = "account-sender-name"
    const val SIGNATURE = "account-signature"
}

/**
 * The frame of a page that opens over the settings: its title and Back in the top bar of a phone
 * and of a rail (desktop-kit 2.8.0; docs/KIT-ISTEKLERI.md, 15), a header with Back in a large
 * window, and the content scrolling beneath the bars.
 */
@Composable
internal fun SettingsPageFrame(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val sidebar = LocalNavKind.current == NavKind.Sidebar
    CompactTopBar(title = title, onBack = onClose, showSearch = false, showOnRail = true)
    val shell = rememberShellContentPadding()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(shell)) {
        PageHeader(title) {
            if (sidebar) DeskButton(stringResource(desktopkit.R.string.dk_back), onClose, icon = DeskIcons.ArrowLeft)
        }
        content()
        Spacer(Modifier.height(DeskTheme.dimens.gapXl))
    }
}

/**
 * "Add an account": the address, then what the search for its servers calls for, then the
 * sign-in ([form] holds where the user stands and what was typed). On a phone the bottom
 * navigation is away while it is open.
 *
 * @param scope where the servers are asked; it must outlive this page, which closes the moment the
 *   account is added.
 * @param onAdded the account was added, by its address.
 */
@Composable
fun AccountSetupPage(form: AccountSetupForm, actions: AccountActions, scope: CoroutineScope, onClose: () -> Unit, onAdded: (String) -> Unit) {
    val context = LocalContext.current
    val lookUp: () -> Unit = { scope.launch { form.lookUp(actions) } }
    val add: () -> Unit = {
        scope.launch {
            if (form.add(actions) != null) onAdded(form.address.trim())
        }
    }
    val signIn: () -> Unit = {
        scope.launch {
            if (form.signIn(actions) { url -> BrowserTab.open(context, url) } != null) onAdded(form.address.trim())
        }
    }
    // a set-up of its own on a phone: no tabs to leave it by half-way, and no bar under its buttons
    // (desktop-kit 2.9.0)
    HideBottomNavigation()
    SettingsPageFrame(stringResource(R.string.account_add), onClose) {
        when (val step = form.step) {
            AccountSetupForm.Step.Address -> AddressStep(form, lookUp)
            is AccountSetupForm.Step.Password -> PasswordStep(form, step.plan, add)
            is AccountSetupForm.Step.SignIn -> SignInStep(form, step.plan, signIn)
            is AccountSetupForm.Step.NotYet -> NotYetStep(form, step)
            AccountSetupForm.Step.Manual -> ManualStep(form, add)
        }
    }
}

@Composable
private fun AddressStep(form: AccountSetupForm, onContinue: () -> Unit) {
    val dimens = DeskTheme.dimens
    SettingsSection(stringResource(R.string.setup_address)) {
        Note(stringResource(R.string.setup_address_note))
        DeskTextField(
            value = form.address,
            onValueChange = {
                form.address = it
                form.edited()
            },
            modifier = Modifier.fillMaxWidth().padding(top = dimens.gapM).testTag(AccountTags.ADDRESS),
            hint = stringResource(R.string.setup_address_hint),
            error = form.problem != null,
            enabled = !form.busy,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Go,
            onImeAction = onContinue,
        )
        Problem(form)
        Waiting(form.busy)
        DeskButton(
            stringResource(R.string.setup_continue),
            onContinue,
            Modifier.padding(top = dimens.gapM),
            primary = true,
            enabled = !form.busy && form.address.isNotBlank(),
        )
    }
}

@Composable
private fun PasswordStep(form: AccountSetupForm, plan: SetupPlan.Found, onAdd: () -> Unit) {
    val dimens = DeskTheme.dimens
    val provider = plan.provider
    val appPassword = plan.settings.auth == AuthKind.AppPassword
    SettingsSection(provider?.name ?: stringResource(R.string.setup_found)) {
        KeyValueRow(stringResource(R.string.account_address), form.address.trim(), selectable = true)
        KeyValueRow(stringResource(R.string.setup_receiving), serverLabel(plan.settings.imap), mono = true)
        KeyValueRow(stringResource(R.string.setup_sending), serverLabel(plan.settings.smtp), mono = true)
        Note(stringResource(if (appPassword) R.string.setup_gmail_note else R.string.setup_password_note), Modifier.padding(top = dimens.gapS))
        // the page where such a password is made, one tap away: made there, copied, pasted here
        val page = provider?.appPasswordPage
        if (page != null) {
            val browser = LocalUriHandler.current
            DeskButton(
                stringResource(R.string.setup_open_app_passwords),
                { runCatching { browser.openUri(page) } },
                Modifier.padding(top = dimens.gapS),
                icon = DeskIcons.OpenWindow,
            )
        }
        PasswordField(form, stringResource(if (appPassword) R.string.setup_app_password else R.string.setup_password), onAdd)
        Problem(form, plan.settings.auth)
        Waiting(form.busy)
        Row(Modifier.padding(top = dimens.gapM), horizontalArrangement = Arrangement.spacedBy(dimens.gapS)) {
            DeskButton(stringResource(R.string.setup_add), onAdd, primary = true, enabled = !form.busy && form.password.isNotEmpty())
            DeskButton(stringResource(R.string.setup_other_address), form::startOver, enabled = !form.busy)
        }
        // a provider the app knows by heart has no other servers to offer
        if (provider == null) {
            DeskButton(
                stringResource(R.string.setup_manual_switch),
                { form.byHand(plan.settings) },
                Modifier.padding(top = dimens.gapS),
                enabled = !form.busy,
            )
        }
    }
}

/**
 * An account that signs in at its provider: the browser opens the provider's page, and the app
 * waits until it comes back. Waiting, the user can give the sign-in up.
 */
@Composable
private fun SignInStep(form: AccountSetupForm, plan: SetupPlan.SignIn, onSignIn: () -> Unit) {
    val dimens = DeskTheme.dimens
    SettingsSection(plan.provider.name) {
        KeyValueRow(stringResource(R.string.account_address), form.address.trim(), selectable = true)
        KeyValueRow(stringResource(R.string.setup_receiving), serverLabel(plan.settings.imap), mono = true)
        KeyValueRow(stringResource(R.string.setup_sending), serverLabel(plan.settings.smtp), mono = true)
        Note(stringResource(R.string.setup_microsoft_note), Modifier.padding(top = dimens.gapS))
        Problem(form, plan.settings.auth)
        if (form.busy) {
            Note(stringResource(R.string.setup_microsoft_waiting), Modifier.padding(top = dimens.gapM))
            Waiting(true)
            DeskButton(stringResource(R.string.action_cancel), form::stopSignIn, Modifier.padding(top = dimens.gapM))
        } else {
            Row(Modifier.padding(top = dimens.gapM), horizontalArrangement = Arrangement.spacedBy(dimens.gapS)) {
                DeskButton(stringResource(R.string.setup_microsoft_sign_in), onSignIn, primary = true, icon = DeskIcons.OpenWindow)
                DeskButton(stringResource(R.string.setup_other_address), form::startOver)
            }
        }
    }
}

@Composable
private fun NotYetStep(form: AccountSetupForm, step: AccountSetupForm.Step.NotYet) {
    SettingsSection(step.provider.name) {
        StatusBanner(tone = BannerTone.Info, text = stringResource(if (step.hosted) R.string.setup_hosted_note else R.string.setup_outlook_note))
        DeskButton(stringResource(R.string.setup_other_address), form::startOver, Modifier.padding(top = DeskTheme.dimens.gapM))
    }
}

@Composable
private fun ManualStep(form: AccountSetupForm, onAdd: () -> Unit) {
    val dimens = DeskTheme.dimens
    val servers = form.servers
    val security = listOf(Security.Tls to stringResource(R.string.security_tls), Security.StartTls to stringResource(R.string.security_starttls))
    SettingsSection(stringResource(R.string.setup_manual_title)) {
        KeyValueRow(stringResource(R.string.account_address), form.address.trim(), selectable = true)
        Note(stringResource(R.string.setup_manual_note), Modifier.padding(top = dimens.gapS))

        ServerFields(
            hostLabel = stringResource(R.string.setup_imap_host),
            host = servers.imapHost,
            onHost = {
                servers.imapHost = it
                form.edited()
            },
            port = servers.imapPort,
            onPort = {
                servers.imapPort = it
                form.edited()
            },
            enabled = !form.busy,
        )
        SettingRow(stringResource(R.string.setup_security)) {
            SegmentedChoice(options = security, selected = servers.imapSecurity, onSelect = servers::chooseImapSecurity)
        }
        ServerFields(
            hostLabel = stringResource(R.string.setup_smtp_host),
            host = servers.smtpHost,
            onHost = {
                servers.smtpHost = it
                form.edited()
            },
            port = servers.smtpPort,
            onPort = {
                servers.smtpPort = it
                form.edited()
            },
            enabled = !form.busy,
        )
        SettingRow(stringResource(R.string.setup_security)) {
            SegmentedChoice(options = security, selected = servers.smtpSecurity, onSelect = servers::chooseSmtpSecurity)
        }
        DeskTextField(
            value = servers.username,
            onValueChange = {
                servers.username = it
                form.edited()
            },
            modifier = Modifier.fillMaxWidth().padding(top = dimens.gapM),
            label = stringResource(R.string.setup_username),
            enabled = !form.busy,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
        )
        PasswordField(form, stringResource(R.string.setup_password), onAdd)
        Problem(form)
        Waiting(form.busy)
        Row(Modifier.padding(top = dimens.gapM), horizontalArrangement = Arrangement.spacedBy(dimens.gapS)) {
            DeskButton(stringResource(R.string.setup_add), onAdd, primary = true, enabled = !form.busy && form.password.isNotEmpty())
            DeskButton(stringResource(R.string.setup_other_address), form::startOver, enabled = !form.busy)
        }
    }
}

/** A server's name with its port beside it. */
@Composable
private fun ServerFields(hostLabel: String, host: String, onHost: (String) -> Unit, port: String, onPort: (String) -> Unit, enabled: Boolean) {
    val dimens = DeskTheme.dimens
    Row(Modifier.fillMaxWidth().padding(top = dimens.gapM), horizontalArrangement = Arrangement.spacedBy(dimens.gapS), verticalAlignment = Alignment.Bottom) {
        DeskTextField(
            value = host,
            onValueChange = onHost,
            modifier = Modifier.weight(1f),
            label = hostLabel,
            enabled = enabled,
            mono = true,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        )
        DeskTextField(
            value = port,
            onValueChange = { typed -> onPort(typed.filter { it.isDigit() }.take(5)) },
            modifier = Modifier.width(96.dp),
            label = stringResource(R.string.setup_port),
            enabled = enabled,
            mono = true,
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next,
        )
    }
}

@Composable
private fun PasswordField(form: AccountSetupForm, label: String, onDone: () -> Unit) {
    DeskTextField(
        value = form.password,
        onValueChange = {
            form.password = it
            form.edited()
        },
        modifier = Modifier.fillMaxWidth().padding(top = DeskTheme.dimens.gapM).testTag(AccountTags.PASSWORD),
        label = label,
        password = true,
        error = form.problem is AccountSetupForm.Problem.Failed,
        enabled = !form.busy,
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Done,
        onImeAction = onDone,
    )
}

/** What stood in the way at the last try, in words that say what to do about it. */
@Composable
private fun Problem(form: AccountSetupForm, auth: AuthKind = AuthKind.Password) {
    val problem = form.problem ?: return
    val text = when (problem) {
        AccountSetupForm.Problem.NotAnAddress -> stringResource(R.string.setup_not_address)
        AccountSetupForm.Problem.Duplicate -> stringResource(R.string.setup_duplicate)
        AccountSetupForm.Problem.Servers -> stringResource(R.string.setup_servers_incomplete)
        is AccountSetupForm.Problem.Failed -> failureText(problem.kind, problem.detail, auth)
        is AccountSetupForm.Problem.NotSignedIn -> notSignedInText(problem.detail)
        AccountSetupForm.Problem.NoBrowser -> stringResource(R.string.setup_no_browser)
    }
    StatusBanner(tone = BannerTone.Error, text = text, modifier = Modifier.padding(top = DeskTheme.dimens.gapM))
}

/**
 * Why a sign-in did not work, and under it what the server itself said. [auth] is how the account
 * signs in: what to look at differs.
 */
@Composable
internal fun failureText(kind: MailException.Kind, detail: String, auth: AuthKind): String {
    val summary = stringResource(
        when (kind) {
            MailException.Kind.Authentication -> when (auth) {
                AuthKind.Password -> R.string.setup_failed_login
                AuthKind.AppPassword -> R.string.setup_failed_login_app_password
                // the provider took the sign-in, and its mail server did not
                AuthKind.MicrosoftOAuth -> R.string.setup_failed_login_microsoft
            }
            MailException.Kind.Network -> R.string.setup_failed_network
            MailException.Kind.Security -> R.string.setup_failed_security
            MailException.Kind.Server -> R.string.setup_failed_server
        },
    )
    return if (detail.isBlank()) summary else summary + "\n" + stringResource(R.string.setup_server_said, detail.take(DETAIL_LENGTH))
}

/** A sign-in in the browser that came to nothing, and under it what the provider said. */
@Composable
private fun notSignedInText(detail: String): String {
    val summary = stringResource(R.string.setup_not_signed_in)
    return if (detail.isBlank()) summary else summary + "\n" + stringResource(R.string.setup_provider_said, detail.take(DETAIL_LENGTH))
}

/** What became of signing in to an account again, when it was not done. */
@Composable
private fun resultText(result: SetupResult, auth: AuthKind): String = when (result) {
    is SetupResult.Failed -> failureText(result.kind, result.detail, auth)
    is SetupResult.NotSignedIn -> notSignedInText(result.detail)
    SetupResult.NoBrowser -> stringResource(R.string.setup_no_browser)
    SetupResult.Duplicate -> stringResource(R.string.setup_duplicate)
    is SetupResult.Done -> ""
}

/** A server's own words are shown up to this length. */
private const val DETAIL_LENGTH = 300

@Composable
private fun Waiting(busy: Boolean) {
    if (busy) DeskProgressBar(fraction = null, modifier = Modifier.padding(top = DeskTheme.dimens.gapM))
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = DeskTheme.type.labelSmall, color = DeskTheme.colors.textTertiary, modifier = modifier)
}

@Composable
private fun serverLabel(server: ServerAddress): String = stringResource(R.string.setup_server, server.host, server.port)

/** How an account is doing, in a few words. */
@Composable
internal fun stateLabel(state: AccountState): String = stringResource(
    when (state) {
        AccountState.Ok -> R.string.account_state_ok
        AccountState.NeedsLogin -> R.string.account_state_login
        AccountState.Unreachable -> R.string.account_state_unreachable
        AccountState.Error -> R.string.account_state_error
    },
)

/**
 * One account: how it is doing, syncing it now, the name and the signature its mail is written
 * with, signing in again (with another password, or at its provider in the browser), and taking
 * it off the device.
 *
 * @param scope where the server is asked; as for [AccountSetupPage], it outlives the page.
 * @param onSync asks the servers for what is new.
 * @param onRemoved the account is gone, by its name.
 */
@Composable
fun AccountPage(
    account: MailAccount,
    actions: AccountActions,
    scope: CoroutineScope,
    onClose: () -> Unit,
    onSync: () -> Unit,
    onNotify: (String) -> Unit,
    onRemoved: (String) -> Unit,
) {
    val dimens = DeskTheme.dimens
    val colors = DeskTheme.colors
    val context = LocalContext.current
    var password by remember(account.id) { mutableStateOf("") }
    var busy by remember(account.id) { mutableStateOf(false) }
    var failure by remember(account.id) { mutableStateOf<SetupResult?>(null) }
    // the sign-in in the browser under way, which "Cancel" gives up
    var signingIn by remember(account.id) { mutableStateOf<Job?>(null) }
    var confirmRemove by remember(account.id) { mutableStateOf(false) }
    // what is typed; the account's own until then
    var senderName by remember(account.id) { mutableStateOf(account.senderName) }
    var signature by remember(account.id) { mutableStateOf(account.signature) }
    val saved = stringResource(R.string.account_writing_saved)
    val reconnected = stringResource(R.string.account_reconnected)
    val reconnect: () -> Unit = {
        if (!busy && password.isNotEmpty()) {
            busy = true
            failure = null
            scope.launch {
                try {
                    when (val result = actions.reconnect(account.id, password)) {
                        is SetupResult.Done -> {
                            password = ""
                            onNotify(reconnected)
                        }
                        else -> failure = result
                    }
                } finally {
                    busy = false
                }
            }
        }
    }
    val signInAgain: () -> Unit = {
        if (!busy) {
            busy = true
            failure = null
            signingIn = scope.launch {
                try {
                    when (val result = actions.reconnectSignedIn(account.id) { url -> BrowserTab.open(context, url) }) {
                        is SetupResult.Done -> onNotify(reconnected)
                        else -> failure = result
                    }
                } finally {
                    signingIn = null
                    busy = false
                }
            }
        }
    }
    SettingsPageFrame(account.name, onClose) {
        SettingsSection(stringResource(R.string.detail_account)) {
            KeyValueRow(stringResource(R.string.account_address), account.address, selectable = true)
            KeyValueRow(stringResource(R.string.account_server), account.server, mono = true)
            KeyValueRow(
                stringResource(R.string.account_state),
                stateLabel(account.state),
                valueColor = if (account.state == AccountState.Ok) colors.text else colors.warning,
            )
            if (account.detail.isNotBlank()) {
                Note(stringResource(R.string.setup_server_said, account.detail.take(DETAIL_LENGTH)))
            }
            DeskButton(stringResource(R.string.account_sync_now), onSync, Modifier.padding(top = dimens.gapM))
        }
        SettingsSection(stringResource(R.string.account_writing)) {
            Note(stringResource(R.string.account_writing_note))
            DeskTextField(
                value = senderName,
                onValueChange = { senderName = it },
                modifier = Modifier.fillMaxWidth().padding(top = dimens.gapM).testTag(AccountTags.SENDER_NAME),
                label = stringResource(R.string.account_sender_name),
                imeAction = ImeAction.Next,
            )
            DeskTextField(
                value = signature,
                onValueChange = { signature = it },
                modifier = Modifier.fillMaxWidth().padding(top = dimens.gapM).testTag(AccountTags.SIGNATURE),
                label = stringResource(R.string.account_signature),
                singleLine = false,
                minLines = 3,
                maxLines = 8,
            )
            DeskButton(
                stringResource(R.string.account_writing_save),
                {
                    scope.launch {
                        actions.saveWriting(account.id, senderName, signature)
                        onNotify(saved)
                    }
                },
                Modifier.padding(top = dimens.gapM),
                enabled = senderName != account.senderName || signature != account.signature,
            )
        }
        SettingsSection(stringResource(R.string.account_action_reconnect)) {
            if (account.auth == AuthKind.MicrosoftOAuth) {
                SignInAgain(busy, failure, signInAgain, onCancel = { signingIn?.cancel() })
            } else {
                Note(stringResource(R.string.account_reconnect_note))
                DeskTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        failure = null
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = dimens.gapM).testTag(AccountTags.PASSWORD),
                    label = stringResource(R.string.setup_password),
                    password = true,
                    error = failure != null,
                    enabled = !busy,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    onImeAction = reconnect,
                )
                failure?.let { failed ->
                    StatusBanner(tone = BannerTone.Error, text = resultText(failed, account.auth), modifier = Modifier.padding(top = dimens.gapM))
                }
                Waiting(busy)
                DeskButton(
                    stringResource(R.string.account_action_reconnect),
                    reconnect,
                    Modifier.padding(top = dimens.gapM),
                    primary = true,
                    enabled = !busy && password.isNotEmpty(),
                )
            }
        }
        SettingsSection(stringResource(R.string.account_remove)) {
            Note(stringResource(R.string.account_remove_note))
            // in the colour of what cannot be undone; the window that asks first stays
            DeskButton(stringResource(R.string.account_remove), { confirmRemove = true }, Modifier.padding(top = dimens.gapM), icon = DeskIcons.Delete, danger = true)
        }
    }
    if (confirmRemove) {
        DeskDialog(
            onDismiss = { confirmRemove = false },
            title = stringResource(R.string.account_remove_title, account.name),
            actions = {
                DeskButton(stringResource(R.string.action_cancel), { confirmRemove = false })
                DeskButton(
                    stringResource(R.string.account_remove),
                    {
                        confirmRemove = false
                        val name = account.name
                        scope.launch {
                            actions.remove(account.id)
                            onRemoved(name)
                        }
                    },
                    danger = true,
                )
            },
        ) {
            Text(stringResource(R.string.account_remove_note), style = DeskTheme.type.body, color = colors.text)
        }
    }
}

/** Signing in to an account at its provider again, in the browser; waiting, the user can give it up. */
@Composable
private fun SignInAgain(busy: Boolean, failure: SetupResult?, onSignIn: () -> Unit, onCancel: () -> Unit) {
    val dimens = DeskTheme.dimens
    Note(stringResource(R.string.account_reconnect_microsoft_note))
    failure?.let { failed ->
        StatusBanner(tone = BannerTone.Error, text = resultText(failed, AuthKind.MicrosoftOAuth), modifier = Modifier.padding(top = dimens.gapM))
    }
    if (busy) {
        Note(stringResource(R.string.setup_microsoft_waiting), Modifier.padding(top = dimens.gapM))
        Waiting(true)
        DeskButton(stringResource(R.string.action_cancel), onCancel, Modifier.padding(top = dimens.gapM))
    } else {
        DeskButton(stringResource(R.string.setup_microsoft_sign_in), onSignIn, Modifier.padding(top = dimens.gapM), primary = true, icon = DeskIcons.OpenWindow)
    }
}
