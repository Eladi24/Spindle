package io.github.eladimany.spindle.data.server

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import timber.log.Timber
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class ServerAddress(val host: String, val port: Int) {
    fun urlFor(token: String): String = "http://$host:$port/t/$token"
}

/**
 * Serves local audio files to the Node over HTTP, resolving opaque tokens
 * from [TokenRegistry] — never a real file path (hard constraint #6). Must
 * support bounded HTTP Range requests and clamp the end offset to the file
 * size: the Node computes a byte range from bitrate and overshoots past EOF
 * when seeking near the end of a track, and an unclamped 416 there kills
 * playback (hard constraint #4). Runs only while a Node output session is
 * active — [start]/[stop] are explicit, nothing starts this automatically.
 */
@Singleton
class MediaHttpServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenRegistry: TokenRegistry,
) {
    private var server: EmbeddedServer<*, *>? = null

    val isRunning: Boolean get() = server != null

    suspend fun start(host: String): ServerAddress {
        stop()
        val embedded = embeddedServer(CIO, host = host, port = 0) { module() }
        embedded.start(wait = false)
        val port = embedded.engine.resolvedConnectors().first().port
        server = embedded
        return ServerAddress(host, port)
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
        server = null
    }

    private fun Application.module() {
        routing {
            get("/t/{token}") {
                val token = call.parameters["token"]
                val served = token?.let(tokenRegistry::resolve)
                if (served == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.response.header("icy-name", served.icyName)
                    serveFile(Uri.parse(served.uri), call)
                }
            }
        }
    }

    private suspend fun serveFile(uri: Uri, call: ApplicationCall) {
        val descriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            Timber.w(e, "Failed to open %s for HTTP serving", uri)
            null
        }
        if (descriptor == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        descriptor.use { pfd ->
            val fileSize = pfd.statSize
            val contentType = context.contentResolver.getType(uri)
                ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                ?: ContentType.Audio.Any
            val range = call.request.headers[HttpHeaders.Range]?.let { HttpRange.parse(it, fileSize) }

            call.response.header(HttpHeaders.AcceptRanges, "bytes")

            val start = range?.first ?: 0L
            val end = range?.last ?: (fileSize - 1)
            val length = end - start + 1
            if (range != null) {
                call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$fileSize")
            }
            val status = if (range != null) HttpStatusCode.PartialContent else HttpStatusCode.OK

            call.respondBytesWriter(contentType = contentType, status = status, contentLength = length) {
                FileInputStream(pfd.fileDescriptor).use { input ->
                    if (start > 0) input.channel.position(start)
                    copyRange(input, this, length)
                }
            }
        }
    }

    /** Copies exactly [limit] bytes — a plain `copyTo` doesn't stop at a byte-range boundary. */
    private suspend fun copyRange(input: FileInputStream, output: ByteWriteChannel, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) break
            output.writeFully(buffer, 0, read)
            remaining -= read
        }
    }
}
