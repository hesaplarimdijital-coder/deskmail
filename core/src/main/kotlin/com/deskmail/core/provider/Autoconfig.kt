package com.deskmail.core.provider

import com.deskmail.core.log.AppLog
import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress

/**
 * Finds the servers of an address, in the order that asks the fewest strangers:
 *
 * 1. the providers the app knows by heart ([ProviderTable]);
 * 2. Thunderbird's database of providers ([Ispdb]), by the address's domain;
 * 3. the same database by the domain of the server that takes the domain's mail (its MX record):
 *    an address at a company's own domain is often hosted by a provider the database knows;
 * 4. what the domain itself says about its mail servers (its SRV records, RFC 6186).
 *
 * Nothing found means the user enters the servers by hand. Only the domain is ever sent anywhere,
 * never the address.
 *
 * @param fetch gets the text at an https address, or null when there is none (not found, no network).
 * @param mailHosts the hosts that take mail for a domain (its MX records), best first; empty when unknown.
 * @param services the hosts that offer a service ("_imaps._tcp.example.org", its SRV records), best
 *   first; empty when unknown.
 */
class Autoconfig(
    private val fetch: (url: String) -> String?,
    private val mailHosts: (domain: String) -> List<String> = { emptyList() },
    private val services: (name: String) -> List<ServiceHost> = { emptyList() },
    private val log: AppLog? = null,
) {

    /** Where the settings came from, for the log and for telling the user. */
    enum class Source { Table, Database, MailHost, Service }

    data class Found(val settings: MailSettings, val source: Source, val provider: Provider? = null)

    fun find(address: String): Found? {
        val domain = ProviderTable.domainOf(address) ?: return null
        ProviderTable.forAddress(address)?.let { provider ->
            return Found(provider.settingsFor(address), Source.Table, provider)
        }
        lookup(domain, address)?.let {
            log?.info(TAG, "$domain: settings from the provider database")
            return Found(it, Source.Database)
        }
        for (host in hostsOf(domain).take(MAIL_HOSTS_TRIED)) {
            val parent = registrable(host) ?: continue
            if (parent == domain) continue
            // a domain hosted by a provider the app knows by heart
            ProviderTable.all.firstOrNull { parent in it.domains || parent in HOSTED_BY[it.id].orEmpty() }?.let { provider ->
                log?.info(TAG, "$domain: its mail goes to $host, which is ${provider.name}")
                return Found(provider.settingsFor(address), Source.MailHost, provider)
            }
            lookup(parent, address)?.let {
                log?.info(TAG, "$domain: its mail goes to $host; settings from the provider database for $parent")
                return Found(it, Source.MailHost)
            }
        }
        announced(domain, address)?.let {
            log?.info(TAG, "$domain: settings from its own service records")
            return Found(it, Source.Service)
        }
        log?.info(TAG, "$domain: no settings found")
        return null
    }

    /**
     * What the domain's SRV records say: IMAP and mail submission, over TLS where it is offered,
     * with STARTTLS otherwise. The address is the user name; these records have no place for another.
     */
    private fun announced(domain: String, address: String): MailSettings? {
        val imap = service("_imaps._tcp.$domain", Security.Tls) ?: service("_imap._tcp.$domain", Security.StartTls) ?: return null
        val smtp = service("_submissions._tcp.$domain", Security.Tls) ?: service("_submission._tcp.$domain", Security.StartTls) ?: return null
        return MailSettings(imap, smtp, username = address, auth = AuthKind.Password)
    }

    private fun service(name: String, security: Security): ServerAddress? {
        val host = try {
            services(name).firstOrNull()
        } catch (e: Exception) {
            log?.warn(TAG, "$name could not be asked for: $e")
            null
        } ?: return null
        return ServerAddress(host.host, host.port, security)
    }

    private fun hostsOf(domain: String): List<String> =
        try {
            mailHosts(domain)
        } catch (e: Exception) {
            log?.warn(TAG, "$domain: its mail hosts could not be asked for: $e")
            emptyList()
        }

    private fun lookup(domain: String, address: String): MailSettings? {
        val xml = try {
            fetch(Ispdb.url(domain))
        } catch (e: Exception) {
            log?.warn(TAG, "$domain: the provider database could not be asked: $e")
            null
        } ?: return null
        return Ispdb.parse(xml, address)
    }

    companion object {
        private const val TAG = "setup"
        private const val MAIL_HOSTS_TRIED = 2

        /**
         * The domains under which the known providers take other domains' mail (Google Workspace,
         * Microsoft 365). Such an account signs in the way its provider's own addresses do.
         */
        private val HOSTED_BY = mapOf(
            "gmail" to setOf("google.com", "googlemail.com"),
            "outlook" to setOf("outlook.com", "office365.com"),
        )

        /**
         * The last two labels of a host name ("aspmx.l.google.com" → "google.com"), three where the
         * second to last is a short country-level one ("mx.example.com.tr" → "example.com.tr").
         * Good enough to find a mail host's provider; not a public-suffix list.
         */
        internal fun registrable(host: String): String? {
            val labels = host.trim().trimEnd('.').lowercase().split('.').filter { it.isNotEmpty() }
            if (labels.size < 2) return null
            val take = if (labels.size >= 3 && labels[labels.size - 1].length == 2 && labels[labels.size - 2] in SECOND_LEVEL) 3 else 2
            return labels.takeLast(take).joinToString(".")
        }

        private val SECOND_LEVEL = setOf("com", "co", "net", "org", "gov", "edu", "ac", "gen", "web", "info", "biz", "k12", "bel", "pol", "av", "dr", "name", "tv", "tel")
    }
}
