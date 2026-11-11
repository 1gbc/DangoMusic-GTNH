package me.sorihiko.dangomusicgtnh.music

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.zip.CRC32

/** Persistent copies of complete NetEase audio, isolated by login session. */
object NeteaseDiskCache {
    private const val MAGIC = 0x444D4301
    private const val LIMIT = 2L * 1024 * 1024 * 1024
    val directory: Path by lazy {
        val home = Paths.get(System.getProperty("user.home"))
        if (System.getProperty("os.name").startsWith("Windows")) {
            val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            (local ?: home.resolve("AppData/Local")).resolve("NetEase/CloudMusic/DangoMusicCache")
        } else {
            val cache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            (cache ?: home.resolve(".cache")).resolve("dangomusicgtnh/netease")
        }
    }

    @Volatile internal var generation = 0L
        private set

    @Synchronized
    fun clear() {
        generation++
        if (!Files.isDirectory(directory)) return
        Files.newDirectoryStream(directory, "*.audio").use { files ->
            for (file in files) {
                if (file.fileName.toString().matches(Regex("[0-9a-f]{64}[.]audio"))) Files.deleteIfExists(file)
            }
        }
    }

    internal fun key(id: Long, quality: NeteaseQuality, session: String): String {
        val bytes = "$id:${quality.id}:$session".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Synchronized
    internal fun read(key: String): Pair<NeteaseApi.SongUrl, ByteArray>? {
        val file = directory.resolve("$key.audio")
        if (!Files.isRegularFile(file)) return null
        return try {
            val result = DataInputStream(Files.newInputStream(file).buffered()).use { input ->
                if (input.readInt() != MAGIC) throw IOException("Invalid cache version")
                val tier = input.readUTF()
                val stream = NeteaseStream(NeteaseQuality.values().firstOrNull { it.id == tier }, input.readLong(), input.readUTF())
                val gain = input.readFloat()
                val peak = input.readFloat()
                val size = input.readInt()
                if (size !in 10_000..NeteaseApi.MAX_AUDIO_BYTES || size > Files.size(file)) throw IOException("Invalid cache length")
                val checksum = input.readLong()
                val bytes = ByteArray(size)
                input.readFully(bytes)
                if (input.read() != -1 || checksum != checksum(bytes)) throw IOException("Corrupt audio cache")
                NeteaseApi.SongUrl("", false, gain, peak, neteaseStream = stream) to bytes
            }
            runCatching { Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis())) }
            result
        } catch (_: IOException) {
            runCatching { Files.deleteIfExists(file) }
            null
        }
    }

    @Synchronized
    internal fun write(key: String, url: NeteaseApi.SongUrl, bytes: ByteArray, downloadGeneration: Long) {
        if (downloadGeneration != generation) return
        val stream = url.neteaseStream ?: return
        if (url.trialOnly || url.replaced || bytes.size !in 10_000..NeteaseApi.MAX_AUDIO_BYTES ||
            Mp3Probe.kind(bytes) !in setOf("flac", "ogg-vorbis", "id3", "mpeg-like")) return
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "audio-", ".tmp")
        try {
            DataOutputStream(Files.newOutputStream(temp).buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeUTF(stream.quality?.id.orEmpty())
                out.writeLong(stream.bitrate)
                out.writeUTF(stream.format)
                out.writeFloat(url.gainDb)
                out.writeFloat(url.peak)
                out.writeInt(bytes.size)
                out.writeLong(checksum(bytes))
                out.write(bytes)
            }
            val target = directory.resolve("$key.audio")
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            prune()
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun checksum(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    private fun prune() {
        val entries = Files.newDirectoryStream(directory, "*.audio").use { files ->
            files.mapNotNull { file ->
                try {
                    Triple(file, Files.size(file), Files.getLastModifiedTime(file))
                } catch (_: IOException) {
                    null
                }
            }
        }.sortedBy { it.third }
        var total = entries.sumOf { it.second }
        for ((file, size) in entries) {
            if (total <= LIMIT) break
            if (Files.deleteIfExists(file)) total -= size
        }
    }
}
