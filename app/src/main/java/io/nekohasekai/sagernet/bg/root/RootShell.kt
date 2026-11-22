package io.nekohasekai.sagernet.bg.root

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Helper for running privileged commands via `su`.
 */
object RootShell {

    data class CommandResult(
        val exitCode: Int,
        val output: String,
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    /**
     * Checks whether `su` access is currently granted to the app process.
     */
    fun isRootAvailable(timeoutSeconds: Long = 5): Boolean {
        return runCommand(listOf("su", "-c", "id"), timeoutSeconds).let { result ->
            result.isSuccess && result.output.contains("uid=0")
        }
    }

    /**
     * Writes [script] to a mode-0600 temporary file inside [tempDir] and executes it via `su`.
     */
    fun runScript(
        script: String,
        tempDir: File,
        prefix: String = "ownbox_root_",
        timeoutSeconds: Long = 15,
    ): CommandResult {
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        val scriptFile = File.createTempFile(prefix, ".sh", tempDir)
        try {
            scriptFile.setReadable(false, false)
            scriptFile.setReadable(true, true)
            scriptFile.setWritable(false, false)
            scriptFile.setWritable(true, true)
            scriptFile.setExecutable(false, false)
            val normalized = script.replace("\r\n", "\n")
            scriptFile.writeText(normalized, StandardCharsets.UTF_8)
            val escapedPath = scriptFile.absolutePath.replace("'", "'\\''")
            return runCommand(listOf("su", "-c", "sh '$escapedPath'"), timeoutSeconds)
        } finally {
            runCatching { scriptFile.delete() }
        }
    }

    private fun runCommand(command: List<String>, timeoutSeconds: Long): CommandResult {
        var process: Process? = null
        return try {
            process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            runCatching { process.outputStream.close() }

            val outputBuffer = ByteArrayOutputStream()
            val readerThread = thread(isDaemon = true, name = "ownbox-su-reader") {
                runCatching {
                    process.inputStream.use { input ->
                        input.copyTo(outputBuffer)
                    }
                }
            }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                readerThread.join(1000)
                val partial = outputBuffer.toString(StandardCharsets.UTF_8.name()).trim()
                val msg = if (partial.isEmpty()) {
                    "Timed out after ${timeoutSeconds}s"
                } else {
                    "Timed out after ${timeoutSeconds}s\n$partial"
                }
                return CommandResult(-1, msg)
            }

            readerThread.join(2000)
            val text = outputBuffer.toString(StandardCharsets.UTF_8.name()).trim()
            CommandResult(process.exitValue(), text)
        } catch (t: Throwable) {
            CommandResult(-1, t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { process?.destroy() }
        }
    }
}
