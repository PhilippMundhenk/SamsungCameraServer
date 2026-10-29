package org.mundhenk.cambackup.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mundhenk.cambackup.core.CameraSimulator
import org.mundhenk.cambackup.server.TestSupport.http
import java.io.File
import java.util.Base64

/** The settings page: rendering, saving, environment precedence, auth, and the file it edits. */
class SettingsTest {
    private lateinit var dataDir: File
    private val servers = ArrayList<CamBackupServer>()
    private val log = java.util.concurrent.CopyOnWriteArrayList<String>()   // written by server threads too

    @Before fun setUp() { dataDir = TestSupport.tempDir("cambackup-settings") }
    @After fun tearDown() { servers.forEach { it.stop() }; dataDir.deleteRecursively() }

    private fun start(env: Map<String, String> = emptyMap(), dir: File = dataDir): CamBackupServer =
        CamBackupServer.start(dir, env = mapOf("CAMBACKUP_SSDP_ENABLED" to "false") + env, httpPortOverride = 0, log = { log += it }).also { servers += it }

    private fun form(vararg pairs: Pair<String, String>) =
        pairs.joinToString("&") { (k, v) -> java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8") }.toByteArray()

    @Test
    fun pageShowsEveryKeyWithItsSourceAndSavesTheFile() {
        val s = start(mapOf("CAMBACKUP_EXPORT_SUBFOLDER" to "{yyyy}/{MM}", "CAMBACKUP_EXIF_TOOL" to "no-exiftool-here"))
        val base = "http://127.0.0.1:${s.port}"
        val (code, page) = http("GET", "$base/settings")
        assertEquals(200, code)
        for (def in SettingsCatalog.all) assertTrue(def.key, page.contains("<code>${def.key}</code>"))
        assertTrue(page.contains("No token is set"))                                          // warning banner
        assertTrue(page.contains("<span class=\"badge env\">environment</span> <code>{yyyy}/{MM}</code>"))  // env value, read-only
        assertTrue(page.contains("Copy the 3 environment values into the file"))             // ssdp.enabled (test harness) + export.subfolder + exif.tool
        assertTrue(page.contains("name=\"storage.subfolder\"") && page.contains("<option value=\"flat\" selected>"))
        assertTrue(page.contains("type=\"checkbox\" id=\"raw.convert\" name=\"raw.convert\" value=\"true\">"))  // unchecked
        assertTrue(page.contains("id=\"raw.onlyifnojpeg\" name=\"raw.onlyifnojpeg\" value=\"true\" checked"))
        assertTrue(page.contains("badge restart"))
        // the status page links to it and shows the storage summary in its card
        val (_, status) = http("GET", "$base/")
        assertTrue(status, status.contains("Photos &rarr;") && status.contains("(flat)") && status.contains("href=\"/settings\""))
        assertFalse(status.contains("<h2>Pipeline</h2>"))

        // save: text, select, number, checkbox on and off; env-overridden keys are left alone
        val (saveCode, _) = http("POST", "$base/settings", form(
            "rename.pattern" to "{yyyy}_{base}.{ext}", "storage.subfolder" to "month", "raw.quality" to "80",
            "raw.convert" to "true", "raw.onlyifnojpeg" to "false", "ssdp.enabled" to "false", "export.subfolder" to "ignored-because-env", "unknown.key" to "x", "api.token" to " t0k "),
            mapOf("Content-Type" to "application/x-www-form-urlencoded"), followRedirects = false)
        assertEquals(303, saveCode)
        val text = s.config.file.readText()
        assertTrue(text, text.contains("rename.pattern={yyyy}_{base}.{ext}") && text.contains("storage.subfolder=month") && text.contains("raw.quality=80"))
        assertTrue(text.contains("raw.convert=true") && text.contains("raw.onlyifnojpeg=false"))  // unchecked boxes are saved as false
        assertFalse(text.contains("ssdp.enabled=false"))                                        // env-overridden keys are not editable
        assertFalse(text.contains("unknown.key")); assertFalse(text.contains("ignored-because-env"))
        assertTrue(text.startsWith("# cambackup server configuration"))                     // comments preserved
        assertEquals("{yyyy}_{base}.{ext}", s.config.renamePattern)                          // live
        assertEquals("{yyyy}/{MM}", s.config.exportSubfolder)                                // env still wins
        assertEquals(Config.Source.FILE, s.config.source("rename.pattern")); assertEquals(Config.Source.ENVIRONMENT, s.config.source("export.subfolder")); assertEquals(Config.Source.DEFAULT, s.config.source("webhook.url"))
        assertEquals("t0k", s.config.apiToken)
        // the new layout applies to the next upload, the new token to the next request
        assertEquals(401, http("GET", "$base/settings").first)
        val basic = mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("anyone:t0k".toByteArray()))
        val (okCode, saved) = http("GET", "$base/settings?saved=1", headers = basic)
        assertEquals(200, okCode); assertTrue(saved.contains("Saved.") && saved.contains("value=\"{yyyy}_{base}.{ext}\"") && saved.contains("<option value=\"month\" selected>"))
        assertFalse(saved.contains("No token is set"))
        assertEquals(200, http("GET", "$base/settings", headers = mapOf("Authorization" to "Bearer t0k")).first)
        assertEquals(401, http("GET", "$base/settings", headers = mapOf("Authorization" to "Basic not-base64!!")).first)
        assertEquals(401, http("POST", "$base/settings/adopt-env", ByteArray(0)).first)
        CameraSimulator("127.0.0.1", s.port) { log += it }.runBackup(listOf(CameraSimulator.SimFile("A.JPG", "2026-10-27", "image/jpeg", ByteArray(5))))
        s.pipeline.awaitIdle()
        assertTrue(File(s.config.storageDir, "2026/10/2026_A.JPG").isFile)

        // validation error re-renders the form instead of saving
        val (errCode, errPage) = http("POST", "$base/settings", form("raw.quality" to "lots"), mapOf("Content-Type" to "application/x-www-form-urlencoded") + basic)
        assertEquals(200, errCode); assertTrue(errPage.contains("is not a number"))
        assertTrue(s.config.file.readText().contains("raw.quality=80"))

        // adopt the environment into the file
        assertEquals(303, http("POST", "$base/settings/adopt-env", ByteArray(0), basic, followRedirects = false).first)
        val adopted = s.config.file.readText()
        assertTrue(adopted.contains("export.subfolder={yyyy}/{MM}") && adopted.contains("exif.tool=no-exiftool-here"))
        assertTrue(log.any { it.startsWith("Settings: copied 3 environment value(s)") })
    }

    /** #19: the settings page has a section per known camera; saved values land as camera.<id>.<key> and steer the sink and pipeline. */
    @Test
    fun perCameraSectionOnTheSettingsPage() {
        val s = start(mapOf("CAMBACKUP_API_TOKEN" to "tok", "CAMBACKUP_EXIF_TOOL" to "no-exiftool-here"))
        val base = "http://127.0.0.1:${s.port}"
        val basic = mapOf("Authorization" to "Basic " + java.util.Base64.getEncoder().encodeToString("x:tok".toByteArray()), "Content-Type" to "application/x-www-form-urlencoded")
        var page = http("GET", "$base/settings", headers = basic).second
        assertTrue(page, page.contains("<h2>Per camera</h2>") && page.contains("No camera has talked to this server yet"))
        s.devices.seen(Devices.Kind.CAMERA, "127.0.0.1", "127.0.0.1", session = true)
        s.devices.rename(Devices.Kind.CAMERA, "127.0.0.1", "NX1000")
        page = http("GET", "$base/settings", headers = basic).second
        assertTrue(page, page.contains("<h3>NX1000 <code>127.0.0.1</code></h3>") && page.contains("name=\"camera.127_0_0_1.exif.timeshift\""))
        assertTrue(page.contains("name=\"camera.127_0_0_1.raw.convert\"><option value=\"\" selected>inherit (off)</option>"))
        assertTrue(page.contains("name=\"camera.127_0_0_1.storage.subfolder\"><option value=\"\" selected>inherit ("))
        assertFalse(page.contains("camera.127_0_0_1.exif.tool"))                  // tool locations are not per camera
        // a bad number is reported, nothing saved
        val (badCode, badPage) = http("POST", "$base/settings", form("camera.127_0_0_1.raw.quality" to "abc"), basic, followRedirects = false)
        assertEquals(200, badCode); assertTrue(badPage, badPage.contains("JPEG quality (camera 127.0.0.1): &apos;abc&apos; is not a number"))
        // the real thing: layout, time shift, a boolean, a number; blank entries are ignored
        val (code, _) = http("POST", "$base/settings", form(
            "camera.127_0_0_1.storage.subfolder" to "year", "camera.127_0_0_1.exif.timeshift" to "+1:00", "camera.127_0_0_1.raw.convert" to "true",
            "camera.127_0_0_1.raw.quality" to "70", "camera.127_0_0_1.rename.pattern" to "", "camera.127_0_0_1.raw.onlyifnojpeg" to "maybe", "camera.10_0_0_9.nonsense" to "x"),
            basic, followRedirects = false)
        assertEquals(303, code)
        val text = s.config.file.readText()
        assertTrue(text, text.contains("camera.127_0_0_1.storage.subfolder=year") && text.contains("camera.127_0_0_1.exif.timeshift=+1:00") && text.contains("camera.127_0_0_1.raw.convert=true") && text.contains("camera.127_0_0_1.raw.quality=70"))
        assertFalse(text.contains("onlyifnojpeg=maybe") || text.contains("nonsense"))
        assertEquals("+1:00", s.config.forCamera("127.0.0.1").exifTimeShift); assertNull(s.config.exifTimeShift)
        assertTrue(s.config.forCamera("127.0.0.1").rawConvert); assertFalse(s.config.rawConvert)
        // the page shows the saved values, the status page lists them
        page = http("GET", "$base/settings", headers = basic).second
        assertTrue(page, page.contains("name=\"camera.127_0_0_1.exif.timeshift\" value=\"+1:00\"") && page.contains("<option value=\"true\" selected>on</option>") && page.contains("<option value=\"year\" selected>"))
        val status = http("GET", "$base/").second
        assertTrue(status, status.contains("Own settings: <code>exif.timeshift=+1:00</code>"))
        // the camera's files land in its own layout (year folders), the general layout stays flat
        CameraSimulator("127.0.0.1", s.port) { log += it }.runBackup(listOf(CameraSimulator.SimFile("Y.JPG", "2026-10-28", "image/jpeg", ByteArray(5))))
        s.pipeline.awaitIdle()
        assertTrue(File(s.config.storageDir, "2026/Y.JPG").isFile)
        // back to inherit: blank removes the override
        http("POST", "$base/settings", form("camera.127_0_0_1.exif.timeshift" to "", "camera.127_0_0_1.raw.convert" to ""), basic, followRedirects = false)
        assertNull(s.config.forCamera("127.0.0.1").exifTimeShift); assertFalse(s.config.forCamera("127.0.0.1").rawConvert)
        assertEquals(setOf("storage.subfolder", "raw.quality"), s.config.cameraOverrides("127.0.0.1").keys)
    }

    @Test
    fun configSaveHandlesMissingFileUnknownKeysAndEscaping() {
        val cfg = TestSupport.config(dataDir, "hook.command" to "echo hi")
        assertFalse(cfg.file.exists())
        cfg.save(mapOf("rename.pattern" to "  {name}  ", "my.extra" to "C:\\photos\\x", "hook.command" to ""))
        val text = cfg.file.readText()
        assertTrue(text, text.contains("rename.pattern={name}") && text.contains("my.extra=C:\\\\photos\\\\x") && text.contains("\nhook.command=\n"))
        assertEquals("{name}", cfg.renamePattern)
        assertNull(cfg.hookCommand)                                      // blank value = unset
        assertEquals("C:\\photos\\x", cfg["my.extra"])                   // unknown key appended and readable
        assertNull(cfg.fileValue("webhook.url")); assertEquals("{name}", cfg.fileValue("rename.pattern"))
        // a file without the key: appended after a blank line, comments untouched
        cfg.file.writeText("# mine\nhttp.port=1\n")
        cfg.save(mapOf("webhook.url" to "http://h"))
        assertEquals("# mine\nhttp.port=1\n\nwebhook.url=http://h", cfg.file.readText())
        cfg.reload(); assertEquals("http://h", cfg.webhookUrl)
    }

    @Test
    fun settingsPageCanBeSwitchedOffAndRendersSecretsMasked() {
        val s = start(mapOf("CAMBACKUP_UI_SETTINGS" to "false"))
        assertEquals(404, http("GET", "http://127.0.0.1:${s.port}/settings").first)
        assertEquals(200, http("GET", "http://127.0.0.1:${s.port}/").first)
        val s2 = start(mapOf("CAMBACKUP_API_TOKEN" to "secret", "CAMBACKUP_WEBHOOK_TOKEN" to "wh"), dir = File(dataDir, "two"))
        val page = http("GET", "http://127.0.0.1:${s2.port}/settings", headers = mapOf("Authorization" to "Bearer secret")).second
        assertFalse(page.contains("secret")); assertFalse(page.contains(">wh<"))
        assertTrue(page.contains("••••••"))
        // a custom pattern in the file shows as the selected custom option; a differing file value next to an env value is reported
        s2.config.save(mapOf("storage.subfolder" to "{yyyy}/{MM}/{dd}", "webhook.token" to "other"))
        val page2 = http("GET", "http://127.0.0.1:${s2.port}/settings", headers = mapOf("Authorization" to "Bearer secret")).second
        assertTrue(page2.contains("<option value=\"{yyyy}/{MM}/{dd}\" selected>{yyyy}/{MM}/{dd} (custom)</option>"))
        assertTrue(page2.contains("file has <code>••••••</code>, ignored"))
        // forced colour scheme for screenshots / preference
        assertTrue(http("GET", "http://127.0.0.1:${s.port}/?scheme=light").second.startsWith("<!doctype html><html lang=\"en\" data-scheme=\"light\">"))
        assertTrue(http("GET", "http://127.0.0.1:${s2.port}/settings?scheme=dark", headers = mapOf("Authorization" to "Bearer secret")).second.contains("<html lang=\"en\" data-scheme=\"dark\">"))
        assertFalse(http("GET", "http://127.0.0.1:${s.port}/?scheme=blue").second.contains("<html lang=\"en\" data-scheme"))
        // direct render helpers
        assertEquals(mapOf("a" to "1", "b" to "x y", "c" to ""), SettingsCatalog.parseForm("a=1&b=x+y&c&"))
        assertNull(SettingsCatalog.byKey("nope")); assertEquals("Hooks", SettingsCatalog.byKey("hook.command")!!.section)
    }
}
