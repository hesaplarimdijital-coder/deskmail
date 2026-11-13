package com.deskmail.ui.mail

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.deskmail.R
import desktopkit.components.DeskSearchField
import desktopkit.theme.DeskTheme
import kotlinx.coroutines.delay

/**
 * The field of the search screen, above what it finds. What is looked for follows the typing
 * after a short pause, or at once with the keyboard's search key. The field asks for the focus
 * when the screen opens with nothing typed yet, and whenever "/" asks for it.
 *
 * What is typed is the window's ([MailUiState.searchText]): it is still there when the screen is
 * come back to.
 */
@Composable
fun SearchBox(ui: MailUiState, modifier: Modifier = Modifier) {
    val dimens = DeskTheme.dimens
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // whether the field has its place in the window yet: before that there is nothing to give the focus to
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(ui.searchText) {
        if (ui.searchText != ui.searchQuery) {
            delay(SEARCH_AFTER_MS)
            ui.searchQuery = ui.searchText
        }
    }
    LaunchedEffect(ui.searchFocusAsked, placed) {
        if (placed && (ui.searchFocusAsked || ui.searchText.isEmpty())) {
            // Asked for until the field has had the focus for a moment: a screen that is still
            // being drawn may not give it at once, or may take it back.
            var held = 0
            var frames = 0
            while (held < HOLD_FRAMES && frames < FOCUS_FRAMES) {
                withFrameNanos { }
                frames++
                if (focused) {
                    held++
                } else {
                    held = 0
                    runCatching { focus.requestFocus() }
                }
            }
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
            .onFocusChanged { focused = it.hasFocus }
            .onGloballyPositioned { placed = true }
            .testTag(MailTags.SEARCH),
        hint = stringResource(R.string.search_hint),
        onSearch = { ui.searchQuery = it },
    )
}

/** What is typed is looked for once the typing has paused this long. */
private const val SEARCH_AFTER_MS = 250L

/** For how many frames at most the field is asked to take the focus: about a second. */
private const val FOCUS_FRAMES = 60

/** For how many frames in a row the field is to have had the focus before it is left alone. */
private const val HOLD_FRAMES = 5
