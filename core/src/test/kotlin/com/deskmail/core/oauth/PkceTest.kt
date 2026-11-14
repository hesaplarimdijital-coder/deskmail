package com.deskmail.core.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {

    @Test
    fun theChallengeIsTheRfcs() {
        // RFC 7636, appendix B
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun aVerifierIsLongEnoughAndNeverTheSame() {
        val verifiers = List(50) { Pkce.verifier() }
        assertEquals(50, verifiers.toSet().size)
        for (verifier in verifiers) {
            // 43 to 128 of the unreserved characters (RFC 7636, 4.1)
            assertEquals(43, verifier.length)
            assertTrue(verifier, verifier.all { it.isLetterOrDigit() || it in "-._~" })
            assertNotEquals(verifier, Pkce.challenge(verifier))
        }
    }
}
