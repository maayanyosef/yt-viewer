package local.youtubetv.apikey

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** The port the Key page asks for first. Any free port is used when it is taken. */
const val KEY_PAGE_PREFERRED_PORT = 8642

/** The most a request may carry, head and body each. A key is about 40 characters. */
internal const val KEY_PAGE_MAX_BYTES = 8 * 1024

/** How long a connection may sit without sending anything before it is dropped. */
private const val READ_TIMEOUT_MS = 10_000

/** What one request to the Key page asks for. The routing is a pure function. */
sealed interface KeyPageAction {
    /** `GET` or `HEAD` on the token's own path. */
    data object ShowPage : KeyPageAction

    /** `POST` on the token's own path, with the key it carried, already normalized. */
    data class Submit(val key: String) : KeyPageAction

    /** Any other path, which includes every path with a wrong token. */
    data object NotFound : KeyPageAction

    /** The right path, with a method the page does not answer. */
    data object MethodNotAllowed : KeyPageAction
}

/**
 * Decides what one request is. Build ticket 64.
 *
 * **The token is the whole of the path, and nothing else is served.** A wrong token is
 * answered exactly like a path that does not exist, so a device guessing learns nothing from
 * the answer. A query string is ignored.
 *
 * @param body the request body, already read. Only a `POST` looks at it.
 */
fun routeKeyPageRequest(method: String, target: String, body: String, token: String): KeyPageAction {
    val path = target.substringBefore('?').trimEnd('/')
    if (!path.startsWith("/") || !tokenMatches(path.removePrefix("/"), token)) {
        return KeyPageAction.NotFound
    }
    return when (method) {
        "GET", "HEAD" -> KeyPageAction.ShowPage
        "POST" -> KeyPageAction.Submit(normalizeKey(formField(body, "key").orEmpty()))
        else -> KeyPageAction.MethodNotAllowed
    }
}

/** One field of an `application/x-www-form-urlencoded` body, or null when it is absent. */
internal fun formField(body: String, name: String): String? =
    body.split('&').firstNotNullOfOrNull { pair ->
        val field = pair.substringBefore('=')
        if (field != name) return@firstNotNullOfOrNull null
        runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrNull()
    }

/**
 * The small web server behind the key screen's QR code. Build ticket 64.
 *
 * - It runs only between [start] and [stop], which the key screen calls as it opens and closes.
 * - It answers only the one path that is [token], so another device on the network cannot set
 *   a key without the QR code.
 * - It refuses any connection that is not from the home network or the device itself. See
 *   [isLocalNetworkAddress].
 * - It serves one page and accepts one `POST`. Nothing else.
 * - **It never writes the key anywhere.** The key goes from the request body to [onKey] and
 *   nowhere else, and nothing here logs a body.
 *
 * No library. It is `java.net.ServerSocket` and about a hundred lines, which is less to keep
 * current than an embedded server, and every line of it is tested on the JVM.
 *
 * Requests are answered one at a time, on one thread. A key check takes a second or two, and
 * one phone sends one key.
 *
 * @param onKey checks and saves a key, and says what happened. Called on the server's own
 *   thread, so it may block.
 * @param log one line per event. Never handed a key or a body.
 * @param pageAddress the address the television prints right now, or null. The page shows it
 *   for sharing. It is read once per page, so a changed address shows on the next load. Build
 *   ticket 66.
 */
class KeyPageServer(
    private val token: String,
    private val onKey: (String) -> KeyCheckResult,
    private val log: (String) -> Unit = {},
    private val preferredPort: Int = KEY_PAGE_PREFERRED_PORT,
    private val acceptFrom: (java.net.InetAddress) -> Boolean = ::isLocalNetworkAddress,
    private val pageAddress: () -> String? = { null },
) {
    @Volatile
    private var socket: ServerSocket? = null
    private var acceptor: Thread? = null
    private var worker: ExecutorService? = null

    /** The port it listens on, or null when it is not running. */
    val port: Int? get() = socket?.takeUnless { it.isClosed }?.localPort

    /** Starts listening and returns the port. Starting a running server changes nothing. */
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
            Thread(runnable, "key-page-request").apply { isDaemon = true }
        }
        worker = handlers
        acceptor = Thread({ acceptLoop(server, handlers) }, "key-page-accept").apply {
            isDaemon = true
            start()
        }
        log("The Key page is listening on port ${server.localPort}.")
        return server.localPort
    }

    /**
     * Stops listening. After this returns, the port is closed and no new connection is taken.
     *
     * **It does not wait for a request already being answered**, and that is on purpose. A key
     * that works closes the key screen, and closing the screen calls this, from inside the
     * request that sent the key. Waiting here would wait for itself. That request finishes on
     * its own, so the phone still gets the page that says the key was saved.
     *
     * `shutdown` and not `shutdownNow`. A request already queued is still answered, because
     * interrupting one parked on the model's lock dropped its connection with no page at all.
     * Found by `/code-review` on build ticket 64. The model is closed by then, so such a request
     * is answered `SCREEN_CLOSED` and saves nothing.
     */
    @Synchronized
    fun stop() {
        val server = socket ?: return
        socket = null
        runCatching { server.close() }
        worker?.shutdown()
        worker = null
        acceptor = null
        log("The Key page stopped listening.")
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
                log("Refused a connection to the Key page from outside the home network.")
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
                if (length !in 0..KEY_PAGE_MAX_BYTES) {
                    return respond(output, 413, "text/plain", "Too large")
                }
                val body = readBody(input, length)
                when (val action = routeKeyPageRequest(method, target, body, token)) {
                    KeyPageAction.NotFound -> {
                        log("The Key page refused a request without the right token.")
                        respond(output, 404, "text/plain", "Not found")
                    }
                    KeyPageAction.MethodNotAllowed -> respond(output, 405, "text/plain", "Not allowed")
                    KeyPageAction.ShowPage -> respond(
                        output,
                        200,
                        "text/html; charset=utf-8",
                        keyPageHtml(result = null, postPath = "/$token", address = sharedAddress()),
                        headOnly = method == "HEAD",
                    )
                    is KeyPageAction.Submit -> {
                        val result = onKey(action.key)
                        respond(
                            output,
                            200,
                            "text/html; charset=utf-8",
                            keyPageHtml(result = result, postPath = "/$token", address = sharedAddress()),
                        )
                    }
                }
            }
        }
    }

    /**
     * The address to print on the page, or null once [stop] has run. A request still queued at
     * that moment is answered, and must not offer an address that no longer answers. Found by
     * `/code-review` on build ticket 66.
     */
    private fun sharedAddress(): String? = if (socket == null) null else pageAddress()

    /** The request line and headers, up to the blank line, or null when they never end. */
    private fun readHead(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (bytes.size() < KEY_PAGE_MAX_BYTES) {
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

    private fun respond(
        output: OutputStream,
        status: Int,
        type: String,
        body: String,
        headOnly: Boolean = false,
    ) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Payload Too Large"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        // `no-store`, so the page with a result on it is never kept by the phone's browser, and
        // `no-referrer`, so the token in this page's address is not sent to Google by itself when
        // the user follows a link to the console. The policy is [KEY_PAGE_CONTENT_SECURITY_POLICY].
        //
        // The token does leave the network when the user chooses to share the address by email
        // or WhatsApp, build ticket 66. That is accepted on purpose: the token answers only from
        // the home network, and only while the key screen is open. Spec section 7.5 says so too.
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Content-Security-Policy: $KEY_PAGE_CONTENT_SECURITY_POLICY\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) output.write(bytes)
        output.flush()
    }
}
