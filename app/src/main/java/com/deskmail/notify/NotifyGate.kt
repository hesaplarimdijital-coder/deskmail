package com.deskmail.notify

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What a window knows of the system's leave to tell of new mail, and what it can do about it.
 * The activity keeps it: it is the one that can ask the system.
 *
 * Two leaves. To show notifications at all; and, for mail to be told of as it comes, to be left
 * alone in the background: with battery optimisation on, the system cuts a sleeping device's
 * apps off the network, and the lines that wait for mail with it.
 *
 * @param ask asks the user to allow notifications: with the system's own question while the
 *   system still asks it, in the system's settings after that.
 * @param openSystemSettings opens the system's settings for the app's notifications: the switch,
 *   and each account's channel with its sound.
 * @param askBattery asks the user to take battery optimisation off the app, with the system's
 *   own question.
 * @param skipBattery the user was led to that question and put it off: it is not brought up
 *   again unasked.
 * @param openBatterySettings opens the system's page for the app, where its battery use is set.
 */
class NotifyGate(
    val ask: () -> Unit,
    val openSystemSettings: () -> Unit,
    val askBattery: () -> Unit = {},
    val skipBattery: () -> Unit = {},
    val openBatterySettings: () -> Unit = {},
) {
    /** Whether the system lets the app show notifications right now. */
    var allowed by mutableStateOf(false)

    /** Whether the user has been asked before: nobody is asked twice without having asked for it. */
    var asked by mutableStateOf(false)

    /** Whether the system leaves the app alone in the background right now (battery optimisation is off for it). */
    var unrestricted by mutableStateOf(false)

    /** Whether the user was led to the battery question before: once unasked, and no more. */
    var batteryAsked by mutableStateOf(false)
}
