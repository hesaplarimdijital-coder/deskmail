package com.deskmail.notify

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What a window knows of the system's leave to show notifications, and what it can do about it.
 * The activity keeps it: it is the one that can ask the system.
 *
 * @param ask asks the user to allow notifications: with the system's own question while the
 *   system still asks it, in the system's settings after that.
 * @param openSystemSettings opens the system's settings for the app's notifications: the switch,
 *   and each account's channel with its sound.
 */
class NotifyGate(val ask: () -> Unit, val openSystemSettings: () -> Unit) {
    /** Whether the system lets the app show notifications right now. */
    var allowed by mutableStateOf(false)

    /** Whether the user has been asked before: nobody is asked twice without having asked for it. */
    var asked by mutableStateOf(false)
}
