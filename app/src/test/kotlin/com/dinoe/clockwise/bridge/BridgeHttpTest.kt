package com.dinoe.clockwise.bridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BridgeHttpTest {

    private fun request(raw: String) = BridgeHttp.parseRequest(ByteArrayInputStream(raw.toByteArray()))

    private fun failingRequest(raw: String): BridgeHttpException {
        try {
            request(raw)
        } catch (e: BridgeHttpException) {
            return e
        }
        fail("Expected BridgeHttpException")
        throw IllegalStateException()
    }

    @Test
    fun parsesRequestLineHeadersAndQuery() {
        val request = request(
            "GET /v1/events?from=2026-11-01&to=2026-11-30&limit=20 HTTP/1.1\r\n" +
                "Host: 192.168.1.50:8787\r\n" +
                "Authorization: Bearer abc\r\n\r\n",
        )
        assertEquals("/v1/events", request.path)
        assertEquals("2026-11-01", request.query["from"])
        assertEquals("2026-11-30", request.query["to"])
        assertEquals("20", request.query["limit"])
        assertEquals("Bearer abc", request.headers["authorization"])
        assertEquals("192.168.1.50:8787", request.headers["host"])
    }

    @Test
    fun decodesPercentEncodedQueryValues() {
        val request = request("GET /v1/events?from=2026%2D11%2D01 HTTP/1.1\r\n\r\n")
        assertEquals("2026-11-01", request.query["from"])
    }

    @Test
    fun toleratesRequestWithoutQuery() {
        val request = request("GET /v1/meta HTTP/1.1\r\n\r\n")
        assertEquals("/v1/meta", request.path)
        assertTrue(request.query.isEmpty())
    }

    @Test
    fun rejectsNonGetWith405() {
        assertEquals(405, failingRequest("POST /v1/meta HTTP/1.1\r\n\r\n").status)
    }

    @Test
    fun rejectsBodiesWith413() {
        assertEquals(
            413,
            failingRequest("GET /v1/meta HTTP/1.1\r\nContent-Length: 5\r\n\r\nhello").status,
        )
    }

    @Test
    fun rejectsMalformedRequestLine() {
        assertEquals(400, failingRequest("GARBAGE\r\n\r\n").status)
        assertEquals(400, failingRequest("").status)
    }

    @Test
    fun rejectsOversizedHead() {
        val oversized = "GET /" + "a".repeat(BridgeHttp.MAX_REQUEST_BYTES) + " HTTP/1.1\r\n\r\n"
        assertEquals(400, failingRequest(oversized).status)
    }

    @Test
    fun writesStatusHeadersAndBodyWithByteLength() {
        val output = ByteArrayOutputStream()
        BridgeHttp.writeResponse(
            output,
            BridgeResponse(200, "{\"ok\":\"範例\"}"),
        )
        val raw = output.toString(StandardCharsets.UTF_8)
        val body = "{\"ok\":\"範例\"}"
        val head = raw.substringBefore("\r\n\r\n") + "\r\n\r\n"
        assertEquals(body, raw.substringAfter("\r\n\r\n"))
        assertTrue(head.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(head.contains("Content-Type: application/json; charset=utf-8\r\n"))
        assertTrue(head.contains("Cache-Control: no-store\r\n"))
        assertTrue(head.contains("Connection: close\r\n"))
        val contentLength = head.lineSequence()
            .first { it.startsWith("Content-Length:") }
            .substringAfter("Content-Length: ")
            .trim()
            .toInt()
        assertEquals(body.toByteArray(StandardCharsets.UTF_8).size, contentLength)
    }
}
