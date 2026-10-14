package com.bido.budgetsync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same vectors are checked in BaseCamp and the ToDo app, so all three sign alike. */
class RequestSignerTest {
    private val nonce = "00112233445566778899aabbccddeeff"

    @Test fun matchesTheSharedVectors() {
        assertEquals(
            "c6f789c2b75563925fdeda34ec6b17048a29a1c960ad1c0871c66a9ba481fd70",
            RequestSigner.signature("abcd-efgh-jkmn-pqrs", "POST", "/api/todo/sync", 1_700_000_000_000, nonce, """{"x":1}""".toByteArray()),
        )
        assertEquals("bdd4477020006ab580172d19fdb58ca866d20a5771d514a191e758288cf0a785", RequestSigner.proof("ABCDEFGHJKMNPQRS", nonce))
    }

    @Test fun onlyTheRightCodeProvesItself() {
        val proof = RequestSigner.proof("ABCD-EFGH-JKMN-PQRS", nonce)
        assertTrue(RequestSigner.provesCode(proof, "abcdefghjkmnpqrs", nonce))
        assertFalse(RequestSigner.provesCode(proof, "ABCDEFGHJKMNPQRT", nonce))
        assertFalse(RequestSigner.provesCode(null, "ABCDEFGHJKMNPQRS", nonce))
    }
}
