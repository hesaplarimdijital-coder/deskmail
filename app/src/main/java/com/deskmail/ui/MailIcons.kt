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

    /** A folder with an arrow into it: move to a folder. */
    val MoveTo: ImageVector by lazy {
        DeskIcons.build(
            "MoveTo",
            DeskIcons.stroke("M2 4.5a1 1 0 0 1 1-1h3L7.5 5H13a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1z"),
            DeskIcons.stroke("M5.8 9.2h4.2"),
            DeskIcons.stroke("M8.5 7.6 10.1 9.2 8.5 10.8"),
        )
    }

    /** An arrow that turns back: reply. */
    val Reply: ImageVector by lazy {
        DeskIcons.build(
            "Reply",
            DeskIcons.stroke("M6.5 4 2.5 8l4 4"),
            DeskIcons.stroke("M2.5 8h6.5a4.5 4.5 0 0 1 4.5 4.5"),
        )
    }

    /** Two arrows that turn back: reply to all. */
    val ReplyAll: ImageVector by lazy {
        DeskIcons.build(
            "ReplyAll",
            DeskIcons.stroke("M5.5 4 1.5 8l4 4"),
            DeskIcons.stroke("M9 4 5 8l4 4"),
            DeskIcons.stroke("M5 8h5a4 4 0 0 1 4 4.5"),
        )
    }

    /** An arrow that turns onward: forward. */
    val ForwardMail: ImageVector by lazy {
        DeskIcons.build(
            "ForwardMail",
            DeskIcons.stroke("M9.5 4l4 4-4 4"),
            DeskIcons.stroke("M13.5 8H7a4.5 4.5 0 0 0-4.5 4.5"),
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

    /** An arrow out of a tray: hand something to another app. */
    val Share: ImageVector by lazy {
        DeskIcons.build(
            "Share",
            DeskIcons.stroke("M8 10.5V2.5M5 5.3 8 2.4l3 2.9"),
            DeskIcons.stroke("M3.5 8.5v4a1 1 0 0 0 1 1h7a1 1 0 0 0 1-1v-4"),
        )
    }

    /** An arrow into a tray: take something in from a file. */
    val Import: ImageVector by lazy {
        DeskIcons.build(
            "Import",
            DeskIcons.stroke("M8 2.5v8M5 7.7 8 10.6l3-2.9"),
            DeskIcons.stroke("M3.5 8.5v4a1 1 0 0 0 1 1h7a1 1 0 0 0 1-1v-4"),
        )
    }

    val Copy: ImageVector by lazy {
        DeskIcons.build(
            "Copy",
            DeskIcons.stroke("M6.5,5.5H12.5A1,1 0 0 1 13.5,6.5V12.5A1,1 0 0 1 12.5,13.5H6.5A1,1 0 0 1 5.5,12.5V6.5A1,1 0 0 1 6.5,5.5Z"),
            DeskIcons.stroke("M10.5 5.5v-2a1 1 0 0 0-1-1h-6a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2"),
        )
    }

    /** An arrow going round: ask the servers for what is new. */
    val Refresh: ImageVector by lazy {
        DeskIcons.build(
            "Refresh",
            DeskIcons.stroke("M13 8a5 5 0 1 1-1.46-3.54"),
            DeskIcons.stroke("M12.4 2.2v2.6H9.8"),
        )
    }

    /** Head and shoulders: an account. */
    val Account: ImageVector by lazy {
        DeskIcons.build(
            "Account",
            DeskIcons.stroke("M8 2.8a2.4 2.4 0 1 0 0 4.8 2.4 2.4 0 0 0 0-4.8z"),
            DeskIcons.stroke("M3.2 13.2c.5-2.4 2.4-3.6 4.8-3.6s4.3 1.2 4.8 3.6"),
        )
    }

    private const val STAR = "M8.0,2.4L9.5,6.3L13.7,6.5L10.4,9.2L11.5,13.3L8.0,10.9L4.5,13.3L5.6,9.2L2.3,6.5L6.5,6.3Z"
}
