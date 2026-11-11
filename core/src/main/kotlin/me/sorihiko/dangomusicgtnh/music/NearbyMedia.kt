package me.sorihiko.dangomusicgtnh.music

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Download helper shared by the client's L1/L2 paths and the optional server relay.
 *
 * It deliberately does not use the account cookie jar: remote tracks are fetched either
 * with a URL resolved by the listener's own account (L1) or with the short-lived URL the
 * host shared (L2/L3).
 */
object NearbyMedia {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36 DangoMusic"

    fun referer(source: MusicSource): String = when (source) {
        MusicSource.NETEASE -> "https://music.163.com/"
        MusicSource.QQ -> "https://y.qq.com/"
        MusicSource.APPLE -> "https://music.apple.com/"
    }

    fun download(
        url: String,
        referer: String = "",
        maxBytes: Int = 64 * 1024 * 1024,
        connectMs: Int = 8000,
        readMs: Int = 20000,
        allowed: ((URL) -> Boolean)? = null,
    ): ByteArray? {
        if (url.isBlank() || url.length > 2048) return null
        var current: URL = try { URL(url) } catch (_: Exception) { return null }
        repeat(6) {
            if (current.protocol != "https" && current.protocol != "http") return null
            if (allowed != null && !allowed(current)) return null
            val conn = current.openConnection() as? HttpURLConnection ?: return null
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = connectMs
                conn.readTimeout = readMs
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Accept-Encoding", "identity")
                if (referer.isNotBlank()) conn.setRequestProperty("Referer", referer)
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return null
                    current = URL(current, location)
                    return@repeat
                }
                if (code !in 200..299) return null
                val declared = conn.contentLengthLong
                if (declared > maxBytes) return null
                return conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream(if (declared in 1..maxBytes) declared.toInt() else 16384)
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (out.size() + read > maxBytes) return null
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
            } catch (_: Exception) {
                return null
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    fun isPlayable(bytes: ByteArray?): Boolean = bytes != null && bytes.isNotEmpty() && Mp3Probe.decodable(bytes)

    fun kind(bytes: ByteArray?): String = bytes?.let(Mp3Probe::kind) ?: "unknown"

    /**
     * Hosts the optional server relay is willing to fetch. Keeping this list narrow
     * prevents a client from turning the server into an arbitrary HTTP proxy.
     */
    fun isAllowedRelayHost(url: String, allowLocal: Boolean): Boolean {
        val host = try { URI(url).host?.lowercase() } catch (_: Exception) { null } ?: return false
        if (allowLocal && (host == "127.0.0.1" || host == "localhost")) return true
        return host == "music.163.com" ||
            host.endsWith(".music.126.net") ||
            host.endsWith(".126.net") ||
            host.endsWith(".music.163.com") ||
            host.endsWith(".qqmusic.qq.com") ||
            host.endsWith(".gtimg.cn") ||
            host.endsWith(".kgstatic.com") ||
            host.endsWith(".kglink.com")
    }
}
