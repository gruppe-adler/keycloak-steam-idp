package de.grad.keycloak.steam

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Minimal loopback HTTP server standing in for the Steam OpenID and Web API
 * endpoints. Records every request and answers with [status] / [body].
 */
class FakeHttpServer : AutoCloseable {

    data class Recorded(val method: String, val uri: URI, val body: String) {
        val query: Map<String, String> get() = decodeForm(uri.rawQuery ?: "")
        val form: Map<String, String> get() = decodeForm(body)
    }

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    val requests = CopyOnWriteArrayList<Recorded>()

    @Volatile var status = 200
    @Volatile var body = ""

    init {
        server.createContext("/") { ex ->
            requests += Recorded(
                ex.requestMethod,
                ex.requestURI,
                ex.requestBody.readAllBytes().toString(StandardCharsets.UTF_8),
            )
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}/"

    override fun close() = server.stop(0)

    companion object {
        fun decodeForm(s: String): Map<String, String> =
            s.split('&').filter { it.isNotEmpty() }.associate {
                val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
                URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
            }
    }
}
