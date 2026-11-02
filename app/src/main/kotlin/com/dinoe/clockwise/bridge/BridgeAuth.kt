package com.dinoe.clockwise.bridge

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Token generation and bearer-token checks for the bridge. Tokens are 32 random bytes encoded as
 * unpadded Base64URL and compared with [MessageDigest.isEqual] so the check does not leak timing.
 */
object BridgeAuth {
    private const val BEARER_PREFIX = "Bearer "

    private val random = SecureRandom()

    fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** True only for a well-formed `Authorization: Bearer <token>` header matching [expectedToken]. */
    fun isAuthorized(authorizationHeader: String?, expectedToken: String): Boolean {
        if (authorizationHeader == null || expectedToken.isEmpty()) return false
        if (!authorizationHeader.startsWith(BEARER_PREFIX)) return false
        val provided = authorizationHeader.substring(BEARER_PREFIX.length)
            .toByteArray(StandardCharsets.UTF_8)
        val expected = expectedToken.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(provided, expected)
    }

    private const val TOKEN_BYTES = 32
}
