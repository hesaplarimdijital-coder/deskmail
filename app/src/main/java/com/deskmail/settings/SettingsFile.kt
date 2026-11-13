package com.deskmail.settings

import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import com.deskmail.core.provider.AuthKind
import com.deskmail.core.provider.MailSettings
import com.deskmail.mail.MessageTemplate
import com.deskmail.mail.PortableAccount
import com.deskmail.mail.SavedSearch
import com.deskmail.mail.SenderMarks
import desktopkit.settings.DeskPreferences
import desktopkit.theme.DensityPreference
import desktopkit.theme.ThemeMode
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.InputStream
import java.time.Instant

/**
 * The app's settings in a file of their own, to take to another device ("Move settings"): the
 * accounts as [PortableAccount] has them, the app's own settings ([AppSettings.portable]), the
 * look (theme, accent, density), and the user's own lists: the templates, the saved searches and
 * what was said of senders. Never a password or a token: the file has no place for one.
 *
 * Of the kind [KIND], written as version [FORMAT]. A later version may add to it; what this one
 * does not know it passes over.
 */
object SettingsFile {
    const val KIND = "deskmail-settings"
    const val FORMAT = 1
    const val MIME_TYPE = "application/json"

    /** What may be picked to be read: a file saved as JSON is not always said to be one. */
    val OPEN_TYPES = arrayOf(MIME_TYPE, "text/*", "application/octet-stream")

    /** A settings file is small; anything larger than this is no settings file, and is not read whole. */
    const val MAX_BYTES = 1_000_000

    /** What a settings file holds. */
    data class Contents(
        val accounts: List<PortableAccount>,
        val settings: Map<String, Any?> = emptyMap(),
        val look: Look? = null,
        val templates: List<MessageTemplate> = emptyList(),
        val searches: List<SavedSearch> = emptyList(),
        val senders: Map<String, SenderMarks> = emptyMap(),
    )

    /** The kit's look, by name: what the other device does not know is passed over there. */
    data class Look(val theme: String, val accent: String, val density: String)

    fun write(contents: Contents, writtenAt: Instant): String {
        val root = JSONObject()
            .put("kind", KIND)
            .put("format", FORMAT)
            .put("written", writtenAt.toString())
            .put("accounts", JSONArray(contents.accounts.map { it.toJson() }))
            .put("settings", JSONObject(contents.settings))
        contents.look?.let { look -> root.put("look", JSONObject().put("theme", look.theme).put("accent", look.accent).put("density", look.density)) }
        root.put("templates", JSONArray(contents.templates.map { JSONObject().put("name", it.name).put("body", it.body) }))
        root.put("searches", JSONArray(contents.searches.map { JSONObject().put("name", it.name).put("query", it.query) }))
        root.put("senders", JSONArray(contents.senders.map { (address, marks) -> JSONObject().put("address", address).put("vip", marks.vip).put("images", marks.images) }))
        return root.toString(2)
    }

    /** The settings in [text]; null when it is no settings file of this app's. */
    fun read(text: String): Contents? {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return null
        }
        if (root.optString("kind") != KIND) return null
        val list = root.optJSONArray("accounts")
        val accounts = if (list == null) emptyList() else (0 until list.length()).mapNotNull { list.optJSONObject(it)?.toAccount() }
        val settings = root.optJSONObject("settings")?.let { values -> values.keys().asSequence().associateWith { values.opt(it) } }.orEmpty()
        val look = root.optJSONObject("look")?.let { Look(it.optString("theme"), it.optString("accent"), it.optString("density")) }
        // the ids are this device's to give: a template, a search is known by what it is
        val templates = objects(root, "templates").mapNotNull { item ->
            val name = item.optString("name").trim()
            val body = item.optString("body")
            if (name.isEmpty() || body.isBlank()) null else MessageTemplate(0, name, body)
        }
        val searches = objects(root, "searches").mapNotNull { item ->
            val query = item.optString("query").trim()
            if (query.isEmpty()) null else SavedSearch(0, item.optString("name").trim().ifEmpty { query }, query)
        }
        val senders = objects(root, "senders").mapNotNull { item ->
            val address = item.optString("address").trim().lowercase()
            val marks = SenderMarks(vip = item.optBoolean("vip"), images = item.optBoolean("images"))
            if (address.isEmpty() || '@' !in address || marks == SenderMarks()) null else address to marks
        }.toMap()
        return Contents(accounts, settings, look, templates, searches, senders)
    }

    /** The objects of the list [name] in [root]; what is not an object is passed over. */
    private fun objects(root: JSONObject, name: String): List<JSONObject> {
        val list = root.optJSONArray(name) ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
    }

    /** [input] read as a settings file, up to [MAX_BYTES]; null when it is no settings file, or a larger file. */
    fun read(input: InputStream): Contents? {
        val bytes = input.readNBytes(MAX_BYTES + 1)
        if (bytes.size > MAX_BYTES) return null
        return read(bytes.decodeToString())
    }

    /** The look this window has, for the file. */
    fun lookOf(prefs: DeskPreferences): Look = Look(prefs.themeMode.name, prefs.accent.name, prefs.density.name)

    /** Takes the look of another device, as far as this one has the same theme, accent and density. */
    fun applyLook(prefs: DeskPreferences, look: Look) {
        ThemeMode.entries.firstOrNull { it.name == look.theme }?.let { prefs.updateThemeMode(it) }
        prefs.accents.firstOrNull { it.name == look.accent }?.let { prefs.updateAccent(it) }
        DensityPreference.entries.firstOrNull { it.name == look.density }?.let { prefs.updateDensity(it) }
    }

    private fun PortableAccount.toJson(): JSONObject = JSONObject()
        .put("address", address)
        .put("name", name)
        .put("senderName", senderName)
        .put("provider", provider)
        .put("imap", settings.imap.toJson())
        .put("smtp", settings.smtp.toJson())
        .put("username", settings.username)
        .put("auth", settings.auth.name)
        .put("syncDays", syncDays)
        .put("notify", notifyMode)
        .put("signature", signature)

    private fun ServerAddress.toJson(): JSONObject = JSONObject().put("host", host).put("port", port).put("security", security.name)

    /** An account of the file; null for one without an address or without servers. */
    private fun JSONObject.toAccount(): PortableAccount? {
        val address = optString("address").trim()
        val imap = optJSONObject("imap")?.toServer()
        val smtp = optJSONObject("smtp")?.toServer()
        if (address.isEmpty() || imap == null || smtp == null) return null
        return PortableAccount(
            address = address,
            name = optString("name"),
            senderName = optString("senderName"),
            provider = optString("provider"),
            settings = MailSettings(imap, smtp, optString("username").ifEmpty { address }, AuthKind.entries.firstOrNull { it.name == optString("auth") } ?: AuthKind.Password),
            syncDays = optInt("syncDays", 0),
            notifyMode = optString("notify"),
            signature = optString("signature"),
        )
    }

    /** A server of the file; null without a host or with a port that cannot be. A security this version does not know is read as TLS: never as less. */
    private fun JSONObject.toServer(): ServerAddress? {
        val host = optString("host").trim()
        val port = optInt("port", -1)
        if (host.isEmpty() || port !in 1..MAX_PORT) return null
        return ServerAddress(host, port, Security.entries.firstOrNull { it.name == optString("security") } ?: Security.Tls)
    }

    private const val MAX_PORT = 65_535
}
