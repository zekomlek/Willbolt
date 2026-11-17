package app.willbolt.core

import app.willbolt.data.Password
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Willbolt's one password, kept only as a salted PBKDF2 hash. Checking one takes a moment on purpose, so call these
 * off the main thread. The rules around it (changing it, the 4-day way back, pauses after wrong tries) are in [Policy].
 */
object Passwords {
    const val MIN_LENGTH = 4

    /** Without the old password, a new one can be set this long after asking for it. */
    const val RESET_WAIT_MS = 4 * 24 * 3_600_000L

    /** Every [TRIES] wrong passwords in a row earn a pause of [PAUSE_MS]. */
    const val TRIES = 5
    const val PAUSE_MS = 5 * 60_000L

    private const val ROUNDS = 20_000

    fun make(pw: String, rounds: Int = ROUNDS): Password {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Password(b64(derive(pw, salt, rounds)), b64(salt), rounds)
    }

    fun matches(p: Password, pw: String): Boolean = try {
        val expected = Base64.getDecoder().decode(p.hash)
        MessageDigest.isEqual(expected, derive(pw, Base64.getDecoder().decode(p.salt), p.rounds))
    } catch (_: Throwable) {
        false
    }

    private fun derive(pw: String, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(pw.toCharArray(), salt, rounds, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
}
