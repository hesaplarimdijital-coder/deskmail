package com.deskmail.ui.compose

import android.content.Intent
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.mail.MailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Choosing recipients from the contacts: what the system is asked, and what is made of its answer. */
@RunWith(AndroidJUnit4::class)
class ContactPickTest {

    /** Android 17: the system's own picker, several people at once, their email addresses alone. */
    @Test
    fun android17AsksItsOwnPickerForEmailAddresses() {
        val intent = ContactPick.intent(sdk = 37)
        assertEquals("android.provider.action.PICK_CONTACTS", intent.action)
        assertEquals(listOf(Email.CONTENT_ITEM_TYPE), intent.getStringArrayListExtra("android.provider.extra.PICK_CONTACTS_REQUESTED_DATA_FIELDS"))
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    /** Before that the contacts app picks one address. Neither needs leave to read the contacts. */
    @Test
    fun earlierAndroidAsksTheContactsAppForOneAddress() {
        val intent = ContactPick.intent(sdk = 36)
        assertEquals(Intent.ACTION_PICK, intent.action)
        assertEquals(Email.CONTENT_TYPE, intent.type)
    }

    @Test
    fun onlyEmailAddressesAreTakenEachOnce() {
        val rows = listOf(
            ContactPick.Row("Ayşe Yılmaz", Email.CONTENT_ITEM_TYPE, "ayse@posta.example"),
            ContactPick.Row("Ayşe Yılmaz", Phone.CONTENT_ITEM_TYPE, "+90 555 000 00 00"),
            ContactPick.Row("Ayşe Y.", Email.CONTENT_ITEM_TYPE, "AYSE@posta.example"),
            ContactPick.Row("Bozuk", Email.CONTENT_ITEM_TYPE, "adres değil"),
            // the contacts app's single answer says nothing of its kind
            ContactPick.Row(" Elif Şahin ", null, " elif@isyeri.example "),
        )

        assertEquals(
            listOf(MailAddress("Ayşe Yılmaz", "ayse@posta.example"), MailAddress("Elif Şahin", "elif@isyeri.example")),
            ContactPick.addresses(rows),
        )
    }

    @Test
    fun whatIsPickedComesAfterWhatWasTypedAndNobodyTwice() {
        val ayse = MailAddress("Ayşe Yılmaz", "ayse@posta.example")
        val elif = MailAddress("Elif Şahin", "elif@isyeri.example")

        assertEquals("Ayşe Yılmaz <ayse@posta.example>", ContactPick.append("", listOf(ayse)))
        assertEquals("mehmet@isyeri.example, Ayşe Yılmaz <ayse@posta.example>", ContactPick.append("mehmet@isyeri.example, ", listOf(ayse)))
        assertEquals(
            "ayse@posta.example, Elif Şahin <elif@isyeri.example>",
            ContactPick.append("ayse@posta.example", listOf(ayse, elif)),
        )
        // nothing new: what was typed stays as it was typed
        assertEquals("ayse@posta.example;", ContactPick.append("ayse@posta.example;", listOf(ayse)))
    }
}
