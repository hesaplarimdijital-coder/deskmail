package com.deskmail.core.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonTest {

    @Test
    fun aTokenAnswerIsReadMemberByMember() {
        val answer = Json.obj(
            """
            {
              "token_type": "Bearer",
              "scope": "https://outlook.office.com/IMAP.AccessAsUser.All https://outlook.office.com/SMTP.Send",
              "expires_in": 3599,
              "ext_expires_in": 3599,
              "access_token": "EwBIA+l3BAAUcDnR",
              "refresh_token": "M.C540_BAY.0.U.-Cg*b!5"
            }
            """.trimIndent(),
        )
        assertEquals("Bearer", answer.string("token_type"))
        assertEquals(3599L, answer.long("expires_in"))
        assertEquals("EwBIA+l3BAAUcDnR", answer.string("access_token"))
        assertEquals("M.C540_BAY.0.U.-Cg*b!5", answer.string("refresh_token"))
        // in the order they came
        assertEquals(listOf("token_type", "scope", "expires_in", "ext_expires_in", "access_token", "refresh_token"), answer.keys.toList())
    }

    @Test
    fun anErrorWithArraysAndNestedObjects() {
        val answer = Json.obj(
            """{"error":"invalid_grant","error_codes":[70000, 9002313],"inner":{"a":[true,false,null,{"b":-1.5e2}]},"empty":{},"none":[]}""",
        )
        assertEquals("invalid_grant", answer.string("error"))
        assertEquals(listOf(70000.0, 9002313.0), answer["error_codes"])
        assertEquals(mapOf("a" to listOf(true, false, null, mapOf("b" to -150.0))), answer["inner"])
        assertEquals(emptyMap<String, Any?>(), answer["empty"])
        assertEquals(emptyList<Any?>(), answer["none"])
    }

    @Test
    fun escapesAreUndone() {
        val answer = Json.obj("""{"s":"a\"b\\c\/d\n\t\u00e7\u011f\ud83d\udce8"}""")
        assertEquals("a\"b\\c/d\n\tçğ\uD83D\uDCE8", answer.string("s"))
    }

    @Test
    fun aNumberAsAStringCountsToo() {
        // the older Microsoft endpoints wrote their numbers so
        assertEquals(3600L, Json.obj("""{"expires_in":"3600"}""").long("expires_in"))
        assertNull(Json.obj("""{"expires_in":"soon"}""").long("expires_in"))
        assertNull(Json.obj("""{"expires_in":true}""").long("expires_in"))
        assertNull(Json.obj("""{"other":1}""").long("expires_in"))
        // a member that is not a string is no string
        assertNull(Json.obj("""{"access_token":12}""").string("access_token"))
    }

    @Test
    fun whatIsNoObjectIsRefused() {
        for (text in listOf("", "  ", "[1,2]", "\"text\"", "{", "{\"a\":}", "{\"a\":1,}", "{\"a\" 1}", "{\"a\":1} x", "{a:1}", "{\"a\":tru}", "{\"a\":\"open}", "{\"a\":\"\\x\"}", "<html>")) {
            assertThrows(text, IllegalArgumentException::class.java) { Json.obj(text) }
        }
    }

    @Test
    fun aHostileNestingEndsWithNoStackSpent() {
        val deep = "{\"a\":" + "[".repeat(10_000) + "]".repeat(10_000) + "}"
        assertThrows(IllegalArgumentException::class.java) { Json.obj(deep) }
    }
}
