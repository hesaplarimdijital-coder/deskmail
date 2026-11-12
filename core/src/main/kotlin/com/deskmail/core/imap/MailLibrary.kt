package com.deskmail.core.imap

import org.eclipse.angus.mail.util.MailStreamProvider

/**
 * What the mail library (Jakarta Mail, in Angus Mail's implementation) is told once, before its
 * first use, so that it needs nothing found for it in the resource files of its jar. Whether those
 * files survive the packaging of an Android app is nothing the app's mail should depend on.
 *
 * - The decoders of base64 and quoted-printable ("stream provider") are found through
 *   META-INF/services unless a system property names their class. It is named here.
 * - The IMAP store is made directly ([ImapConnection.open]), not looked up in the list of providers.
 * - A multipart's parts are taken from the message's structure ([MessageParts]), not through the
 *   table of content handlers.
 */
internal object MailLibrary {
    private const val STREAM_PROVIDER = "jakarta.mail.util.StreamProvider"

    init {
        // the class itself, so that a library that renames it fails the build and not a sign-in
        if (System.getProperty(STREAM_PROVIDER) == null) System.setProperty(STREAM_PROVIDER, MailStreamProvider::class.java.name)
    }

    /** Makes sure the above has happened; costs nothing after the first call. */
    fun prepare() = Unit
}
