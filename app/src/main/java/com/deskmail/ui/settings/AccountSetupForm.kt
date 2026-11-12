package com.deskmail.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deskmail.core.mail.MailException
import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import com.deskmail.core.provider.AuthKind
import com.deskmail.core.provider.MailSettings
import com.deskmail.core.provider.Provider
import com.deskmail.core.provider.ProviderTable
import com.deskmail.mail.AccountActions
import com.deskmail.mail.AccountDraft
import com.deskmail.mail.SetupPlan
import com.deskmail.mail.SetupResult

/**
 * "Add an account" as the user goes through it: the address, then what the search for its
 * servers came to (a password to enter, a provider this version cannot sign in to yet, or the
 * servers by hand), then the sign-in. What is typed and where the user stands are kept here,
 * outside the page that shows them.
 */
@Stable
class AccountSetupForm {
    var address by mutableStateOf("")
    var password by mutableStateOf("")

    /** Where the user stands. */
    var step: Step by mutableStateOf(Step.Address)
        private set

    /** A server is being asked: the buttons wait. */
    var busy by mutableStateOf(false)
        private set

    /** What stood in the way at the last try. */
    var problem: Problem? by mutableStateOf(null)
        private set

    /** The servers as they are typed by hand. */
    val servers = ManualServers()

    sealed interface Step {
        data object Address : Step

        /** The servers are known; the password is asked for. */
        data class Password(val plan: SetupPlan.Found) : Step

        /** A provider this version cannot sign in to yet. */
        data class NotYet(val provider: Provider) : Step

        /** The servers are entered by hand. */
        data object Manual : Step
    }

    sealed interface Problem {
        data object NotAnAddress : Problem

        data object Duplicate : Problem

        /** Something is missing from the servers typed by hand. */
        data object Servers : Problem

        /** The server was asked and said no, or could not be asked. */
        data class Failed(val kind: MailException.Kind, val detail: String) : Problem
    }

    /** Something was typed: the last try's problem is no longer about what is there. */
    fun edited() {
        problem = null
    }

    /** Works out where the address's servers are and moves on to what that calls for. */
    suspend fun lookUp(actions: AccountActions) {
        if (busy) return
        busy = true
        problem = null
        try {
            when (val plan = actions.find(address)) {
                SetupPlan.NotAnAddress -> problem = Problem.NotAnAddress
                SetupPlan.Duplicate -> problem = Problem.Duplicate
                is SetupPlan.Found -> {
                    password = ""
                    step = Step.Password(plan)
                }
                is SetupPlan.NotYet -> step = Step.NotYet(plan.provider)
                SetupPlan.Manual -> byHand(null)
            }
        } finally {
            busy = false
        }
    }

    /** Back to the address: another one, or the same one typed right this time. */
    fun startOver() {
        step = Step.Address
        password = ""
        problem = null
    }

    /** The servers by hand, starting from [found] where the search came to something. */
    fun byHand(found: MailSettings?) {
        servers.fill(address.trim(), found)
        problem = null
        step = Step.Manual
    }

    /** Signs in and adds the account. Returns its id; null when it was not added, and [problem] says why. */
    suspend fun add(actions: AccountActions): Long? {
        if (busy) return null
        val draft = draft() ?: return null
        busy = true
        problem = null
        try {
            return when (val result = actions.add(draft)) {
                is SetupResult.Done -> result.accountId
                SetupResult.Duplicate -> {
                    problem = Problem.Duplicate
                    null
                }
                is SetupResult.Failed -> {
                    problem = Problem.Failed(result.kind, result.detail)
                    null
                }
            }
        } finally {
            busy = false
        }
    }

    private fun draft(): AccountDraft? = when (val at = step) {
        is Step.Password -> AccountDraft(address.trim(), at.plan.settings, at.plan.provider, password)
        Step.Manual -> {
            val settings = servers.settings()
            if (settings == null) problem = Problem.Servers
            settings?.let { AccountDraft(address.trim(), it, null, password) }
        }
        else -> null
    }
}

/** An account's servers as the user types them: text until they are complete. */
@Stable
class ManualServers {
    var imapHost by mutableStateOf("")
    var imapPort by mutableStateOf(IMAP_TLS)
    var imapSecurity by mutableStateOf(Security.Tls)
        private set
    var smtpHost by mutableStateOf("")
    var smtpPort by mutableStateOf(SMTP_TLS)
    var smtpSecurity by mutableStateOf(Security.Tls)
        private set
    var username by mutableStateOf("")

    /**
     * Starts from what was [found], or from the names most providers give their servers
     * ("imap." and "smtp." before the address's domain), which the user then corrects.
     */
    fun fill(address: String, found: MailSettings?) {
        val domain = ProviderTable.domainOf(address).orEmpty()
        imapHost = found?.imap?.host ?: if (domain.isEmpty()) "" else "imap.$domain"
        imapSecurity = found?.imap?.security?.takeIf { it != Security.None } ?: Security.Tls
        imapPort = found?.imap?.port?.toString() ?: usualImap(imapSecurity)
        smtpHost = found?.smtp?.host ?: if (domain.isEmpty()) "" else "smtp.$domain"
        smtpSecurity = found?.smtp?.security?.takeIf { it != Security.None } ?: Security.Tls
        smtpPort = found?.smtp?.port?.toString() ?: usualSmtp(smtpSecurity)
        username = found?.username ?: address
    }

    /** Another security: the port follows when it was the usual one for the security before. */
    fun chooseImapSecurity(security: Security) {
        if (imapPort.isBlank() || imapPort == usualImap(imapSecurity)) imapPort = usualImap(security)
        imapSecurity = security
    }

    fun chooseSmtpSecurity(security: Security) {
        if (smtpPort.isBlank() || smtpPort == usualSmtp(smtpSecurity)) smtpPort = usualSmtp(security)
        smtpSecurity = security
    }

    /** The servers as typed, or null while a name, a port or the user name is missing or cannot be one. */
    fun settings(): MailSettings? {
        val imap = server(imapHost, imapPort, imapSecurity) ?: return null
        val smtp = server(smtpHost, smtpPort, smtpSecurity) ?: return null
        val user = username.trim()
        if (user.isEmpty()) return null
        return MailSettings(imap, smtp, user, AuthKind.Password)
    }

    private fun server(host: String, port: String, security: Security): ServerAddress? {
        val name = host.trim().lowercase()
        if (name.isEmpty() || name.any { it.isWhitespace() || it == '/' || it == '@' }) return null
        val number = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return ServerAddress(name, number, security)
    }

    private companion object {
        const val IMAP_TLS = "993"
        const val IMAP_STARTTLS = "143"
        const val SMTP_TLS = "465"
        const val SMTP_STARTTLS = "587"

        fun usualImap(security: Security) = if (security == Security.StartTls) IMAP_STARTTLS else IMAP_TLS

        fun usualSmtp(security: Security) = if (security == Security.StartTls) SMTP_STARTTLS else SMTP_TLS
    }
}
