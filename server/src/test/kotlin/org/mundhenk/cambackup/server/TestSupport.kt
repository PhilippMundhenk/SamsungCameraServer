package org.mundhenk.cambackup.server

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties

/** Shared helpers: fake command line tools (Windows .cmd / POSIX sh), HTTP calls, configs. */
object TestSupport {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")

    fun tempDir(prefix: String): File = File.createTempFile(prefix, "").apply { delete(); mkdirs() }

    /** Writes a fake program into [bin]; [windows] is cmd syntax, [unix] is sh syntax. */
    fun fake(bin: File, name: String, windows: String, unix: String): File {
        bin.mkdirs()
        val f = if (isWindows) File(bin, "$name.cmd") else File(bin, name)
        f.writeText(if (isWindows) "@echo off\r\n$windows\r\n" else "#!/bin/sh\n$unix\n")
        f.setExecutable(true)
        return f
    }

    /** exiftool that reports a fixed DateTimeOriginal + Model and succeeds on every write. */
    fun fakeExiftool(bin: File, name: String = "exiftool", date: String = "2024-05-06 07:08:09", model: String = "NX1000") =
        fake(bin, name, "echo $date\r\necho $model", "echo \"$date\"; echo $model")

    fun fakeFailing(bin: File, name: String) = fake(bin, name, "exit /b 1", "exit 1")

    /** dcraw_emu that "develops" by copying fake.ppm next to the input (<input>.ppm). */
    fun fakeDcraw(bin: File, ppm: ByteArray): File {
        bin.mkdirs(); File(bin, "fake.ppm").writeBytes(ppm)
        return fake(bin, "dcraw_emu",
            "if \"%1\"==\"-v\" exit /b 0\r\ncopy /y \"%~dp0fake.ppm\" \"%2.ppm\" >nul",
            "[ \"\$1\" = \"-v\" ] && exit 0\ncp \"\$(dirname \"\$0\")/fake.ppm\" \"\$2.ppm\"")
    }

    /** darktable-cli that copies fake.jpg to the requested output. */
    fun fakeDarktable(bin: File, jpg: ByteArray): File {
        bin.mkdirs(); File(bin, "fake.jpg").writeBytes(jpg)
        return fake(bin, "darktable-cli",
            "if \"%1\"==\"--version\" exit /b 0\r\ncopy /y \"%~dp0fake.jpg\" \"%2\" >nul",
            "[ \"\$1\" = \"--version\" ] && exit 0\ncp \"\$(dirname \"\$0\")/fake.jpg\" \"\$2\"")
    }

    /** Hook script that dumps the CAMBACKUP_* environment into [out]. */
    fun fakeHook(bin: File, name: String, out: File): File = fake(bin, name,
        "echo %CAMBACKUP_EVENT% %CAMBACKUP_CAMERA% %CAMBACKUP_SYSOFF% %CAMBACKUP_STORED% %CAMBACKUP_SKIPPED% > \"${out.path}\"",
        "echo \"\$CAMBACKUP_EVENT \$CAMBACKUP_CAMERA \$CAMBACKUP_SYSOFF \$CAMBACKUP_STORED \$CAMBACKUP_SKIPPED\" > \"${out.path}\"\necho \"\$CAMBACKUP_FILES\" >> \"${out.path}\"")

    fun ppm(w: Int, h: Int): ByteArray = "P6\n# fake\n$w $h\n255\n".toByteArray() + ByteArray(w * h * 3) { (it * 3).toByte() }

    fun config(dataDir: File, vararg pairs: Pair<String, String>, env: Map<String, String> = emptyMap()): Config {
        val p = Properties(); pairs.forEach { (k, v) -> p.setProperty(k, v) }
        dataDir.mkdirs()
        return Config(dataDir, p, env)
    }

    fun http(method: String, url: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap(), chunked: Boolean = false, followRedirects: Boolean = true): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.instanceFollowRedirects = followRedirects
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            if (chunked) c.setChunkedStreamingMode(64 * 1024) else c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        c.disconnect()
        return code to text
    }

    fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) { if (System.currentTimeMillis() > deadline) throw AssertionError("timeout"); Thread.sleep(50) }
    }
}
