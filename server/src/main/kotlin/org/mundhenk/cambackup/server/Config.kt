package org.mundhenk.cambackup.server

import java.io.File
import java.util.Properties

/**
 * Server configuration: `<dataDir>/cambackup.conf` (Java properties). Every key can be
 * overridden by an environment variable `CAMBACKUP_<KEY>` (dots replaced by underscores,
 * upper-cased, e.g. `CAMBACKUP_EXIF_TIMESHIFT`). A commented default file is written on
 * first start so the data volume documents itself. The web UI's settings page edits the file
 * through [save]; the environment always wins over the file.
 */
class Config(val dataDir: File, private val props: Properties, private val env: Map<String, String> = System.getenv()) {

    enum class Source { ENVIRONMENT, FILE, DEFAULT }

    val file: File get() = File(dataDir, FILE_NAME)

    private fun envKey(key: String) = "CAMBACKUP_" + key.uppercase().replace('.', '_')

    operator fun get(key: String): String? {
        val fromEnv = env[envKey(key)]?.trim()
        if (!fromEnv.isNullOrEmpty()) return fromEnv
        return synchronized(props) { props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } }
    }

    /** Where the effective value of [key] comes from. */
    fun source(key: String): Source = when {
        !env[envKey(key)].isNullOrBlank() -> Source.ENVIRONMENT
        !synchronized(props) { props.getProperty(key) }.isNullOrBlank() -> Source.FILE
        else -> Source.DEFAULT
    }

    /** The value in the file only (what the settings page edits), null when unset there. */
    fun fileValue(key: String): String? = synchronized(props) { props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } }

    /** The environment override for [key], if any. */
    fun envValue(key: String): String? = env[envKey(key)]?.trim()?.takeIf { it.isNotEmpty() }

    fun string(key: String, default: String): String = this[key] ?: default
    fun stringOrNull(key: String): String? = this[key]
    fun int(key: String, default: Int): Int = this[key]?.toIntOrNull() ?: default
    fun bool(key: String, default: Boolean): Boolean =
        this[key]?.let { it.equals("true", true) || it == "1" || it.equals("yes", true) } ?: default
    fun file(key: String, default: File): File = this[key]?.let { resolve(it) } ?: default
    fun fileOrNull(key: String): File? = this[key]?.let { resolve(it) }

    private fun resolve(path: String): File = File(path).let { if (it.isAbsolute) it else File(dataDir, path) }

    // ---- typed accessors ----
    val httpPort get() = int("http.port", 52235)
    val serverName get() = stringOrNull("server.name")
    val serverUdn get() = stringOrNull("server.udn")
    val ssdpEnabled get() = bool("ssdp.enabled", true)
    val storageDir get() = file("storage.dir", File(dataDir, "incoming"))
    /** Folder layout under storage.dir: pattern with {yyyy} {MM} {dd} and '/', or day | month | year | flat. */
    val storageSubfolder get() = org.mundhenk.cambackup.core.Layout.normalize(stringOrNull("storage.subfolder"))
    val apiToken get() = stringOrNull("api.token")
    val uiEnabled get() = bool("ui.enabled", true)
    /** The settings page at /settings (protected by api.token when one is set). */
    val uiSettings get() = bool("ui.settings", true)

    val exifTimeShift get() = stringOrNull("exif.timeshift")
    val exifTool get() = string("exif.tool", "exiftool")
    /** Extra directories searched for tools before PATH (File.pathSeparator separated). */
    val toolsPath: List<File> get() = stringOrNull("tools.path")?.split(File.pathSeparatorChar)?.filter { it.isNotBlank() }?.map { resolve(it) } ?: emptyList()
    val rawConvert get() = bool("raw.convert", false)
    val rawConverter get() = string("raw.converter", "auto")
    val rawOnlyIfNoJpeg get() = bool("raw.onlyifnojpeg", true)
    val rawJpegQuality get() = int("raw.quality", 92)
    val renamePattern get() = stringOrNull("rename.pattern")
    val exportDir get() = fileOrNull("export.dir")
    val exportSubfolder get() = string("export.subfolder", "{yyyy}-{MM}-{dd}")
    val webhookUrl get() = stringOrNull("webhook.url")
    val webhookToken get() = stringOrNull("webhook.token")
    val hookCommand get() = stringOrNull("hook.command")
    val sysOffCommand get() = stringOrNull("sysoff.command")

    /** Non-secret view for the status page. */
    fun summary(): List<Pair<String, String>> = listOf(
        "http.port" to httpPort.toString(),
        "ssdp.enabled" to ssdpEnabled.toString(),
        "storage.dir" to storageDir.path,
        "storage.subfolder" to storageSubfolder.ifEmpty { "(flat)" },
        "api.token" to if (apiToken != null) "(set)" else "(none - LAN only)",
        "exif.timeshift" to (exifTimeShift ?: "(off)"),
        "raw.convert" to if (rawConvert) rawConverter else "false",
        "rename.pattern" to (renamePattern ?: "(off)"),
        "export.dir" to (exportDir?.path ?: "(off)"),
        "webhook.url" to (webhookUrl ?: "(off)"),
        "hook.command" to (hookCommand ?: "(off)"),
        "sysoff.command" to (sysOffCommand ?: "(off)"),
    )

    /**
     * Writes [updates] into the config file (a blank value removes the key's value) and reloads.
     * Comments and the order of the file are preserved: existing `key=` lines are replaced in
     * place, unknown keys are appended. Keys with an environment override are written too, the
     * environment just keeps winning until it is removed.
     */
    fun save(updates: Map<String, String>) {
        val text = if (file.isFile) file.readText() else DEFAULT_CONF
        val lines = text.split('\n').toMutableList()
        val remaining = LinkedHashMap(updates)
        for (i in lines.indices) {
            val line = lines[i]
            val key = line.substringBefore('=', "").trim()
            if (line.isBlank() || line.trimStart().startsWith("#") || !line.contains('=') || key !in remaining) continue
            lines[i] = "$key=${escape(remaining.remove(key)!!)}"
        }
        if (remaining.isNotEmpty()) {
            if (lines.lastOrNull()?.isNotBlank() == true) lines += ""
            for ((k, v) in remaining) lines += "$k=${escape(v)}"
        }
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n"))
        reload()
    }

    /** Re-reads the file; the typed accessors pick the new values up on their next call. */
    fun reload() {
        val p = Properties()
        if (file.isFile) file.inputStream().use { p.load(it) }
        synchronized(props) { props.clear(); props.putAll(p) }
    }

    /** Properties escaping for the few characters that matter (backslashes, leading spaces). */
    private fun escape(v: String): String = v.trim().replace("\\", "\\\\")

    companion object {
        const val FILE_NAME = "cambackup.conf"

        fun load(dataDir: File, env: Map<String, String> = System.getenv()): Config {
            dataDir.mkdirs()
            val f = File(dataDir, FILE_NAME)
            if (!f.exists()) f.writeText(DEFAULT_CONF)
            val p = Properties()
            f.inputStream().use { p.load(it) }
            return Config(dataDir, p, env)
        }

        val DEFAULT_CONF: String = """
            |# cambackup server configuration. Every key can be overridden by an environment
            |# variable CAMBACKUP_<KEY> (dots -> underscores, upper case), e.g. CAMBACKUP_EXIF_TIMESHIFT.
            |# Relative paths are resolved against the data directory. The web UI's settings page
            |# (http://<host>:52235/settings) edits this file; the environment always wins over it.
            |
            |# HTTP port for the camera (UPnP), the phone (backend API) and the web UI.
            |http.port=52235
            |
            |# Name the camera shows. Leave empty to keep the name in SAMSUNGAutoBackupDESC.ini.
            |server.name=
            |# UDN (uuid without the "uuid:" prefix). Set this to the UDN shown in the Android app so
            |# one pairing file on the SD card works for phone and home server. Leave empty to keep
            |# (or generate) the UDN in SAMSUNGAutoBackupDESC.ini.
            |server.udn=
            |
            |# Answer SSDP discovery (the container must be on the camera's network: host networking or macvlan).
            |ssdp.enabled=true
            |
            |# Where uploads land: <storage.dir>/<storage.subfolder>/<name>
            |storage.dir=incoming
            |# Folder layout under storage.dir, from the camera's date. flat (no folders, the default),
            |# day ({yyyy}-{MM}-{dd}), month ({yyyy}/{MM}), year ({yyyy}), or any pattern with {yyyy} {MM} {dd} and '/'.
            |# A second file with the same name gets a numbered suffix, nothing is overwritten.
            |storage.subfolder=flat
            |
            |# Bearer token the Android app must send in backend mode (empty = no auth; LAN only!).
            |# Also protects the settings page (HTTP Basic auth, any user name, this token as password).
            |api.token=
            |# Serve the status page and the pairing-file download at /
            |ui.enabled=true
            |# Serve the settings page at /settings
            |ui.settings=true
            |
            |# ---- post-processing pipeline (a stage is skipped when unset or when its tool is missing) ----
            |
            |# Shift all EXIF dates, exiftool syntax without the tag: "+1:00" (one hour later), "-0:30",
            |# "+0:0:1 0:0:0" (one day). Cameras of this generation have no DST handling; fix it here.
            |exif.timeshift=
            |exif.tool=exiftool
            |# Extra directories to look for tools in, before PATH (":"-separated on Linux, ";" on Windows).
            |tools.path=
            |
            |# Convert Samsung .SRW RAW files to a JPEG next to the RAW (<name>.jpg).
            |raw.convert=false
            |# auto | darktable | rawtherapee | dcraw | none   (auto = first one found on PATH)
            |raw.converter=auto
            |# Skip the conversion when the camera already delivered the JPEG of a RAW+JPEG pair.
            |raw.onlyifnojpeg=true
            |raw.quality=92
            |
            |# Rename files. Tokens: {yyyy} {MM} {dd} {HH} {mm} {ss} (EXIF DateTimeOriginal via exiftool,
            |# falling back to the camera's date and 00:00:00), {base} {ext} {name} (original name),
            |# {camera} (model, needs exiftool). Example: {yyyy}{MM}{dd}_{HH}{mm}{ss}_{base}.{ext}
            |rename.pattern=
            |
            |# Move finished files into this directory (e.g. a Synology Drive share), under export.subfolder.
            |export.dir=
            |export.subfolder={yyyy}-{MM}-{dd}
            |
            |# POST a JSON summary of every session here (e.g. a Home Assistant webhook).
            |webhook.url=
            |webhook.token=
            |
            |# Shell command run after every session (env: CAMBACKUP_EVENT, CAMBACKUP_CAMERA, CAMBACKUP_SYSOFF,
            |# CAMBACKUP_FILES (one path per line), CAMBACKUP_STORED, CAMBACKUP_SKIPPED).
            |hook.command=
            |# Command run when the camera asks to shut the PC down after the backup ("SysOff").
            |sysoff.command=
            |""".trimMargin()
    }
}
