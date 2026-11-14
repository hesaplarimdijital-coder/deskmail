package com.deskmail.core.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        assertNull(browserSignIn(AuthKind.AppPassword))
        assertSame(MicrosoftSignIn.config, browserSignIn(AuthKind.MicrosoftOAuth))
    }
}
