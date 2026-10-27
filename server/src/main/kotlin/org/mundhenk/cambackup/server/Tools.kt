package org.mundhenk.cambackup.server

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Runs external programs (exiftool, RAW converters, hook commands) and remembers which exist.
 * [searchPath]: extra directories looked at before PATH (config key tools.path; tests drop fakes there).
 */
class Tools(private val log: (String) -> Unit, private val searchPath: List<File> = emptyList()) {
    data class Result(val exitCode: Int, val output: String) {
        val ok get() = exitCode == 0
    }

    private val available = ConcurrentHashMap<String, Boolean>()
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /** Bare program names are resolved against [searchPath] first; anything with a path separator is used as is. */
    fun resolve(program: String): String {
        if (searchPath.isEmpty() || program.contains('/') || program.contains('\\')) return program
        val candidates = if (isWindows) listOf("$program.exe", "$program.cmd", "$program.bat", program) else listOf(program)
        for (dir in searchPath) for (c in candidates) {
            val f = File(dir, c)
            if (f.isFile) return f.path
        }
        return program
    }

    /** True if [program] can be started. Probed once per name. */
    fun has(program: String): Boolean = available.getOrPut(program) {
        try {
            val p = ProcessBuilder(resolve(program), versionFlag(program)).redirectErrorStream(true).start()
            p.inputStream.use { it.readBytes() }
            p.waitFor(20, TimeUnit.SECONDS)
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun versionFlag(program: String) = when {
        program.contains("exiftool") -> "-ver"
        program.contains("dcraw") -> "-v"
        else -> "--version"
    }

    fun run(vararg cmd: String, dir: File? = null, timeoutSec: Long = 600, env: Map<String, String> = emptyMap()): Result {
        val pb = ProcessBuilder(resolve(cmd[0]), *cmd.drop(1).toTypedArray()).redirectErrorStream(true)
        if (dir != null) pb.directory(dir)
        pb.environment().putAll(env)
        val p = pb.start()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { if (out.length < 64_000) out.append(it).append('\n') } }
        reader.isDaemon = true; reader.start()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return Result(-1, out.toString() + "\n(timeout after ${timeoutSec}s)")
        }
        reader.join(5000)
        return Result(p.exitValue(), out.toString().trim())
    }

    /** Run a user-supplied shell command line through the platform shell. */
    fun shell(commandLine: String, env: Map<String, String>, timeoutSec: Long = 600): Result {
        val cmd = if (isWindows) arrayOf("cmd", "/c", commandLine) else arrayOf("/bin/sh", "-c", commandLine)
        return run(*cmd, env = env, timeoutSec = timeoutSec)
    }

    fun status(): Map<String, Boolean> = available.toSortedMap()

    /** Probe the usual suspects once in the background so the status page can show them. */
    fun probeInBackground(programs: List<String>) {
        Thread({ programs.forEach { has(it) } }, "cambackup-tool-probe").apply { isDaemon = true }.start()
    }
}
