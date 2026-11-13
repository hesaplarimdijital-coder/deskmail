package com.deskmail.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.TestApp
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.Routes
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The licences of the work of others the app ships: every one there to read, and reached from Settings. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class LicencesTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun text(id: Int) = context.resources.openRawResource(id).bufferedReader().use { it.readText() }

    @Test
    fun everyLicenceIsThereToRead() {
        val expected = mapOf(
            Licence.Apache2 to "Apache License",
            Licence.Epl2 to "Eclipse Public License - v 2.0",
            Licence.Bsd3 to "Redistribution and use in source and binary forms",
            Licence.Mit to "Jonathan Hedley",
            Licence.Ofl to "SIL OPEN FONT LICENSE",
        )
        for ((licence, words) in expected) assertTrue(licence.name, text(licence.text).contains(words))
        // the kit asks for its NOTICE and the fonts' licence to be shown by the apps that ship it
        val kit = THIRD_PARTIES.first { it.what == "desktop-kit" }
        assertTrue(text(kit.notice!!).contains("IBM Plex"))
        assertTrue(THIRD_PARTIES.any { it.licence == Licence.Ofl })
    }

    @Test
    fun aLicenceIsReadFromSettings() {
        val settings = SettingsUiState()
        compose.setContent { TestApp(testMail(), testPrefs(), testSettings(), startRoute = Routes.SETTINGS, settingsUi = settings) }
        compose.waitForIdle()

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Açık kaynak lisansları"))
        compose.onNodeWithText("Açık kaynak lisansları").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag(LicenceTags.LIST).performScrollToNode(hasText("jsoup"))
        compose.onNodeWithText("jsoup").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        compose.onNodeWithTag(LicenceTags.TEXT).assertExists()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Jonathan Hedley", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
