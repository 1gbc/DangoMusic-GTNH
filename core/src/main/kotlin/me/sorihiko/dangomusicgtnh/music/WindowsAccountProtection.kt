package me.sorihiko.dangomusicgtnh.music

import java.io.IOException
import java.nio.file.Paths
import java.util.Base64
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Use Windows DPAPI without requiring the launcher to provide a native Java library. */
internal object WindowsAccountProtection {
    fun transform(bytes: ByteArray, encrypt: Boolean): ByteArray {
        val systemRoot = System.getenv("SystemRoot") ?: throw IOException("Windows system directory is unavailable")
        val executable = Paths.get(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
        val method = if (encrypt) "Protect" else "Unprotect"
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}ProgressPreference = 'SilentlyContinue'
            Add-Type -AssemblyName System.Security
            ${'$'}bytes = [Convert]::FromBase64String([Console]::ReadLine())
            ${'$'}result = [Security.Cryptography.ProtectedData]::$method(${'$'}bytes, ${'$'}null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
            [Console]::WriteLine([Convert]::ToBase64String(${'$'}result))
        """.trimIndent()
        val command = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder(executable.toString(), "-NoProfile", "-NonInteractive", "-EncodedCommand", command)
            .redirectErrorStream(true).start()
        val exchange = FutureTask {
            process.outputStream.bufferedWriter(Charsets.US_ASCII).use {
                it.write(Base64.getEncoder().encodeToString(bytes))
                it.newLine()
            }
            process.inputStream.bufferedReader(Charsets.US_ASCII).use { it.readText().trim() }
        }
        Thread(exchange, "Dango-WindowsAccountProtection").apply { isDaemon = true }.start()
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) throw IOException("Windows account protection timed out")
            if (process.exitValue() != 0) throw IOException("Windows account protection failed")
            return Base64.getDecoder().decode(exchange.get(1, TimeUnit.SECONDS))
        } finally {
            if (process.isAlive) process.destroyForcibly()
            exchange.cancel(true)
        }
    }
}
