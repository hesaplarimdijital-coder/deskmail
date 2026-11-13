package com.deskmail.ui.mail

import android.text.format.DateFormat
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.deskmail.R
import com.deskmail.core.mail.FolderRole
import com.deskmail.core.sync.SnoozeChoice
import com.deskmail.core.sync.SnoozeTimes
import com.deskmail.mail.MailMessage
import com.deskmail.mail.MailSource
import desktopkit.components.DeskDialog
import desktopkit.components.DeskListRow
import desktopkit.theme.DeskTheme
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Whether [message] can be snoozed: it has a Message-ID to be found again by, and lies where mail
 * waits to be dealt with (the inbox, the archive, a folder of the user's own) or is snoozed already
 * (it is then given another time). Not a draft, a sent message, what is in Spam or the trash.
 */
fun snoozable(mail: MailSource, message: MailMessage): Boolean {
    if (message.globalId.isEmpty()) return false
    val role = mail.folder(message.folderId)?.role ?: return false
    return role == FolderRole.Inbox || role == FolderRole.Archive || role == FolderRole.Other || role == FolderRole.Snoozed
}

/** Whether [message] lies in its account's Snoozed folder. */
fun isSnoozed(mail: MailSource, message: MailMessage): Boolean = mail.folder(message.folderId)?.role == FolderRole.Snoozed

/** What the reader says of a snoozed message: when it comes back, where the device knows that. */
data class SnoozeState(val until: Instant?)

/** Names the tests find the snooze dialog by. */
object SnoozeTags {
    const val DIALOG = "snooze-dialog"
    const val STRIP = "snooze-strip"
}

/**
 * "Snooze": until when, asked in a dialog over whatever layout the window has. The times are the
 * user's own ([SnoozeTimes]): this evening, tomorrow morning, the weekend, next week; each says its
 * day and hour. Nothing is shown while [open] is false.
 */
@Composable
fun SnoozeChooser(open: Boolean, onDismiss: () -> Unit, onChoose: (Instant) -> Unit) {
    if (!open) return
    val clock = LocalMailClock.current
    val choices = remember(clock) { SnoozeTimes.choices(ZonedDateTime.ofInstant(clock.now(), clock.zone)) }
    val locale = LocalConfiguration.current.locales[0]
    val hour24 = DateFormat.is24HourFormat(LocalContext.current)
    val format = remember(locale, hour24) { DateTimeFormatter.ofPattern(if (hour24) "EEE HH:mm" else "EEE h:mm a", locale) }
    DeskDialog(onDismiss = onDismiss, title = stringResource(R.string.snooze_title), modifier = Modifier.testTag(SnoozeTags.DIALOG)) {
        choices.forEach { choice ->
            DeskListRow(
                title = stringResource(label(choice.kind)),
                trailing = format.format(choice.at),
                onClick = {
                    onDismiss()
                    onChoose(choice.at.toInstant())
                },
            )
        }
        Text(stringResource(R.string.snooze_note), style = DeskTheme.type.labelSmall, color = DeskTheme.colors.textTertiary)
    }
}

private fun label(kind: SnoozeChoice.Kind): Int = when (kind) {
    SnoozeChoice.Kind.ThisEvening -> R.string.snooze_evening
    SnoozeChoice.Kind.TomorrowMorning -> R.string.snooze_tomorrow
    SnoozeChoice.Kind.ThisWeekend -> R.string.snooze_weekend
    SnoozeChoice.Kind.NextWeek -> R.string.snooze_next_week
}
