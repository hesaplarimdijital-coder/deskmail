package com.deskmail.ui

import androidx.compose.ui.graphics.vector.ImageVector
import desktopkit.components.DeskIcons

/**
 * The icons a mail app needs beyond the kit's, drawn the kit's way: lines on a 16 x 16 grid,
 * 1.6 stroke, round caps ([DeskIcons.build]).
 */
object MailIcons {
    /** A closed envelope: mail, unread. */
    val Mail: ImageVector by lazy {
        DeskIcons.build(
            "Mail",
            DeskIcons.stroke("M3.5,3.5H12.5A1.5,1.5 0 0 1 14,5V11A1.5,1.5 0 0 1 12.5,12.5H3.5A1.5,1.5 0 0 1 2,11V5A1.5,1.5 0 0 1 3.5,3.5Z"),
            DeskIcons.stroke("M2.6 5.2 8 9l5.4-3.8"),
        )
    }

    /** An opened envelope: read. */
    val MailOpen: ImageVector by lazy {
        DeskIcons.build(
            "MailOpen",
            DeskIcons.stroke("M2.5 6.6 8 3l5.5 3.6v5.4a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1z"),
            DeskIcons.stroke("M2.8 7 8 10.2 13.2 7"),
        )
    }

    val Inbox: ImageVector by lazy {
        DeskIcons.build(
            "Inbox",
            DeskIcons.stroke("M2.5 9.5 4.4 3.5h7.2l1.9 6v2.5a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1z"),
            DeskIcons.stroke("M2.5 9.5h3.3l.8 1.5h2.8l.8-1.5h3.3"),
        )
    }

    val Star: ImageVector by lazy { DeskIcons.build("Star", DeskIcons.stroke(STAR)) }

    val StarFilled: ImageVector by lazy { DeskIcons.build("StarFilled", DeskIcons.fill(STAR), DeskIcons.stroke(STAR)) }

    val Drafts: ImageVector by lazy {
        DeskIcons.build(
            "Drafts",
            DeskIcons.stroke("M4.5,2.5H9.5L12.5,5.5V12.5A1,1 0 0 1 11.5,13.5H4.5A1,1 0 0 1 3.5,12.5V3.5A1,1 0 0 1 4.5,2.5Z"),
            DeskIcons.stroke("M9.5 2.5v3h3M6 8.5h4M6 11h2.5"),
        )
    }

    val Sent: ImageVector by lazy {
        DeskIcons.build("Sent", DeskIcons.stroke("M13.5 2.5 2.5 6.9l4.4 1.9 1.9 4.7z"), DeskIcons.stroke("M13.5 2.5 6.9 8.8"))
    }

    val Archive: ImageVector by lazy {
        DeskIcons.build(
            "Archive",
            DeskIcons.stroke("M2.5,3.5H13.5V6H2.5Z"),
            DeskIcons.stroke("M3.5 6v6.5a1 1 0 0 0 1 1h7a1 1 0 0 0 1-1V6M6.5 8.8h3"),
        )
    }

    val Junk: ImageVector by lazy {
        DeskIcons.build("Junk", DeskIcons.stroke("M2.5,8a5.5,5.5 0 1 0 11,0a5.5,5.5 0 1 0 -11,0Z"), DeskIcons.stroke("m4.2 4.2 7.6 7.6"))
    }

    val Attachment: ImageVector by lazy {
        DeskIcons.build(
            "Attachment",
            DeskIcons.stroke("M12.6 7.6 8.1 12.1a2.9 2.9 0 0 1-4.1-4.1l5-5a1.9 1.9 0 0 1 2.7 2.7L6.9 10.5a.95.95 0 0 1-1.35-1.35L9.7 5"),
        )
    }

    /** A pencil: write a new message. */
    val Compose: ImageVector by lazy {
        DeskIcons.build(
            "Compose",
            DeskIcons.stroke("M3.3 12.7l.6-2.7 6.7-6.7a1.2 1.2 0 0 1 1.7 0l.4.4a1.2 1.2 0 0 1 0 1.7L6 12.1z"),
            DeskIcons.stroke("M9.6 4.3l2.1 2.1"),
        )
    }

    /** A page of lines: the log. */
    val Log: ImageVector by lazy {
        DeskIcons.build(
            "Log",
            DeskIcons.stroke("M4.5,2.5H11.5A1,1 0 0 1 12.5,3.5V12.5A1,1 0 0 1 11.5,13.5H4.5A1,1 0 0 1 3.5,12.5V3.5A1,1 0 0 1 4.5,2.5Z"),
            DeskIcons.stroke("M6 5.5h4M6 8h4M6 10.5h2.5"),
        )
    }

    private const val STAR = "M8.0,2.4L9.5,6.3L13.7,6.5L10.4,9.2L11.5,13.3L8.0,10.9L4.5,13.3L5.6,9.2L2.3,6.5L6.5,6.3Z"
}
