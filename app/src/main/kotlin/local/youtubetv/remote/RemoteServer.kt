package local.youtubetv.remote

import local.youtubetv.apikey.isLocalNetworkAddress
import local.youtubetv.data.isSqliteFile
import java.io.ByteArrayOutputStream
import java.io.File
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

/** The most an imported database may be. The household's, with two thousand videos, is about 2 MB. */
private const val IMPORT_MAX_BYTES = 256L * 1024 * 1024

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
 * @param exportDatabase a consistent copy of the database file for the phone to download, or
 *   null when none can be made. Fork 0.7.
 * @param importDatabase takes a file the phone uploaded, already checked to be SQLite, and
 *   answers null when it was accepted or a sentence saying why not. Fork 0.7.
 * @param importDir where an upload is written while it arrives.
 */
class RemoteServer(
    private val token: String,
    private val snapshot: () -> RemoteSnapshot?,
    private val videos: (sourceId: String) -> List<RemoteVideo>,
    private val onCommand: (RemoteCommand) -> Unit,
    private val exportDatabase: () -> File? = { null },
    private val importDatabase: (File) -> String? = { "This television does not take a backup." },
    private val importDir: File? = null,
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
                    ?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L
                // An import is the one request whose body is a file and not a form. It is
                // routed before its body is read, and its body goes to disk, never to a String.
                if (routeRemoteRequest(method, target, "", token) == RemoteRequest.Import) {
                    return serveImport(input, output, length)
                }
                if (length !in 0..REMOTE_MAX_BYTES.toLong()) return respond(output, 413, "text/plain", "Too large")
                val body = readBody(input, length.toInt())
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
                    RemoteRequest.Export -> {
                        val file = exportDatabase()
                        if (file == null) {
                            respond(output, 503, "text/plain", "No backup could be made.")
                        } else {
                            log("The phone downloaded a backup of ${file.length()} bytes.")
                            respondFile(output, file, "yt-viewer-backup.db")
                        }
                    }
                    // Routed above, before the body was read. Here only so the `when` is whole.
                    RemoteRequest.Import -> respond(output, 400, "text/plain", "Bad request")
                }
            }
        }
    }

    /**
     * Takes the phone's backup file. The body is written straight to a file under [importDir],
     * checked to be SQLite by its first bytes, and handed to [importDatabase], which decides.
     */
    private fun serveImport(input: InputStream, output: OutputStream, length: Long) {
        val dir = importDir
        if (dir == null) return respond(output, 503, "text/plain", "This television does not take a backup.")
        if (length !in 1..IMPORT_MAX_BYTES) return respond(output, 413, "text/plain", "The file is too large.")
        dir.mkdirs()
        val file = File(dir, "upload-${System.currentTimeMillis()}.db")
        try {
            file.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (count < 0) break
                    out.write(buffer, 0, count)
                    left -= count
                }
            }
            val head = file.inputStream().use { stream -> ByteArray(16).also { stream.read(it) } }
            if (file.length() != length || !isSqliteFile(head)) {
                file.delete()
                log("The phone sent a backup that is not an SQLite file, or arrived short. Refused.")
                return respond(output, 400, "text/plain", "That is not a YT Viewer backup file.")
            }
            val problem = importDatabase(file)
            if (problem != null) {
                file.delete()
                return respond(output, 400, "text/plain", problem)
            }
            log("The phone's backup of $length bytes was accepted. The app restarts to load it.")
            respond(output, 200, "text/plain", "Loaded. The television app closed to load the backup; open it again.")
        } catch (failure: IOException) {
            file.delete()
            respond(output, 400, "text/plain", "The upload did not finish.")
        }
    }

    private fun respondFile(output: OutputStream, file: File, name: String) {
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Content-Length: ${file.length()}\r\n")
            append("Content-Disposition: attachment; filename=\"$name\"\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        file.inputStream().use { it.copyTo(output) }
        output.flush()
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
