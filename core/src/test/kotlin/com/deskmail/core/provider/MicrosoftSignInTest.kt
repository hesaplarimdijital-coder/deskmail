package com.deskmail.core.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrosoftSignInTest {

    @Test
    fun withoutARegistrationThereIsNoSignIn() {
        assertNull(MicrosoftSignIn.configFor(""))
        assertNull(MicrosoftSignIn.configFor("   "))
    }

    @Test
    fun personalAccountsSignInForMailOnly() {
        val config = MicrosoftSignIn.configFor(" 0f6e2c1a-1111-2222-3333-444455556666 ")!!
        assertEquals("0f6e2c1a-1111-2222-3333-444455556666", config.clientId)
        assertEquals("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize", config.authorizeUrl)
        assertEquals("https://login.microsoftonline.com/consumers/oauth2/v2.0/token", config.tokenUrl)
        assertEquals("com.deskmail://oauth2redirect", config.redirectUri)
        assertEquals("https://outlook.office.com/IMAP.AccessAsUser.All https://outlook.office.com/SMTP.Send offline_access", config.scope)
    }

    @Test
    fun onlyMicrosoftsAccountsSignInInTheBrowser() {
        assertNull(browserSignIn(AuthKind.Password))
        assertNull(browserSignIn(AuthKind.AppPassword, "0f6e2c1a-1111-2222-3333-444455556666"))
        assertEquals(MicrosoftSignIn.config, browserSignIn(AuthKind.MicrosoftOAuth))
    }

    @Test
    fun aRegistrationOfTheUsersOwnTakesTheAppsPlace() {
        val own = browserSignIn(AuthKind.MicrosoftOAuth, " 0f6e2c1a-1111-2222-3333-444455556666 ")!!
        assertEquals("0f6e2c1a-1111-2222-3333-444455556666", own.clientId)
        assertEquals("https://login.microsoftonline.com/consumers/oauth2/v2.0/token", own.tokenUrl)
        // empty: the app's own
        assertEquals(MicrosoftSignIn.config, browserSignIn(AuthKind.MicrosoftOAuth, "  "))
    }

    @Test
    fun aRegistrationsIdIsAGuid() {
        assertTrue(MicrosoftSignIn.isClientId("0f6e2c1a-1111-2222-3333-444455556666"))
        assertTrue(MicrosoftSignIn.isClientId(" 0F6E2C1A-1111-2222-3333-444455556666 "))
        assertFalse(MicrosoftSignIn.isClientId(""))
        assertFalse(MicrosoftSignIn.isClientId("0f6e2c1a-1111-2222-3333-44445555666"))
        assertFalse(MicrosoftSignIn.isClientId("DeskMail"))
        assertFalse(MicrosoftSignIn.isClientId("0f6e2c1a 1111 2222 3333 444455556666"))
    }
}
