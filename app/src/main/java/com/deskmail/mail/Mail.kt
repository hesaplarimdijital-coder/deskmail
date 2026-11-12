package com.deskmail.mail

import com.deskmail.core.mail.FolderRole

/** An account as the screens show it. */
data class MailAccount(
    val id: Long,
    /** The name the user knows the account by: "Outlook", "Gmail", "İş". */
    val name: String,
    val address: String,
)

/** A folder of an account as the screens show it. */
data class MailFolder(
    val id: Long,
    val accountId: Long,
    /** The folder's place in the account's tree, outermost first: "Projeler", "2026". */
    val path: List<String>,
    val role: FolderRole,
    val unread: Int = 0,
    val total: Int = 0,
) {
    val name: String get() = path.last()

    /** 0 for a top-level folder. */
    val depth: Int get() = path.size - 1
}
