package com.deskmail.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.FakeAccounts
import com.deskmail.TestApp
import com.deskmail.core.mail.MailException
import com.deskmail.core.provider.ProviderTable
import com.deskmail.demo.DemoMail
import com.deskmail.mail.AccountState
import com.deskmail.mail.SetupPlan
import com.deskmail.mail.SetupResult
import com.deskmail.testMail
import com.deskmail.testPrefs
import com.deskmail.testSettings
import com.deskmail.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › Accounts on a phone: adding an account, and an account's own page. The servers are made up. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class AccountPagesTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mail = testMail()
    private val settingsUi = SettingsUiState()

    /** The pages the app had the browser open. */
    private val opened = ArrayList<String>()
    private val browser = object : UriHandler {
        override fun openUri(uri: String) {
            opened += uri
        }
    }

    private fun launch(accounts: FakeAccounts) {
        val prefs = testPrefs()
        val settings = testSettings()
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides browser) {
                TestApp(mail, prefs, settings, startRoute = Routes.SETTINGS, accounts = accounts, settingsUi = settingsUi)
            }
        }
        compose.waitForIdle()
    }

    /** Presses the button named [text]. By its semantics action: the page lies under the phone's bars. */
    private fun press(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test
    fun theAccountsAreListedWithHowTheyAreDoing() {
        mail.setAccountState(DemoMail.GMAIL, AccountState.NeedsLogin)
        launch(FakeAccounts())
        compose.onNodeWithText("deniz@outlook.example · Çalışıyor").assertIsDisplayed()
        compose.onNodeWithText("deniz@gmail.example · Yeniden giriş gerekiyor").assertIsDisplayed()
    }

    @Test
    fun aGmailAccountIsAddedWithAnAppPassword() {
        val gmail = SetupPlan.Found(ProviderTable.Gmail.settingsFor("deniz.aksoy@gmail.com"), ProviderTable.Gmail)
        val accounts = FakeAccounts(plan = gmail, result = SetupResult.Done(7))
        launch(accounts)

        press("Hesap ekle")
        // a set-up of its own: the bottom navigation is away while it is open (desktop-kit 2.9.0)
        compose.onNode(hasText("Gelen") and isSelectable()).assertDoesNotExist()
        compose.onNodeWithTag(AccountTags.ADDRESS).performTextInput("deniz.aksoy@gmail.com")
        compose.waitForIdle()
        press("Devam")
        assertEquals(listOf("deniz.aksoy@gmail.com"), accounts.looked)
        // what Gmail asks for is said, and where its servers are
        compose.onNodeWithText("uygulama şifresi üret", substring = true).assertExists()
        compose.onNodeWithText("imap.gmail.com:993").assertExists()
        // the page where such a password is made is one tap away
        press("Uygulama şifresi sayfasını aç")
        assertEquals(listOf("https://myaccount.google.com/apppasswords"), opened)

        compose.onNodeWithTag(AccountTags.PASSWORD).performTextInput("abcd efgh ijkl mnop")
        compose.waitForIdle()
        press("Hesabı ekle")

        val draft = accounts.added.single()
        assertEquals("deniz.aksoy@gmail.com", draft.address)
        assertSame(ProviderTable.Gmail, draft.provider)
        assertEquals("abcd efgh ijkl mnop", draft.password)
        // back at the inbox, where the mail arrives, with the page closed behind
        assertNull(settingsUi.page)
        compose.onNode(hasText("Gelen") and isSelectable()).assertIsSelected()
        compose.onNodeWithText("deniz.aksoy@gmail.com eklendi. Postası geliyor.").assertExists()
    }

    @Test
    fun aRefusedSignInSaysWhatTheServerSaid() {
        val gmail = SetupPlan.Found(ProviderTable.Gmail.settingsFor("deniz.aksoy@gmail.com"), ProviderTable.Gmail)
        val accounts = FakeAccounts(plan = gmail, result = SetupResult.Failed(MailException.Kind.Authentication, "[AUTHENTICATIONFAILED] Invalid credentials (Failure)"))
        launch(accounts)
        press("Hesap ekle")
        compose.onNodeWithTag(AccountTags.ADDRESS).performTextInput("deniz.aksoy@gmail.com")
        compose.waitForIdle()
        press("Devam")
        compose.onNodeWithTag(AccountTags.PASSWORD).performTextInput("hesap-sifresi")
        compose.waitForIdle()
        press("Hesabı ekle")

        // still on the page, with the reason and the server's own words
        assertEquals(SettingsPage.AddAccount, settingsUi.page)
        compose.onNodeWithText("uygulama şifresi gerekir", substring = true).assertExists()
        compose.onNodeWithText("[AUTHENTICATIONFAILED] Invalid credentials (Failure)", substring = true).assertExists()
    }

    @Test
    fun outlookSaysItIsNotReadyYet() {
        val accounts = FakeAccounts(plan = SetupPlan.NotYet(ProviderTable.Outlook))
        launch(accounts)
        press("Hesap ekle")
        compose.onNodeWithTag(AccountTags.ADDRESS).performTextInput("deniz@outlook.com")
        compose.waitForIdle()
        press("Devam")
        compose.onNodeWithText("Bu henüz hazır değil", substring = true).assertExists()
        assertTrue(accounts.added.isEmpty())
    }

    @Test
    fun anUnknownAddressIsSetUpByHand() {
        val accounts = FakeAccounts(plan = SetupPlan.Manual, result = SetupResult.Done(7))
        launch(accounts)
        press("Hesap ekle")
        compose.onNodeWithTag(AccountTags.ADDRESS).performTextInput("deniz@kendi.example")
        compose.waitForIdle()
        press("Devam")
        // the usual names are filled in for the user to correct
        compose.onNodeWithText("imap.kendi.example").assertExists()
        compose.onNodeWithText("smtp.kendi.example").assertExists()
        // nobody's app passwords are offered where the provider is not known
        compose.onNodeWithText("Uygulama şifresi sayfasını aç").assertDoesNotExist()

        compose.onNodeWithTag(AccountTags.PASSWORD).performTextInput("parola")
        compose.waitForIdle()
        press("Hesabı ekle")
        val draft = accounts.added.single()
        assertNull(draft.provider)
        assertEquals("imap.kendi.example", draft.settings.imap.host)
        assertEquals(993, draft.settings.imap.port)
        assertEquals("deniz@kendi.example", draft.settings.username)
    }

    @Test
    fun anAccountSignsInAgainAndCanBeRemoved() {
        val accounts = FakeAccounts(result = SetupResult.Done(DemoMail.GMAIL))
        settingsUi.page = SettingsPage.Account(DemoMail.GMAIL)
        launch(accounts)
        compose.onNodeWithText("deniz@gmail.example").assertExists()
        compose.onNodeWithText("imap.gmail.com").assertExists()

        compose.onNodeWithTag(AccountTags.PASSWORD).performTextInput("yeni uygulama şifresi")
        compose.waitForIdle()
        // the section's heading has the same words: the button is the one that can be pressed
        compose.onNode(hasText("Yeniden bağlan") and androidx.compose.ui.test.hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf(DemoMail.GMAIL to "yeni uygulama şifresi"), accounts.reconnected)
        compose.onNodeWithText("Yeniden giriş yapıldı.").assertExists()

        // removing asks first
        compose.onNode(hasText("Hesabı kaldır") and androidx.compose.ui.test.hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("Gmail kaldırılsın mı?").assertExists()
        assertTrue(accounts.removed.isEmpty())
        press("Vazgeç")
        compose.onNodeWithText("Gmail kaldırılsın mı?").assertDoesNotExist()
        assertTrue(accounts.removed.isEmpty())
    }

    @Test
    fun theNameAndTheSignatureAnAccountWritesWithAreSaved() {
        val accounts = FakeAccounts()
        settingsUi.page = SettingsPage.Account(DemoMail.GMAIL)
        launch(accounts)

        compose.onNodeWithTag(AccountTags.SENDER_NAME).performTextInput("Deniz Aksoy")
        compose.onNodeWithTag(AccountTags.SIGNATURE).performTextInput("Deniz Aksoy\nİstanbul")
        compose.waitForIdle()
        press("Kaydet")

        assertEquals(listOf(Triple(DemoMail.GMAIL, "Deniz Aksoy", "Deniz Aksoy\nİstanbul")), accounts.written)
        compose.onNodeWithText("Kaydedildi.").assertExists()
    }

    @Test
    fun theSettingsListLeadsToAnAccountsPage() {
        launch(FakeAccounts())
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("deniz@gmail.example · Çalışıyor"))
        // an account's whole row leads to its page
        compose.onNode(hasText("deniz@gmail.example · Çalışıyor") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(SettingsPage.Account(DemoMail.GMAIL), settingsUi.page)
    }
}
