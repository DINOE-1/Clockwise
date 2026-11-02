package com.dinoe.clockwise.bridge

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Blocking accept-loop HTTP server for the bridge. One daemon accept thread plus a small fixed
 * worker pool; every connection is closed after a single response.
 */
class BridgeServer(
    private val port: Int,
    private val handler: (BridgeRequest) -> BridgeResponse,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers: ExecutorService? = null

    /** Binds the port and starts serving. Throws [IOException] with a readable message on bind failure. */
    @Synchronized
    fun start() {
        check(serverSocket == null) { "Bridge server already started" }
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port), BACKLOG)
            }
        } catch (e: IOException) {
            throw IOException(
                "Unable to bind port $port. Another app may be using it; pick a different port in Settings.",
                e,
            )
        }
        val pool = Executors.newFixedThreadPool(WORKER_COUNT) { runnable ->
            Thread(runnable, "bridge-worker").apply { isDaemon = true }
        }
        val thread = Thread({ acceptLoop(socket, pool) }, "bridge-accept").apply {
            isDaemon = true
            start()
        }
        serverSocket = socket
        workers = pool
        acceptThread = thread
    }

    @Synchronized
    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: IOException) {
            // Closing a socket that is already dead is fine.
        }
        workers?.shutdownNow()
        serverSocket = null
        workers = null
        acceptThread = null
    }

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                if (socket.isClosed) return else continue
            }
            try {
                pool.execute { serve(client) }
            } catch (_: Exception) {
                // Pool shut down while stopping; drop the connection.
                runCatching { client.close() }
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = BridgeHttp.SOCKET_TIMEOUT_MS
            socket.use { client ->
                val response = try {
                    handler(BridgeHttp.parseRequest(client.getInputStream()))
                } catch (e: BridgeHttpException) {
                    BridgeResponse(
                        e.status,
                        BridgeJson.error(BridgeJson.errorCodeFor(e.status), e.message ?: "").toString(),
                    )
                } catch (_: Exception) {
                    BridgeResponse(
                        500,
                        BridgeJson.error("internal", "Unexpected error while handling the request").toString(),
                    )
                }
                BridgeHttp.writeResponse(client.getOutputStream(), response)
            }
        } catch (_: IOException) {
            // The client went away mid-request; nothing to answer to.
        }
    }

    companion object {
        const val BACKLOG = 8
        const val WORKER_COUNT = 4
    }
}
