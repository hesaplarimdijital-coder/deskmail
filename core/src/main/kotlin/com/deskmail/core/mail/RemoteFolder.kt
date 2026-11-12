package com.deskmail.core.mail

/**
 * A folder as the server lists it.
 *
 * @param path the folder's full name on the server ("INBOX", "Projeler/2026", "[Gmail]/Sent Mail").
 * @param delimiter what separates the levels of [path] on this server; null where folders do not nest.
 * @param attributes the folder's LIST attributes as the server sent them ("\HasNoChildren", "\Sent").
 * @param selectable whether the folder holds messages; a folder that only holds other folders does not.
 */
data class RemoteFolder(
    val path: String,
    val delimiter: Char?,
    val attributes: Set<String>,
    val selectable: Boolean,
    val role: FolderRole = FolderRole.Other,
) {
    /** The levels of [path], outermost first. */
    val segments: List<String>
        get() = if (delimiter == null) listOf(path) else path.split(delimiter).filter { it.isNotEmpty() }.ifEmpty { listOf(path) }
}
