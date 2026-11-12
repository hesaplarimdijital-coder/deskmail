package com.deskmail.ui.mail

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.deskmail.R
import desktopkit.components.DeskSearchField
import desktopkit.theme.DeskTheme
import kotlinx.coroutines.delay

/**
 * The field of the search screen, above what it finds. What is looked for follows the typing
 * after a short pause, or at once with the keyboard's search key. The field takes the focus when
 * the screen opens with nothing typed yet, and whenever "/" asks for it.
 *
 * What is typed is the window's ([MailUiState.searchText]): it is still there when the screen is
 * come back to.
 */
@Composable
fun SearchBox(ui: MailUiState, modifier: Modifier = Modifier) {
    val dimens = DeskTheme.dimens
    val focus = remember { FocusRequester() }
    LaunchedEffect(ui.searchText) {
        if (ui.searchText != ui.searchQuery) {
            delay(SEARCH_AFTER_MS)
            ui.searchQuery = ui.searchText
        }
    }
    LaunchedEffect(ui.searchFocusAsked) {
        if (ui.searchFocusAsked || ui.searchText.isEmpty()) {
            // once the field has its place in the window
            withFrameNanos { }
            runCatching { focus.requestFocus() }
            ui.searchFocusAsked = false
        }
    }
    DeskSearchField(
        value = ui.searchText,
        onValueChange = { ui.searchText = it },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.pagePadding)
            .padding(top = dimens.gapS)
            .focusRequester(focus)
            .testTag(MailTags.SEARCH),
        hint = stringResource(R.string.search_hint),
        onSearch = { ui.searchQuery = it },
    )
}

/** What is typed is looked for once the typing has paused this long. */
private const val SEARCH_AFTER_MS = 250L
