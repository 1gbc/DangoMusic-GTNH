package me.sorihiko.dangomusicgtnh.music

import me.sorihiko.dangomusicgtnh.DangoMusic
import me.sorihiko.dangomusicgtnh.lyrics.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import kotlin.math.abs

/** Uses the shared timed-lyrics model for both the player page and world rendering. */
object AppleLyrics {
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val artistSeparators = Regex("\\s+(?:&|and|x|feat\\.?|ft\\.?|/)\\s+|[,，、;；]", RegexOption.IGNORE_CASE)
    fun fileStem(track: NeteaseTrack): String {
        val id = track.nativeId.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,160}")) && !it.startsWith(".") }
            ?: MessageDigest.getInstance("SHA-256").digest(track.key.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "apple-$id"
    }

    fun load(track: NeteaseTrack): Lyrics? {
        val directory = DangoMusic.instance.clientDir.resolve("lyrics/apple")
        Files.createDirectories(directory)
        val stem = fileStem(track)
        for (extension in listOf("ttml", "yrc", "lrc")) {
            val file = directory.resolve("$stem.$extension")
            if (!Files.isRegularFile(file) || Files.size(file) > 2 * 1024 * 1024) continue
            val text = String(Files.readAllBytes(file), Charsets.UTF_8).removePrefix("\uFEFF")
            val lyrics = parse(text, extension) ?: continue
            val offsetFile = directory.resolve("$stem.offset")
            val offset = if (Files.isRegularFile(offsetFile) && Files.size(offsetFile) <= 32)
                String(Files.readAllBytes(offsetFile), Charsets.UTF_8).trim().toLongOrNull()?.coerceIn(-600000,600000) ?: 0L else 0L
            return shift(lyrics, offset)
        }
        // The lyrics UI can request a song before MusicKit supplies its duration.
        // Do not cache that as a failed match.
        if (track.durationMs <= 0 || track.name.isBlank() || track.artists.all { it.isBlank() }) return null
        val now = System.nanoTime()
        if (now - (failedAt[track.key] ?: (now - 600_000_000_000L)) < 600_000_000_000L) return null
        val credits = track.artists.flatMap { it.split(artistSeparators) }.joinToString(" ")
        val matches = ArrayList<NeteaseTrack>()
        val checked = HashSet<Long>()
        for (query in listOf("${track.name} $credits", track.name)) {
            try {
                matches += NeteaseApi.search(query, 30)
                val match = selectMatch(track, matches) ?: continue
                if (!checked.add(match.id)) continue
                val result = LyricsProvider.neteaseLyricById(match.id) ?: continue
                return Lyrics(result.lines, "NetEase · matched for Apple Music")
            } catch (_: Exception) { /* Try the next lookup without interrupting playback. */ }
        }
        if (failedAt.size > 512) failedAt.clear()
        failedAt[track.key] = now
        return null
    }

    private fun normalized(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun selectMatch(track: NeteaseTrack, candidates: List<NeteaseTrack>): NeteaseTrack? {
        if (track.durationMs <= 0 || normalized(track.name).isEmpty() || track.artists.isEmpty()) return null
        val artist = normalized(track.artists.joinToString(""))
        if (artist.isEmpty()) return null
        val credits = track.artists.flatMap { it.split(artistSeparators) }.map(::normalized).filter { it.isNotEmpty() }.sorted()
        val title = normalized(track.name)
        val valid = candidates.filter {
            it.durationMs > 0 && abs(it.durationMs - track.durationMs) <= 2000 && normalized(it.name) == title &&
                (normalized(it.artists.joinToString("")) == artist ||
                    (it.artists.size > 1 && it.artists.map(::normalized).sorted() == credits))
        }
        val sameAlbum = valid.filter { track.albumName.isNotBlank() && normalized(it.albumName) == normalized(track.albumName) }
        val ranked = sameAlbum.ifEmpty { valid }.distinctBy { it.id }.sortedBy { abs(it.durationMs - track.durationMs) }
        if (ranked.size > 1 && abs(ranked[0].durationMs-track.durationMs) == abs(ranked[1].durationMs-track.durationMs)) return null
        return ranked.firstOrNull()
    }

    fun parse(text: String, extension: String): Lyrics? {
        val lines = when (extension.lowercase(Locale.ROOT)) {
            "lrc" -> LyricsProvider.parseLrc(text)
            "yrc" -> LyricsProvider.parseYrc(text)
            "ttml" -> parseTtml(text)
            else -> emptyList()
        }
        return lines.takeIf { it.isNotEmpty() }?.let { Lyrics(it, "Local Apple Music lyrics") }
    }

    fun shift(lyrics: Lyrics, offsetMs: Long): Lyrics = Lyrics(lyrics.lines.map { line ->
        LyricLine((line.timeMs+offsetMs).coerceAtLeast(0), line.text,
            line.words?.map { LyricWord((it.startMs+offsetMs).coerceAtLeast(0), it.durMs, it.text) },
            line.translation, line.romanization)
    }, lyrics.sourceName)

    /** Absolute seconds/milliseconds or clock timestamps; frame-based TTML is rejected. */
    private fun time(value: String): Long? {
        val seconds = when {
            value.endsWith("ms") -> value.dropLast(2).toDoubleOrNull()?.div(1000)
            value.endsWith("s") -> value.dropLast(1).toDoubleOrNull()
            value.matches(Regex("\\d+:\\d{2}:\\d{2}(\\.\\d+)?")) -> {
                val parts = value.split(':')
                parts[0].toDouble()*3600 + parts[1].toDouble()*60 + parts[2].toDouble()
            }
            else -> null
        } ?: return null
        return if (seconds.isFinite() && seconds >= 0 && seconds <= 86400) (seconds*1000).toLong() else null
    }

    private fun parseTtml(text: String): List<LyricLine> = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
            override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
            override fun error(e: org.xml.sax.SAXParseException) { throw e }
        })
        val doc = builder.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        val paragraphs = doc.getElementsByTagNameNS("*", "p")
        (0 until paragraphs.length).mapNotNull { i ->
            val p = paragraphs.item(i) as Element
            val begin = time(p.getAttribute("begin")) ?: return@mapNotNull null
            val words = ArrayList<LyricWord>()
            var complete = true
            fun collect(node: Node) {
                if (node is Element) {
                    val role = node.getAttributeNS("http://www.w3.org/ns/ttml#metadata", "role")
                    if (role.contains("translation") || role.contains("romanization")) return
                    val start = time(node.getAttribute("begin"))
                    val end = time(node.getAttribute("end")) ?: time(node.getAttribute("dur"))?.let { start?.plus(it) }
                    if (node !== p && start != null && end != null && start >= begin && end > start) {
                        words += LyricWord(start, end-start, node.textContent)
                        return
                    }
                }
                if (node.nodeType == Node.TEXT_NODE && !node.textContent.isNullOrBlank()) complete = false
                for (j in 0 until node.childNodes.length) collect(node.childNodes.item(j))
            }
            collect(p)
            val body = if (complete && words.isNotEmpty()) words.joinToString("") { it.text } else p.textContent.trim()
            if (body.isBlank()) null else LyricLine(begin,body,words.takeIf { complete && it.isNotEmpty() })
        }.sortedBy { it.timeMs }
    } catch (_: Exception) { emptyList() }
}
