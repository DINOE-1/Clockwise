package com.dinoe.clockwise.bridge

import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** A parsed GET request. Header keys in [headers] are lowercased. */
data class BridgeRequest(
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
)

data class BridgeResponse(
    val status: Int,
    val body: String,
)

/** A protocol-level failure that maps directly to an HTTP status code. */
class BridgeHttpException(val status: Int, message: String) : Exception(message)

/**
 * Minimal HTTP/1.1 parsing and response writing for the bridge. GET only, no request bodies,
 * no keep-alive: every response carries `Connection: close`.
 */
object BridgeHttp {
    const val MAX_REQUEST_BYTES = 8 * 1024
    const val SOCKET_TIMEOUT_MS = 5_000

    private const val CR: Byte = 0x0D
    private const val LF: Byte = 0x0A

    private val STATUS_TEXT = mapOf(
        200 to "OK",
        400 to "Bad Request",
        401 to "Unauthorized",
        404 to "Not Found",
        405 to "Method Not Allowed",
        413 to "Payload Too Large",
        500 to "Internal Server Error",
    )

    /** Reads and parses one request head. Throws [BridgeHttpException] for malformed input. */
    fun parseRequest(input: InputStream): BridgeRequest {
        val head = readHead(input)
        val lines = head.lineSequence().toList()
        val requestLine = lines.firstOrNull() ?: throw BridgeHttpException(400, "Empty request")
        val parts = requestLine.split(" ")
        if (parts.size < 3) throw BridgeHttpException(400, "Malformed request line")
        if (parts[0] != "GET") throw BridgeHttpException(405, "Only GET requests are supported")

        val headers = mutableMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }
        }
        headers["content-length"]?.toIntOrNull()?.let { contentLength ->
            if (contentLength > 0) {
                throw BridgeHttpException(413, "Request bodies are not supported")
            }
        }

        val querySeparator = parts[1].indexOf('?')
        val rawPath = if (querySeparator < 0) parts[1] else parts[1].substring(0, querySeparator)
        val rawQuery = if (querySeparator < 0) null else parts[1].substring(querySeparator + 1)
        return BridgeRequest(path = decode(rawPath), query = parseQuery(rawQuery), headers = headers)
    }

    fun writeResponse(output: OutputStream, response: BridgeResponse) {
        val body = response.body.toByteArray(StandardCharsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ")
            append(response.status)
            append(' ')
            append(STATUS_TEXT[response.status] ?: "OK")
            append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ")
            append(body.size)
            append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(StandardCharsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private fun readHead(input: InputStream): String {
        val buffer = ByteArray(MAX_REQUEST_BYTES)
        var size = 0
        while (size < MAX_REQUEST_BYTES) {
            val byte = input.read()
            if (byte == -1) break
            buffer[size++] = byte.toByte()
            if (size >= 4 &&
                buffer[size - 4] == CR && buffer[size - 3] == LF &&
                buffer[size - 2] == CR && buffer[size - 1] == LF
            ) {
                return String(buffer, 0, size - 4, StandardCharsets.UTF_8)
            }
        }
        throw BridgeHttpException(400, "Malformed request head")
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val query = mutableMapOf<String, String>()
        rawQuery.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val separator = pair.indexOf('=')
            val key = if (separator < 0) pair else pair.substring(0, separator)
            val value = if (separator < 0) "" else pair.substring(separator + 1)
            query[decode(key)] = decode(value)
        }
        return query
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: java.io.UnsupportedEncodingException) {
        value
    }
}
