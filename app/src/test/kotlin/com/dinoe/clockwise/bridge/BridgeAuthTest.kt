package com.dinoe.clockwise.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeAuthTest {

    private val token = BridgeAuth.generateToken()

    @Test
    fun generatedTokenIsUrlSafeWithoutPadding() {
        assertEquals(43, token.length)
        assertTrue(token.none { it == '=' || it == '+' || it == '/' })
    }

    @Test
    fun generatedTokensAreUnique() {
        assertNotEquals(token, BridgeAuth.generateToken())
    }

    @Test
    fun acceptsMatchingBearerHeader() {
        assertTrue(BridgeAuth.isAuthorized("Bearer $token", token))
    }

    @Test
    fun rejectsWrongToken() {
        assertFalse(BridgeAuth.isAuthorized("Bearer ${BridgeAuth.generateToken()}", token))
    }

    @Test
    fun rejectsMissingHeader() {
        assertFalse(BridgeAuth.isAuthorized(null, token))
    }

    @Test
    fun rejectsMalformedHeader() {
        assertFalse(BridgeAuth.isAuthorized(token, token))
        assertFalse(BridgeAuth.isAuthorized("Basic $token", token))
        assertFalse(BridgeAuth.isAuthorized("bearer $token", token))
        assertFalse(BridgeAuth.isAuthorized("Bearer  $token", token))
    }

    @Test
    fun rejectsEmptyExpectedToken() {
        assertFalse(BridgeAuth.isAuthorized("Bearer $token", ""))
    }
}
