package com.bido.budgetsync.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs requests with the pairing code, so the code itself never travels over the network.
 *
 * The phone sends `X-BC-Time`, `X-BC-Nonce` and `X-BC-Sig` = HMAC-SHA256(code, method \n path \n time \n nonce \n
 * sha256(body)). BaseCamp answers with `X-BC-Proof` = HMAC-SHA256(code, "reply" \n nonce), so a device that does not know
 * the code can neither talk to BaseCamp nor pose as it. Kept identical in BaseCamp, ToDo and Budget Tracker.
 */
object RequestSigner {
    const val TIME = "X-BC-Time"
    const val NONCE = "X-BC-Nonce"
    const val SIG = "X-BC-Sig"
    const val PROOF = "X-BC-Proof"
    /** How far the phone's clock may be from the laptop's. */
    const val MAX_SKEW_MS = 10 * 60 * 1000L

    /** The code as typed, without case, dashes or spaces. */
    fun normalize(code: String) = code.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    fun newNonce(): String = hex(ByteArray(16).also { SecureRandom().nextBytes(it) })

    fun signature(code: String, method: String, path: String, time: Long, nonce: String, body: ByteArray?): String =
        hmac(code, listOf(method.uppercase(), path, time.toString(), nonce, hex(sha256(body ?: ByteArray(0)))).joinToString("\n"))

    fun proof(code: String, nonce: String): String = hmac(code, "reply\n$nonce")

    /** The headers that sign one request. Keep [nonce] to check the reply's proof with [provesCode]. */
    fun headers(
        code: String, method: String, path: String, body: ByteArray?,
        nonce: String = newNonce(), time: Long = System.currentTimeMillis(),
    ): Map<String, String> = mapOf(TIME to time.toString(), NONCE to nonce, SIG to signature(code, method, path, time, nonce, body))

    /** True if [sentProof] shows the other side knows [code]. */
    fun provesCode(sentProof: String?, code: String, nonce: String): Boolean = same(sentProof, proof(code, nonce))

    /** Constant-time comparison of two hex strings. */
    fun same(a: String?, b: String): Boolean = a != null && MessageDigest.isEqual(a.lowercase().toByteArray(), b.toByteArray())

    private fun hmac(code: String, message: String): String {
        val key = normalize(code).ifEmpty { "-" }.toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return hex(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
