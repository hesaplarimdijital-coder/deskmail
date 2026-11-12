package com.deskmail.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.mail.FolderRole
import com.deskmail.demo.DemoMail
import com.deskmail.mail.MailFolder
import com.deskmail.testPrefs
import desktopkit.shell.DesktopShell
import desktopkit.shell.ShellTransitions
import desktopkit.shell.WidthClass
import desktopkit.theme.DensityPreference
import desktopkit.theme.DesktopKitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The shell around the app: how each window size reaches the folders, and what a changed folder list does. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outlookCaption = "Outlook · deniz@outlook.example"

    /** A phone reaches a folder through the Folders tab; the folder opens on top of the list and Back returns to it. */
    @Test
    @Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
    fun aPhoneOpensAFolderOnTopOfTheFolderList() {
        val prefs = testPrefs()
        compose.setContent { DeskMailApp(mail = DemoMail(), prefs = prefs) }
        compose.onNode(hasText("Klasörler") and isSelectable()).performClick()
        compose.onNodeWithText(outlookCaption).assertExists()

        compose.onNodeWithText("Faturalar").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(outlookCaption).assertDoesNotExist()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText(outlookCaption).assertExists()
    }

    /** A large window lists every account's folders in the sidebar, a folder inside another one by its path. */
    @Test
    @Config(qualifiers = "tr-w1280dp-h800dp-mdpi")
    fun aLargeWindowListsTheFoldersInTheSidebar() {
        val prefs = testPrefs(density = DensityPreference.Pointer)
        compose.setContent { DeskMailApp(mail = DemoMail(), prefs = prefs) }
        compose.onNode(hasText("Gelen") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("Projeler / 2026") and isSelectable()).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Projeler / 2026") and isSelectable()).assertIsSelected()
    }

    /**
     * What desktop-kit 2.4.0 does when the list of folders changes (docs/KIT-ISTEKLERI.md, 3). The
     * kit builds its navigation graph from the ShellSpec. The same routes in a new spec (a renamed
     * label, another language) keep the open screen and its state; one route more (a folder made on
     * the server, an account added) is another graph: the shell returns to its start screen, and the
     * state the screens saved is gone. Until the kit keeps it, the app holds what must survive
     * outside the screens and goes back to the open folder itself.
     */
    @Test
    @Config(qualifiers = "tr-w1280dp-h800dp-mdpi")
    fun aNewFolderSendsTheShellBackToItsStartScreen() {
        val mail = DemoMail()
        var folders by mutableStateOf(mail.folders)
        var relabelled by mutableIntStateOf(0)
        compose.setContent {
            val resources = LocalContext.current.resources
            val spec = remember(folders, relabelled) { buildShellSpec(resources, mail.accounts, folders) }
            DesktopKitTheme {
                DesktopShell(spec, WidthClass.Large, transitions = ShellTransitions.None) { route, _ -> CounterScreen(route) }
            }
        }
        compose.onNode(hasText("Faturalar") and isSelectable()).performScrollTo().performClick()
        repeat(2) { compose.onNodeWithTag("counter").performClick() }
        compose.onNodeWithTag("counter").assertTextEquals("folder/17: 2")

        // the same routes in a new spec: nothing moves
        relabelled++
        compose.waitForIdle()
        compose.onNodeWithTag("counter").assertTextEquals("folder/17: 2")

        // a folder more: back at the start screen
        folders = folders + MailFolder(28, DemoMail.OUTLOOK, listOf("Yeni klasör"), FolderRole.Other)
        compose.waitForIdle()
        compose.onNodeWithTag("counter").assertTextEquals("inbox: 0")

        // and the folder that was open has lost what it saved
        compose.onNode(hasText("Faturalar") and isSelectable()).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("counter").assertTextEquals("folder/17: 0")
    }
}

@Composable
private fun CounterScreen(route: String) {
    var count by rememberSaveable { mutableIntStateOf(0) }
    Text("$route: $count", Modifier.testTag("counter").clickable { count++ })
}
