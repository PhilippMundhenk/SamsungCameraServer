package org.mundhenk.cambackup.server

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mundhenk.cambackup.core.BackendSink
import org.mundhenk.cambackup.core.BackupServer
import org.mundhenk.cambackup.core.CameraSimulator
import org.mundhenk.cambackup.core.ServerIdentity
import org.mundhenk.cambackup.server.TestSupport.http
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.Random

class ApiAndRelayTest {
    private lateinit var dataDir: File
    private val servers = ArrayList<CamBackupServer>()
    private val log = java.util.concurrent.CopyOnWriteArrayList<String>()   // written by server threads too

    @Before fun setUp() { dataDir = TestSupport.tempDir("cambackup-api") }
    @After fun tearDown() { servers.forEach { it.stop() }; dataDir.deleteRecursively() }

    private fun start(env: Map<String, String> = emptyMap(), dir: File = dataDir, log: (String) -> Unit = { this.log += it }): CamBackupServer =
        CamBackupServer.start(dir, env = mapOf("CAMBACKUP_SSDP_ENABLED" to "false") + env, httpPortOverride = 0, log = log).also { servers += it }

    @Test
    fun apiEdgeCases() {
        val s = start(mapOf("CAMBACKUP_API_TOKEN" to "tok"))
        val base = "http://127.0.0.1:${s.port}"
        val auth = mapOf("X-API-Token" to "tok") // alternative header
        assertEquals(200 to "ok", http("GET", "$base/health"))
        assertEquals(401, http("HEAD", "$base/api/v1/files/2026-10-27/A.JPG", headers = mapOf("Authorization" to "Bearer wrong")).first)
        assertEquals(400, http("HEAD", "$base/api/v1/files/2026-10-27/A.JPG/extra", headers = auth).first)
        assertEquals(400, http("HEAD", "$base/api/v1/files/2026-10-27/%2E%2E", headers = auth).first)
        assertEquals(400, http("HEAD", "$base/api/v1/files/2026-10-27/", headers = auth).first)
        assertEquals(405, http("GET", "$base/api/v1/files/2026-10-27/A.JPG", headers = auth).first)
        assertEquals(405, http("DELETE", "$base/api/v1/files/2026-10-27/A.JPG", headers = auth).first)
        assertEquals(404, http("HEAD", "$base/api/v1/files/2026-10-27/A.JPG", headers = auth).first)
        // PUT without the X-NX-* headers: camera = remote address, class default
        assertEquals(201, http("PUT", "$base/api/v1/files/2026-10-27/A.JPG", "abc".toByteArray(), auth).first)
        assertEquals(201, http("POST", "$base/api/v1/files/2026-10-27/B.JPG", "abcd".toByteArray(), auth).first)
        assertEquals(200, http("PUT", "$base/api/v1/files/2026-10-27/B.JPG", "abcd".toByteArray(), auth).first)
        assertEquals("127.0.0.1", s.backup.stats.lastCamera)
        assertEquals(1, s.backup.stats.filesSkipped.get())
        // events
        assertEquals(400, http("POST", "$base/api/v1/events", "{}".toByteArray(), auth).first)
        assertEquals(400, http("POST", "$base/api/v1/events", """{"type":"pause"}""".toByteArray(), auth).first)
        assertEquals(204, http("POST", "$base/api/v1/events", """{"type":"start"}""".toByteArray(), auth).first)
        assertEquals(1, s.sink.activeSessions().size)
        assertEquals("127.0.0.1", s.sink.activeSessions().keys.first())
        assertEquals(204, http("POST", "$base/api/v1/events", """{"type":"done"}""".toByteArray(), auth).first)
        assertEquals(0, s.sink.activeSessions().size)
        assertEquals(405, http("GET", "$base/api/v1/events").first.let { if (it == 404) 405 else it })
        // log + status page + pairing ini + 404
        val (lc, lt) = http("GET", "$base/api/v1/log")
        assertEquals(200, lc); assertTrue(lt.isNotBlank())
        val (pc, page) = http("GET", "$base/")
        assertEquals(200, pc); assertTrue(page.contains("Last: <code>B.JPG</code>") && page.contains("A.JPG"))
        val (ic, ini) = http("GET", "$base/SAMSUNGAutoBackupDESC.ini")
        assertEquals(200, ic); assertTrue(ini.startsWith("MacAddr=")); assertTrue(ini.contains("UDN=uuid:${s.identity.udn}"))
        assertEquals(404, http("GET", "$base/nope").first)
        assertEquals(404, http("PUT", "$base/").first)
        s.pipeline.awaitIdle()
        // the status page lists probed tools once the background probe has run
        TestSupport.waitFor { http("GET", "$base/").second.contains("<h2>Tools</h2>") }
        val (_, json) = http("GET", "$base/api/v1/status")
        assertTrue(json, json.contains("\"totalFiles\":2") && json.contains("\"recent\":[{\"date\":\"2026-10-27\",\"name\":\"B.JPG\""))
    }

    @Test
    fun uiCanBeDisabledAndEmptyStatePageRenders() {
        val s = start(mapOf("CAMBACKUP_UI_ENABLED" to "false"))
        val base = "http://127.0.0.1:${s.port}"
        assertEquals(404, http("GET", "$base/").first)
        assertEquals(404, http("GET", "$base/pairing.zip").first)
        assertEquals(404, http("GET", "$base/SAMSUNGAutoBackupDESC.ini").first)
        assertEquals(200, http("GET", "$base/api/v1/status").first)
        // direct render with nothing received yet and no stats
        val page = StatusPage.render(Api.StatusModel(s.identity, 1, java.time.Duration.ofMinutes(61), null, 0, emptyList(), emptyMap(), emptyList(), emptyMap(), listOf("<l>"), "/d"))
        assertTrue(page.contains("Nothing received yet") && page.contains("up 1h 1m") && page.contains("&lt;l&gt;"))
        assertFalse(page.contains("<h2>Tools</h2>"))
        // server log default + main --help
        val s2 = start(dir = File(dataDir, "second"), log = ServerLog::log)
        assertTrue(ServerLog.snapshot().any { it.contains("api.token is empty") })
        assertTrue(http("GET", "http://127.0.0.1:${s2.port}/api/v1/log").second.contains("Pairing file for the SD card"))
        val bos = ByteArrayOutputStream(); val old = System.out
        System.setOut(PrintStream(bos, true, "UTF-8"))
        try { main(arrayOf("--help")) } finally { System.setOut(old) }
        assertTrue(bos.toString("UTF-8").startsWith("usage: cambackup-server"))
    }

    /** The container convention: state in /data, uploads in a separate volume (CAMBACKUP_STORAGE_DIR). */
    @Test
    fun uploadsLandInTheSeparateStorageDirectory() {
        val photos = TestSupport.tempDir("cambackup-photos")
        val export = TestSupport.tempDir("cambackup-export")
        try {
            val s = start(mapOf("CAMBACKUP_STORAGE_DIR" to photos.path, "CAMBACKUP_EXPORT_DIR" to export.path, "CAMBACKUP_EXPORT_SUBFOLDER" to "{yyyy}", "CAMBACKUP_EXIF_TOOL" to "no-exiftool-here"))
            assertEquals(photos.absoluteFile, s.config.storageDir.absoluteFile)
            val bytes = ByteArray(1000) { it.toByte() }
            CameraSimulator("127.0.0.1", s.port) { log += it }.runBackup(listOf(
                CameraSimulator.SimFile("A.JPG", "2026-10-27", "image/jpeg", bytes),
                CameraSimulator.SimFile("B.JPG", "2026-10-27", "image/jpeg", bytes),
            ))
            s.pipeline.awaitIdle()
            // nothing under <data>, both files went through /photos to /export
            assertFalse(File(dataDir, "incoming").exists())
            assertFalse(File(photos, "2026-10-27").listFiles()?.isNotEmpty() ?: false)
            assertArrayEquals(bytes, File(export, "2026/A.JPG").readBytes())
            assertEquals(File(export, "2026/B.JPG").path, s.ledger.find("2026-10-27", "B.JPG")!!.path)
            assertTrue(File(dataDir, "received.tsv").isFile)
            val (_, page) = http("GET", "http://127.0.0.1:${s.port}/")
            assertTrue(page.contains(photos.path.replace("\\", "\\")) || page.contains(org.mundhenk.cambackup.core.UpnpXml.escape(photos.path)))
            // without the override the default stays inside the data directory
            val s2 = start(dir = File(dataDir, "second"))
            assertEquals(File(File(dataDir, "second"), "incoming").absoluteFile, s2.config.storageDir.absoluteFile)
        } finally { photos.deleteRecursively(); export.deleteRecursively() }
    }

    /** storage.subfolder: flat by default, presets and patterns; same names never overwrite each other. */
    @Test
    fun storageLayoutsAndNameCollisions() {
        val bytes = ByteArray(10) { it.toByte() }
        fun session(s: CamBackupServer, vararg files: Pair<String, String>) =
            CameraSimulator("127.0.0.1", s.port) { log += it }.runBackup(files.map { (name, date) -> CameraSimulator.SimFile(name, date, "image/jpeg", bytes) })
        // default: flat; the camera's counter wrapped -> same name on another day is a new file
        val flat = start(mapOf("CAMBACKUP_EXIF_TOOL" to "no-exiftool-here"))
        session(flat, "SAM_0001.JPG" to "2026-10-27")
        session(flat, "SAM_0001.JPG" to "2026-10-27")                              // true duplicate
        val other = ByteArray(11) { it.toByte() }
        CameraSimulator("127.0.0.1", flat.port) { log += it }.runBackup(listOf(CameraSimulator.SimFile("SAM_0001.JPG", "2025-01-01", "image/jpeg", other)))
        flat.pipeline.awaitIdle()
        assertArrayEquals(bytes, File(flat.config.storageDir, "SAM_0001.JPG").readBytes())
        assertArrayEquals(other, File(flat.config.storageDir, "SAM_0001_1.JPG").readBytes())
        assertEquals(File(flat.config.storageDir, "SAM_0001_1.JPG").path, flat.ledger.find("2025-01-01", "SAM_0001.JPG")!!.path)
        assertEquals(2, flat.backup.stats.filesStored.get()); assertEquals(1, flat.backup.stats.filesSkipped.get())
        assertTrue(log.any { it.contains("SAM_0001.JPG already exists with different content, storing as SAM_0001_1.JPG") })
        assertTrue(http("GET", "http://127.0.0.1:${flat.port}/").second.contains("(flat)"))
        // presets and a pattern
        for ((layout, expected) in listOf("day" to "2026-10-27/A.JPG", "month" to "2026/10/A.JPG", "year" to "2026/A.JPG", "{yyyy}/{MM}/{dd}" to "2026/10/27/A.JPG", "{yyyy}-{MM}" to "2026-10/A.JPG")) {
            val dir = File(dataDir, layout.replace(Regex("[^A-Za-z]"), "_"))
            val s = start(mapOf("CAMBACKUP_STORAGE_SUBFOLDER" to layout, "CAMBACKUP_EXIF_TOOL" to "no-exiftool-here"), dir = dir)
            session(s, "A.JPG" to "2026-10-27")
            s.pipeline.awaitIdle()
            assertTrue("$layout -> $expected", File(s.config.storageDir, expected).isFile)
            assertTrue(http("HEAD", "http://127.0.0.1:${s.port}/api/v1/files/2026-10-27/A.JPG").first == 200)
        }
    }

    @Test
    fun troubleshootingCommands() {
        val s = start(mapOf("NXBACKUP_SERVER_NAME" to "[PC]Sim").mapKeys { it.key.replace("NXBACKUP", "CAMBACKUP") })
        fun run(vararg a: String): Pair<Int, String> { val bos = ByteArrayOutputStream(); val code = ServerCli.run(arrayOf(*a), PrintStream(bos, true, "UTF-8")); return code to bos.toString("UTF-8") }
        // simulate: a full camera session against the running server
        val (code, out) = run("simulate", "127.0.0.1", s.port.toString(), "3")
        assertEquals(out, 0, code)
        assertTrue(out, out.contains("Session done: server '[PC]Sim'") && out.contains("uploaded 3 of 3"))
        s.pipeline.awaitIdle()
        assertEquals(3, s.ledger.size())
        assertTrue(log.any { it.startsWith("Stored SIM_0001.JPG (500000 bytes, ") && it.contains("MB/s in") })
        // second run: everything is a duplicate, still a complete session
        assertEquals(0, run("simulate", "127.0.0.1", s.port.toString(), "3").first)
        assertEquals(3, s.backup.stats.filesSkipped.get())
        // usage errors and a discovery that may or may not find something (depends on the network)
        assertEquals(2, run("simulate").first)
        assertEquals(2, run("bogus").first)
        assertTrue(run("simulate").second.startsWith("usage:"))
        val (dc, dout) = run("discover", "1")
        assertTrue(dout, dout.startsWith("SSDP: searching") && (dc == 0 && dout.contains("Found:") || dc == 1 && dout.contains("No server answered")))
    }

    @Test
    fun unreadablePairingFileIsReplaced() {
        File(dataDir, "SAMSUNGAutoBackupDESC.ini").writeText("garbage")
        val s = start()
        assertTrue(log.any { it.startsWith("Ignoring unreadable SAMSUNGAutoBackupDESC.ini") })
        assertTrue(log.any { it.startsWith("Generated identity") })
        assertTrue(File(dataDir, "SAMSUNGAutoBackupDESC.ini").readText().contains("UDN=uuid:${s.identity.udn}"))
        // short upload: declared size larger than the body -> rejected, nothing recorded
        val base = "http://127.0.0.1:${s.port}"
        val raw = java.net.Socket("127.0.0.1", s.port).use { sock ->
            sock.getOutputStream().write("PUT /api/v1/files/2026-10-27/SHORT.JPG HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc".toByteArray())
            sock.shutdownOutput()
            sock.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }
        assertTrue(raw, raw.startsWith("HTTP/1.1 500") && raw.contains("short upload of SHORT.JPG: 3 of 10 bytes"))
        assertEquals(0, s.ledger.size())
        assertFalse(File(s.config.storageDir, "SHORT.JPG.part").exists())
        assertEquals(404, http("HEAD", "$base/api/v1/files/2026-10-27/SHORT.JPG").first)
    }

    /**
     * Phone relay end-to-end on the JVM: camera -> phone (core BackupServer + BackendSink, exactly
     * what the Android service runs in "server" mode) -> home server API -> disk, ledger, pipeline.
     */
    @Test
    fun cameraThroughPhoneToHomeServer() {
        val home = start(mapOf("CAMBACKUP_API_TOKEN" to "relay", "CAMBACKUP_SERVER_NAME" to "[PC]Home",
            "CAMBACKUP_RENAME_PATTERN" to "{yyyy}{MM}{dd}_{base}.{ext}", "CAMBACKUP_EXIF_TOOL" to "no-exiftool-here"))
        val phoneLog = ArrayList<String>()
        val phoneSink = BackendSink("http://127.0.0.1:${home.port}", { phoneLog += it }, "relay")
        val phone = BackupServer(ServerIdentity(home.identity.udn, "[PC]Phone", ServerIdentity.DEFAULT_MAC), phoneSink, httpPort = 0, log = { phoneLog += it }, enableSsdp = false)
        phone.start()
        try {
            val rnd = Random(9)
            val jpg = ByteArray(1_500_000).also { rnd.nextBytes(it) }
            val mp4 = ByteArray(400_000).also { rnd.nextBytes(it) }
            val files = listOf(
                CameraSimulator.SimFile("SAM_0100.JPG", "2026-10-27", "image/jpeg", jpg),
                CameraSimulator.SimFile("SAM_0101.MP4", "2026-10-27", "video/mp4", mp4),
            )
            val r = CameraSimulator("127.0.0.1", phone.boundPort) { phoneLog += it }.runBackup(files, sysOff = true)
            assertEquals(home.identity.udn, r.udn)
            assertEquals(listOf("SAM_0100.JPG", "SAM_0101.MP4"), r.uploaded)
            home.pipeline.awaitIdle()
            assertArrayEquals(jpg, File(home.config.storageDir, "20261027_SAM_0100.JPG").readBytes())
            assertArrayEquals(mp4, File(home.config.storageDir, "20261027_SAM_0101.MP4").readBytes())
            assertEquals(2, home.backup.stats.filesStored.get())
            assertEquals(1, home.backup.stats.sessions.get())
            assertTrue(log.any { it.contains("Backup session started (via phone 127.0.0.1, camera 127.0.0.1)") })
            assertTrue(log.any { it.contains("camera asks for shutdown") })
            // second pass: phone asks HEAD first, home says it has them -> nothing re-sent, camera still happy
            val r2 = CameraSimulator("127.0.0.1", phone.boundPort) { phoneLog += it }.runBackup(files)
            assertEquals(2, r2.uploaded.size)
            assertEquals(2, phone.stats.filesSkipped.get())
            assertEquals(2, home.backup.stats.filesStored.get())
            assertEquals(2, home.backup.stats.filesSkipped.get())
            assertTrue(phoneLog.any { it.contains("Ready for SAM_0100.JPG") && it.contains("duplicate") })
        } finally { phone.stop() }
    }
}
