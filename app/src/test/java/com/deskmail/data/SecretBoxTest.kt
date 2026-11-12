package com.deskmail.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException

/** The sealing itself, with a key of the test's own; on a device the key comes from the keystore. */
class SecretBoxTest {

    private val box = testBox()

    @Test
    fun whatIsSealedOpensAgain() {
        val sealed = box.seal("abcd efgh ijkl mnop".toByteArray(), "credential/1")
        assertEquals("abcd efgh ijkl mnop", String(box.open(sealed, "credential/1")))
    }

    @Test
    fun theSameSecretNeverLooksTheSameTwice() {
        val first = box.seal("parola".toByteArray(), "credential/1")
        val second = box.seal("parola".toByteArray(), "credential/1")
        assertFalse(first.iv.contentEquals(second.iv))
        assertFalse(first.data.contentEquals(second.data))
        // and neither holds the secret as it was typed
        assertFalse(String(first.data, Charsets.ISO_8859_1).contains("parola"))
        assertArrayEquals(box.open(first, "credential/1"), box.open(second, "credential/1"))
    }

    @Test
    fun aSecretOnlyOpensForWhatItWasSealedFor() {
        val sealed = box.seal("parola".toByteArray(), "credential/1")
        // copied onto another account's row
        assertThrows(GeneralSecurityException::class.java) { box.open(sealed, "credential/2") }
    }

    @Test
    fun whatWasChangedOrSealedElsewhereDoesNotOpen() {
        val sealed = box.seal("parola".toByteArray(), "credential/1")
        val changed = sealed.data.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { box.open(Sealed(sealed.iv, changed), "credential/1") }
        // another device's key
        assertThrows(GeneralSecurityException::class.java) { testBox(seed = 2).open(sealed, "credential/1") }
    }
}
