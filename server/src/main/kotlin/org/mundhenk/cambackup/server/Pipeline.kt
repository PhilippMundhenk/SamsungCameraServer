package org.mundhenk.cambackup.server

import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** A file that landed in the storage directory and now moves through the pipeline. */
class ProcessedFile(
    val date: String,          // camera's dc:date
    val originalName: String,  // SAM_0001.SRW
    val size: Long,
    val camera: String,
    @Volatile var file: File,  // current location (changes with rename/export)
    val user: String = "",     // multi-user server: the user whose identity the camera used ("" = the general one)
) {
    @Volatile var preview: File? = null   // JPEG rendered from a RAW, if any
    @Volatile var error: String? = null
}

class Session(val camera: String, val startedAt: Long = System.currentTimeMillis(), val user: String = "") {
    val files = ArrayList<ProcessedFile>()
    @Volatile var stored = 0
    @Volatile var skipped = 0
}

/**
 * Post-processing after a file was stored, and hooks after a session finished.
 * Runs on a single worker thread so uploads are never slowed down by RAW conversion.
 *
 * Per file, in order: EXIF time shift -> RAW preview -> rename -> export (move).
 * Per session: webhook -> hook command -> sysoff command.
 * Every stage is optional and skipped (with a log line) when its tool is missing.
 */
class Pipeline(
    private val baseConfig: Config,
    private val ledger: Ledger,
    private val tools: Tools,
    private val log: (String) -> Unit,
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "cambackup-pipeline").apply { isDaemon = true } }

    private val scoped = ThreadLocal<Config>()
    /** The settings as the camera of the file (or session) being processed sees them; the general ones otherwise. */
    private val config: Config get() = scoped.get() ?: baseConfig

    fun submitFile(f: ProcessedFile) {
        worker.execute {
            try { processFile(f) } catch (e: Exception) {
                f.error = e.toString(); log("Pipeline failed for ${f.originalName}: $e")
            }
        }
    }

    fun submitSessionDone(session: Session, sysOff: Boolean) {
        worker.execute {
            try { finishSession(session, sysOff) } catch (e: Exception) { log("Session hooks failed: $e") }
        }
    }

    /** Wait until everything submitted so far has been processed (tests, shutdown). */
    fun awaitIdle(timeoutMs: Long = 60_000) {
        val f = worker.submit { }
        f.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun shutdown() {
        worker.shutdown()
        worker.awaitTermination(30, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------ per file

    private fun processFile(f: ProcessedFile) {
        scoped.set(baseConfig.forCameraAndUser(f.camera, f.user))
        try {
            timeShift(f)
            rawPreview(f)
            val meta = metadata(f)
            rename(f, meta)
            export(f, meta)
        } finally {
            // whatever happened, the ledger must point at where the file is now
            if (f.file.path != ledger.find(f.date, f.originalName)?.path) ledger.updatePath(f.date, f.originalName, f.file)
            scoped.remove()
        }
    }

    private fun timeShift(f: ProcessedFile) {
        val shift = config.exifTimeShift ?: return
        val tool = config.exifTool
        if (!tools.has(tool)) { log("exif.timeshift set but '$tool' not found; skipped"); return }
        if (!isImage(f.file)) return
        val op = if (shift.startsWith("-")) "-=" else "+="
        val value = shift.trimStart('+', '-').trim()
        val r = tools.run(tool, "-overwrite_original", "-P", "-q", "-AllDates$op$value", f.file.path)
        if (r.ok) log("Shifted EXIF dates of ${f.file.name} by $shift")
        else log("exiftool time shift failed for ${f.file.name}: ${r.output}")
    }

    private fun rawPreview(f: ProcessedFile) {
        if (!config.rawConvert) return
        if (!f.file.name.uppercase().endsWith(".SRW")) return
        if (config.rawOnlyIfNoJpeg) {
            val sibling = f.file.nameWithoutExtension
            val jpegExists = f.file.parentFile?.listFiles()?.any {
                it.nameWithoutExtension.equals(sibling, true) && it.extension.equals("jpg", true)
            } == true
            if (jpegExists) { log("RAW ${f.file.name}: JPEG from camera present, no preview rendered"); return }
        }
        val out = File(f.file.parentFile, f.file.name + ".jpg")
        val converter = when (val c = config.rawConverter.lowercase()) {
            "auto" -> listOf("darktable", "rawtherapee", "dcraw").firstOrNull { converterAvailable(it) }
            "none" -> null
            else -> c.takeIf { converterAvailable(it) }
        }
        if (converter == null) { log("raw.convert on but no converter available (darktable-cli / rawtherapee-cli / dcraw_emu / dcraw)"); return }
        val ok = try { convertRaw(converter, f.file, out) } catch (e: Exception) { log("RAW conversion failed: $e"); false }
        if (ok && out.isFile && out.length() > 0) { f.preview = out; log("Rendered ${out.name} with $converter") }
        else { out.delete(); log("RAW conversion of ${f.file.name} with $converter produced no output") }
    }

    private fun converterAvailable(name: String): Boolean = when (name) {
        "darktable" -> tools.has("darktable-cli")
        "rawtherapee" -> tools.has("rawtherapee-cli")
        "dcraw" -> tools.has("dcraw_emu") || tools.has("dcraw")
        else -> false
    }

    private fun convertRaw(converter: String, input: File, out: File): Boolean = when (converter) {
        "darktable" -> {
            val cfg = File(config.dataDir, "darktable").apply { mkdirs() }
            val r = tools.run("darktable-cli", input.path, out.path, "--core", "--configdir", cfg.path,
                "--conf", "plugins/imageio/format/jpeg/quality=${config.rawJpegQuality}")
            if (!r.ok) log("darktable-cli: ${r.output.lines().lastOrNull()}")
            r.ok
        }
        "rawtherapee" -> {
            val r = tools.run("rawtherapee-cli", "-o", out.path, "-j${config.rawJpegQuality}", "-Y", "-c", input.path)
            if (!r.ok) log("rawtherapee-cli: ${r.output.lines().lastOrNull()}")
            r.ok
        }
        "dcraw" -> {
            // LibRaw's dcraw_emu writes <file>.ppm next to the input, classic dcraw writes <base>.ppm
            val bin = if (tools.has("dcraw_emu")) "dcraw_emu" else "dcraw"
            val r = tools.run(bin, "-w", input.path)
            val ppm = listOf(File(input.path + ".ppm"), File(input.parentFile, input.nameWithoutExtension + ".ppm")).firstOrNull { it.isFile }
            if (!r.ok || ppm == null) { log("$bin: ${r.output.lines().lastOrNull()}"); false }
            else {
                try { writeJpeg(readPpm(ppm), out, config.rawJpegQuality / 100f); true } finally { ppm.delete() }
            }
        }
        else -> false
    }

    data class Meta(val yyyy: String, val MM: String, val dd: String, val HH: String, val mm: String, val ss: String, val camera: String) {
        fun expand(pattern: String, base: String, ext: String, name: String): String = pattern
            .replace("{yyyy}", yyyy).replace("{MM}", MM).replace("{dd}", dd)
            .replace("{HH}", HH).replace("{mm}", mm).replace("{ss}", ss)
            .replace("{camera}", camera).replace("{base}", base).replace("{ext}", ext).replace("{name}", name)
    }

    /** DateTimeOriginal + Model from EXIF when exiftool is around, else the camera's date and midnight. */
    private fun metadata(f: ProcessedFile): Meta {
        var date = f.date; var time = "00:00:00"; var model = ""
        val needsExif = config.renamePattern != null || config.exportDir != null
        if (needsExif && isImage(f.file) && tools.has(config.exifTool)) {
            val r = tools.run(config.exifTool, "-s3", "-d", "%Y-%m-%d %H:%M:%S", "-DateTimeOriginal", "-Model", f.file.path)
            val lines = r.output.lines().map { it.trim() }
            val dt = lines.firstOrNull { Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}").matches(it) }
            if (dt != null) { date = dt.substring(0, 10); time = dt.substring(11) }
            lines.firstOrNull { it.isNotEmpty() && it != dt }?.let { model = it }
        }
        val d = date.split('-'); val t = time.split(':')
        return Meta(
            d.getOrNull(0) ?: "0000", d.getOrNull(1) ?: "00", d.getOrNull(2) ?: "00",
            t.getOrNull(0) ?: "00", t.getOrNull(1) ?: "00", t.getOrNull(2) ?: "00",
            model.replace(Regex("[^A-Za-z0-9_-]"), ""),
        )
    }

    private fun rename(f: ProcessedFile, meta: Meta) {
        val pattern = config.renamePattern ?: return
        val base = f.file.nameWithoutExtension; val ext = f.file.extension
        val newName = safeName(meta.expand(pattern, base, ext, f.file.name))
        if (newName.isBlank() || newName == f.file.name) return
        val target = unique(File(f.file.parentFile, newName))
        moveFile(f, target)
        log("Renamed ${f.originalName} -> ${target.name}")
    }

    private fun export(f: ProcessedFile, meta: Meta) {
        val base = config.exportDir ?: return
        // a user's files go to <export.dir>/<user> unless the user has an export dir of their own
        val dir = if (f.user.isNotEmpty() && !config.isSet("${Config.USER_PREFIX}${f.user}.export.dir")) File(base, f.user) else base
        val sub = meta.expand(config.exportSubfolder, f.file.nameWithoutExtension, f.file.extension, f.file.name)
            .split('/', '\\').filter { it.isNotBlank() }.joinToString(File.separator) { safeName(it) }
        val targetDir = if (sub.isEmpty()) dir else File(dir, sub)
        targetDir.mkdirs()
        val target = unique(File(targetDir, f.file.name))
        moveFile(f, target)
        log("Exported ${target.name} -> ${targetDir.path}")
    }

    private fun moveFile(f: ProcessedFile, target: File) {
        move(f.file, target)
        f.file = target
        f.preview?.let { p ->
            val pt = unique(File(target.parentFile, target.name + ".jpg"))
            move(p, pt)
            f.preview = pt
        }
    }

    /**
     * Rename within a filesystem, copy + delete across (e.g. /data -> /photos volumes in Docker).
     * Never overwrites: callers pass a [unique] target.
     */
    private fun move(src: File, dst: File) {
        try {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return
        } catch (_: IOException) {
            // not the same filesystem (AtomicMoveNotSupportedException) or atomic move refused: fall through
        }
        val tmp = File(dst.parentFile, dst.name + ".part")
        Files.copy(src.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
        if (tmp.length() != src.length()) { tmp.delete(); throw IOException("copy of ${src.name} to ${dst.parentFile} is incomplete") }
        try { Files.setLastModifiedTime(tmp.toPath(), Files.getLastModifiedTime(src.toPath())) } catch (_: IOException) {}
        Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE)
        if (!src.delete()) log("Could not delete ${src.path} after copying it to ${dst.path}")
    }

    private fun unique(target: File): File {
        if (!target.exists()) return target
        val base = target.nameWithoutExtension; val ext = target.extension.let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (true) {
            val c = File(target.parentFile, "${base}_$i$ext")
            if (!c.exists()) return c
            i++
        }
    }

    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim()
    private fun isImage(f: File) = f.extension.lowercase() in setOf("jpg", "jpeg", "srw", "dng", "tif", "tiff", "mp4", "mov")

    // ------------------------------------------------------------------ per session

    private fun finishSession(s: Session, sysOff: Boolean) {
        scoped.set(baseConfig.forCameraAndUser(s.camera, s.user))
        try { finishSessionScoped(s, sysOff) } finally { scoped.remove() }
    }

    private fun finishSessionScoped(s: Session, sysOff: Boolean) {
        val files = synchronized(s.files) { s.files.toList() }
        val paths = files.flatMap { listOfNotNull(it.file, it.preview) }
        config.webhookUrl?.let { url ->
            val json = buildString {
                append("{\"event\":\"done\",\"camera\":").append(Json.str(s.camera)).append(",\"user\":").append(Json.str(s.user))
                append(",\"sysOff\":").append(sysOff)
                append(",\"stored\":").append(s.stored).append(",\"skipped\":").append(s.skipped)
                append(",\"durationSec\":").append((System.currentTimeMillis() - s.startedAt) / 1000)
                append(",\"files\":[")
                files.forEachIndexed { i, f ->
                    if (i > 0) append(',')
                    append("{\"name\":").append(Json.str(f.originalName)).append(",\"path\":").append(Json.str(f.file.path))
                    append(",\"size\":").append(f.size)
                    f.preview?.let { append(",\"preview\":").append(Json.str(it.path)) }
                    f.error?.let { append(",\"error\":").append(Json.str(it)) }
                    append('}')
                }
                append("]}")
            }
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 30_000
                c.setRequestProperty("Content-Type", "application/json")
                config.webhookToken?.let { c.setRequestProperty("Authorization", "Bearer $it") }
                c.outputStream.use { it.write(json.toByteArray()) }
                val code = c.responseCode
                (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() }
                log("Webhook $url -> HTTP $code")
            } catch (e: IOException) { log("Webhook failed: $e") }
        }
        val env = mapOf(
            "CAMBACKUP_EVENT" to "done", "CAMBACKUP_CAMERA" to s.camera, "CAMBACKUP_USER" to s.user, "CAMBACKUP_SYSOFF" to sysOff.toString(),
            "CAMBACKUP_STORED" to s.stored.toString(), "CAMBACKUP_SKIPPED" to s.skipped.toString(),
            "CAMBACKUP_FILES" to paths.joinToString("\n") { it.path },
        )
        config.hookCommand?.let { cmd ->
            val r = tools.shell(cmd, env)
            log("hook.command exit ${r.exitCode}" + if (r.output.isNotBlank()) ": ${r.output.lines().last()}" else "")
        }
        if (sysOff) config.sysOffCommand?.let { cmd ->
            log("Camera requested shutdown (SysOff) -> running sysoff.command")
            val r = tools.shell(cmd, env)
            log("sysoff.command exit ${r.exitCode}")
        }
    }

    companion object {
        /** Minimal binary PPM (P6, 8 bit) reader for dcraw output; avoids an ImageMagick dependency. */
        fun readPpm(file: File): BufferedImage {
            BufferedInputStream(file.inputStream(), 1 shl 16).use { input ->
                fun token(): String {
                    val sb = StringBuilder()
                    while (true) {
                        val c = input.read()
                        if (c < 0) break
                        if (c == '#'.code) { while (true) { val d = input.read(); if (d < 0 || d == '\n'.code) break }; continue }
                        if (Character.isWhitespace(c)) { if (sb.isNotEmpty()) break else continue }
                        sb.append(c.toChar())
                    }
                    return sb.toString()
                }
                val magic = token(); require(magic == "P6") { "not a binary PPM: $magic" }
                val w = token().toInt(); val h = token().toInt(); val max = token().toInt()
                require(max == 255) { "only 8-bit PPM supported (maxval $max)" }
                val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                val row = ByteArray(w * 3)
                for (y in 0 until h) {
                    var off = 0
                    while (off < row.size) { val n = input.read(row, off, row.size - off); if (n < 0) throw IOException("truncated PPM"); off += n }
                    val px = IntArray(w) { x -> ((row[x * 3].toInt() and 0xff) shl 16) or ((row[x * 3 + 1].toInt() and 0xff) shl 8) or (row[x * 3 + 2].toInt() and 0xff) }
                    img.setRGB(0, y, w, 1, px, 0, w)
                }
                return img
            }
        }

        fun writeJpeg(img: BufferedImage, out: File, quality: Float) {
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val param = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality }
            ImageIO.createImageOutputStream(out).use { ios ->
                writer.output = ios
                writer.write(null, IIOImage(img, null, null), param)
            }
            writer.dispose()
        }
    }
}

object Json {
    fun str(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    /** Good-enough extraction of a top-level string/bool field from a flat JSON object. */
    fun field(json: String, name: String): String? =
        Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|true|false|null|-?[0-9.]+)").find(json)
            ?.let { m -> m.groups[2]?.value?.replace("\\\"", "\"")?.replace("\\\\", "\\") ?: m.groupValues[1] }
}
