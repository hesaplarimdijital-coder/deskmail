package com.deskmail.ui.settings

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A page that opens over the list of settings. */
sealed interface SettingsPage {
    data object Log : SettingsPage

    data object AddAccount : SettingsPage

    /** One account: how it is doing, signing in again, removing it. */
    data class Account(val id: Long) : SettingsPage
}

/**
 * What the Settings screen of one window shows: which page is open over the list, where the list
 * is scrolled to, and what has been typed into "Add an account".
 *
 * Kept outside the screen, by the window: the shell drops what its screens remember whenever the
 * list of folders changes (docs/KIT-ISTEKLERI.md, 3), which is exactly what adding an account does.
 */
@Stable
class SettingsUiState {
    /** The page open over the list; null for the list itself. */
    var page: SettingsPage? by mutableStateOf(null)

    /** "Add an account" as far as the user has got with it. */
    var setup by mutableStateOf(AccountSetupForm())
        private set

    val list = LazyListState()

    /** Opens "Add an account" with nothing typed. */
    fun addAccount() {
        setup = AccountSetupForm()
        page = SettingsPage.AddAccount
    }

    fun close() {
        page = null
    }
}
