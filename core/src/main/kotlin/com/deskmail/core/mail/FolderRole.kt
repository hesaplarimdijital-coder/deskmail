package com.deskmail.core.mail

/**
 * What a folder is for. Servers name their folders freely ("Sent Items", "Gönderilmiş Öğeler",
 * "[Gmail]/Sent Mail"); the role is what the app acts on, and what it shows a translated name and
 * an icon for.
 */
enum class FolderRole {
    Inbox,
    Drafts,
    Sent,
    Archive,
    Junk,
    Trash,

    /** Gmail's "All Mail": every message once more. Kept out of the unified views. */
    All,

    /** A folder of the user's own. */
    Other,
}
