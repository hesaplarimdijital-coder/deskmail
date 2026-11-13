package com.deskmail.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.deskmail.R
import desktopkit.components.DeskButton
import desktopkit.components.DeskDialog
import desktopkit.theme.DeskTheme

/** The name the tests find the question by. */
const val BATTERY_QUESTION_TAG = "battery-question"

/**
 * Leads the user to the system's battery setting for the app, once: mail is told of as it comes
 * only where the system leaves the app alone in the background. Says why, and what Samsung wants
 * besides; the question itself is the system's ([onAllow]).
 */
@Composable
fun BatteryQuestion(onAllow: () -> Unit, onLater: () -> Unit) {
    DeskDialog(
        onDismiss = onLater,
        title = stringResource(R.string.battery_ask_title),
        modifier = Modifier.testTag(BATTERY_QUESTION_TAG),
        maxWidth = 480.dp,
        actions = {
            DeskButton(stringResource(R.string.battery_ask_later), onLater)
            DeskButton(stringResource(R.string.settings_battery_allow), onAllow, primary = true)
        },
    ) {
        Text(stringResource(R.string.battery_ask_text), style = DeskTheme.type.body, color = DeskTheme.colors.text)
        Text(
            stringResource(R.string.battery_ask_samsung),
            modifier = Modifier.padding(top = DeskTheme.dimens.gapM),
            style = DeskTheme.type.labelSmall,
            color = DeskTheme.colors.textSecondary,
        )
    }
}
