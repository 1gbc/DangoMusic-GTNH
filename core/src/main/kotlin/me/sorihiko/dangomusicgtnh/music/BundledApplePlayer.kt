package me.sorihiko.dangomusicgtnh.music

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object BundledApplePlayer {
    private const val RESOURCE = "/assets/dangomusicgtnh/apple-player/windows-x64.zip"

    fun install(root: Path): Path {
        val archive = javaClass.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
            ?: throw IOException("Missing bundled Apple Music component")
        // Different mod builds can share a config directory without replacing a running executable.
        val digest = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
        val directory = root.toAbsolutePath().normalize().resolve("bundled-$digest")
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = directory.resolve(entry.name).normalize()
                if (target == directory || !target.startsWith(directory)) throw IOException("Invalid Apple component path")
                if (!entry.isDirectory) installFile(target, zip.readBytes())
            }
        }
        val executable = directory.resolve("DangoApplePlayer.exe")
        if (!Files.isRegularFile(executable)) throw IOException("Missing Apple Music executable")
        return executable
    }

    private fun installFile(target: Path, bytes: ByteArray) {
        fun matches() = Files.isRegularFile(target) && Files.size(target) == bytes.size.toLong() &&
            Files.readAllBytes(target).contentEquals(bytes)
        if (matches()) return
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, "apple-player-", ".tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            if (!matches()) throw e
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
