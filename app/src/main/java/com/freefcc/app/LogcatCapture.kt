package com.freefcc.app

import java.io.IOException

internal data class LogcatCaptureResult(
    val lineCount: Int,
    val truncated: Boolean,
    val error: String?
)

/**
 * Reads a narrow, bounded logcat stream without opening any DJI transport socket.
 */
internal object LogcatCapture {
    const val DEFAULT_DURATION_MS = 60_000
    const val MIN_DURATION_MS = 1_000
    const val MAX_DURATION_MS = 120_000

    private const val MAX_LINES = 160
    private const val MAX_LINE_CHARS = 4_096

    internal fun durationMs(params: Map<String, String>): Int {
        val raw = params["duration_ms"] ?: return DEFAULT_DURATION_MS
        val duration = raw.trim().toIntOrNull()
            ?: throw IllegalArgumentException("invalid_duration_ms")
        require(duration in MIN_DURATION_MS..MAX_DURATION_MS) { "invalid_duration_ms" }
        return duration
    }

    internal fun command(): List<String> = listOf(
        "/system/bin/logcat",
        "-v",
        "threadtime",
        "-T",
        "1",
        "DUSS73:I",
        "OpenFCC:V",
        "OpenFCC-LinkState:V",
        "OpenFCC.Main:V",
        "OpenFCC.FourG:V",
        "OpenFCC.Fcc:V",
        "OpenFCC.RcTcp:V",
        "OpenFCC.CommandCrypto:V",
        "OpenFCC.SessionCrypto:V",
        "OpenFCC.SeqProtect:V",
        "OpenFCC.DroneSerial:V",
        "OpenFCC.EncPrefs:V",
        "OpenFCC.NativeRelay:V",
        "AndroidRuntime:E",
        "*:S"
    )

    fun capture(durationMs: Int, onLine: (String) -> Unit): LogcatCaptureResult {
        require(durationMs in MIN_DURATION_MS..MAX_DURATION_MS) { "invalid_duration_ms" }
        val process = try {
            ProcessBuilder(command()).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return LogcatCaptureResult(0, false, e.message ?: "logcat_start_failed")
        }

        val watchdog = Thread(
            {
                try {
                    Thread.sleep(durationMs.toLong())
                    process.destroy()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            },
            "SkylabFCC-Logcat-Watchdog"
        ).apply {
            isDaemon = true
            start()
        }

        var lineCount = 0
        var truncated = false
        var error: String? = null
        try {
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    onLine(line.take(MAX_LINE_CHARS))
                    lineCount++
                    if (lineCount >= MAX_LINES) {
                        truncated = true
                        break
                    }
                }
            }
        } catch (e: IOException) {
            error = e.message ?: "logcat_read_failed"
        } finally {
            watchdog.interrupt()
            process.destroy()
        }
        return LogcatCaptureResult(lineCount, truncated, error)
    }
}
