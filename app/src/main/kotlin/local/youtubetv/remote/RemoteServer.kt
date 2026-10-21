package local.youtubetv.remote

import local.youtubetv.apikey.isLocalNetworkAddress
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** The port the remote asks for first. The Key page has 8642, so both can run at once. */
const val REMOTE_PREFERRED_PORT = 8643

/** The most a request may carry, head and body each. A command is a few dozen bytes. */
private const val REMOTE_MAX_BYTES = 8 * 1024

private const val READ_TIMEOUT_MS = 10_000

/**
 * The small web server behind the phone remote.
 *
 * It is the Key page's server with a second route table: `java.net.ServerSocket`, one
 * request at a time, the home network only, and one token in the path. See
 * `apikey/KeyPageServer.kt` for why there is no library here.
 *
 * It runs while the app is on screen. The token is kept across launches, so the page the
 * phone has bookmarked keeps working; the Key page's token is new every time because a key
 * is a credential and the remote is not.
 *
 * @param snapshot what the television is showing. Called on the server's thread; the
 *   implementation hops to the main thread and may block briefly.
 * @param videos the videos of one Source, read from storage. Called on the server's thread.
 * @param onCommand runs one command. Called on the server's thread; the implementation posts
 *   to the main thread and returns at once.
 */
class RemoteServer(
    private val token: String,
    private val snapshot: () -> RemoteSnapshot?,
    private val videos: (sourceId: String) -> List<RemoteVideo>,
    private val onCommand: (RemoteCommand) -> Unit,
    private val log: (String) -> Unit = {},
    private val preferredPort: Int = REMOTE_PREFERRED_PORT,
    private val acceptFrom: (InetAddress) -> Boolean = ::isLocalNetworkAddress,
) {
    @Volatile
    private var socket: ServerSocket? = null
    private var worker: ExecutorService? = null

    val port: Int? get() = socket?.takeUnless { it.isClosed }?.localPort

    @Synchronized
    fun start(): Int {
        port?.let { return it }
        val server = try {
            ServerSocket(preferredPort)
        } catch (taken: BindException) {
            ServerSocket(0)
        }
        socket = server
        val handlers = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "remote-request").apply { isDaemon = true }
        }
        worker = handlers
        Thread({ acceptLoop(server, handlers) }, "remote-accept").apply {
            isDaemon = true
            start()
        }
        log("The phone remote is listening on port ${server.localPort}.")
        return server.localPort
    }

    @Synchronized
    fun stop() {
        val server = socket ?: return
        socket = null
        runCatching { server.close() }
        worker?.shutdownNow()
        worker = null
        log("The phone remote stopped listening.")
    }

    private fun acceptLoop(server: ServerSocket, handlers: ExecutorService) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (closed: SocketException) {
                return
            } catch (failure: IOException) {
                continue
            }
            if (!acceptFrom(client.inetAddress)) {
                runCatching { client.close() }
                log("Refused a connection to the remote from outside the home network.")
                continue
            }
            runCatching { handlers.execute { serve(client) } }
                .onFailure { runCatching { client.close() } }
        }
    }

    private fun serve(client: Socket) {
        client.use { connection ->
            runCatching {
                connection.soTimeout = READ_TIMEOUT_MS
                val input = connection.getInputStream()
                val output = connection.getOutputStream()
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val requestLine = lines.first().split(' ')
                if (requestLine.size < 2) return respond(output, 400, "text/plain", "Bad request")
                val method = requestLine[0]
                val target = requestLine[1]
                val length = lines.drop(1)
                    .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                if (length !in 0..REMOTE_MAX_BYTES) return respond(output, 413, "text/plain", "Too large")
                val body = readBody(input, length)
                when (val request = routeRemoteRequest(method, target, body, token)) {
                    RemoteRequest.NotFound -> respond(output, 404, "text/plain", "Not found")
                    RemoteRequest.MethodNotAllowed -> respond(output, 405, "text/plain", "Not allowed")
                    RemoteRequest.BadCommand -> respond(output, 400, "text/plain", "Unknown command")
                    RemoteRequest.ShowPage -> respond(
                        output,
                        200,
                        "text/html; charset=utf-8",
                        remotePageHtml("/$token"),
                        headOnly = method == "HEAD",
                    )
                    RemoteRequest.State -> {
                        val state = snapshot()
                        if (state == null) {
                            respond(output, 503, "text/plain", "Not ready")
                        } else {
                            respond(output, 200, "application/json; charset=utf-8", state.toJson())
                        }
                    }
                    is RemoteRequest.Videos -> {
                        val (page, total) = pageOfVideos(
                            videos(request.sourceId),
                            request.offset,
                            request.limit,
                            request.query,
                            request.sort,
                        )
                        respond(output, 200, "application/json; charset=utf-8", videosJson(page, total, request.offset))
                    }
                    is RemoteRequest.Command -> {
                        log("Remote command: ${request.command}")
                        onCommand(request.command)
                        respond(output, 200, "application/json; charset=utf-8", "{\"ok\":true}")
                    }
                }
            }
        }
    }

    private fun readHead(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (bytes.size() < REMOTE_MAX_BYTES) {
            val next = input.read()
            if (next < 0) return null
            bytes.write(next)
            matched = if (next.toByte() == end[matched]) matched + 1 else if (next == '\r'.code) 1 else 0
            if (matched == end.size) {
                return bytes.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
            }
        }
        return null
    }

    private fun readBody(input: InputStream, length: Int): String {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(bytes, read, length - read)
            if (count < 0) break
            read += count
        }
        return String(bytes, 0, read, Charsets.UTF_8)
    }

    private fun respond(output: OutputStream, status: Int, type: String, body: String, headOnly: Boolean = false) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            503 -> "Service Unavailable"
            else -> "Payload Too Large"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            if (type.startsWith("text/html")) {
                append("Content-Security-Policy: $REMOTE_CONTENT_SECURITY_POLICY\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) output.write(bytes)
        output.flush()
    }
}

/** The address the QR code holds and the screen prints. */
fun remotePageUrl(host: String, port: Int, token: String): String = "http://$host:$port/$token"
