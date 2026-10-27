package org.mundhenk.cambackup.server

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mundhenk.cambackup.core.CameraSimulator
import org.mundhenk.cambackup.core.HttpResponse
import org.mundhenk.cambackup.core.HttpServer
import org.mundhenk.cambackup.core.PairingFile
import java.awt.image.BufferedImage
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipInputStream

class ServerTest {
    private lateinit var dataDir: File
    private val servers = ArrayList<CamBackupServer>()
    private val log: (String) -> Unit = { println("  | $it") }

    @Before
    fun setUp() {
        dataDir = File.createTempFile("cambackup-server", "").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
        dataDir.deleteRecursively()
    }

    private fun start(env: Map<String, String> = emptyMap(), dir: File = dataDir): CamBackupServer =
        CamBackupServer.start(dir, env = mapOf("CAMBACKUP_SSDP_ENABLED" to "false") + env, httpPortOverride = 0, log = log).also { servers += it }

    private fun http(method: String, url: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap(), chunked: Boolean = false): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
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

    @Test
    fun cameraSessionStoresFilesAndDeduplicates() {
        val s = start(mapOf("CAMBACKUP_SERVER_NAME" to "[PC]Home"))
        val rnd = Random(1)
        val jpg = ByteArray(2_000_000).also { rnd.nextBytes(it) }
        val srw = ByteArray(1_000_000).also { rnd.nextBytes(it) }
        val files = listOf(
            CameraSimulator.SimFile("SAM_0001.JPG", "2026-10-26", "image/jpeg", jpg),
            CameraSimulator.SimFile("SAM_0001.SRW", "2026-10-26", "image/x-samsung-srw", srw),
        )
        val sim = CameraSimulator("127.0.0.1", s.port, log)
        val r = sim.runBackup(files, sysOff = false)
        assertEquals("[PC]Home", r.friendlyName)
        assertEquals(s.identity.udn, r.udn)
        assertEquals(listOf("SAM_0001.JPG", "SAM_0001.SRW"), r.uploaded)
        s.pipeline.awaitIdle()
        val stored = File(s.config.storageDir, "2026-10-26/SAM_0001.JPG")
        assertArrayEquals(jpg, stored.readBytes())
        assertArrayEquals(srw, File(s.config.storageDir, "2026-10-26/SAM_0001.SRW").readBytes())
        assertEquals(2, s.ledger.size())
        assertTrue(File(dataDir, "received.tsv").readText().contains("SAM_0001.SRW\t1000000\t"))

        // second session: both are duplicates and nothing is overwritten
        sim.runBackup(files)
        s.pipeline.awaitIdle()
        assertEquals(2, s.backup.stats.filesStored.get())
        assertEquals(2, s.backup.stats.filesSkipped.get())
        assertEquals(2, s.ledger.size())

        // status + UI + pairing download
        val (code, json) = http("GET", "http://127.0.0.1:${s.port}/api/v1/status")
        assertEquals(200, code)
        assertTrue(json, json.contains("\"filesStored\":2") && json.contains("\"name\":\"[PC]Home\""))
        val (uiCode, html) = http("GET", "http://127.0.0.1:${s.port}/")
        assertEquals(200, uiCode)
        assertTrue(html.contains("[PC]Home") && html.contains("SAM_0001.SRW"))
        val zip = (URL("http://127.0.0.1:${s.port}/pairing.zip").openConnection() as HttpURLConnection).inputStream.use { it.readBytes() }
        ZipInputStream(zip.inputStream()).use { z ->
            assertEquals(PairingFile.SD_CARD_PATH, z.nextEntry!!.name)
            assertEquals(s.identity, PairingFile.parse(z.readBytes().toString(Charsets.US_ASCII)))
        }
        // identity is persisted as the pairing file in the data dir and survives a restart
        assertEquals(s.identity, PairingFile.parse(File(dataDir, "SAMSUNGAutoBackupDESC.ini").readText()))
        s.stop(); servers.remove(s)
        val s2 = start()
        assertEquals(s.identity, s2.identity)
        assertEquals(2, s2.ledger.size())
        assertTrue(s2.sink.exists(org.mundhenk.cambackup.core.IncomingFile("SAM_0001.JPG", "2026-10-26", jpg.size.toLong(), "image/jpeg", "object.item.imageItem", "x")))
    }

    @Test
    fun backendApiContractUsedByThePhone() {
        val s = start(mapOf("CAMBACKUP_API_TOKEN" to "s3cret"))
        val base = "http://127.0.0.1:${s.port}/api/v1"
        val auth = mapOf("Authorization" to "Bearer s3cret")
        val bytes = ByteArray(300_000).also { Random(2).nextBytes(it) }

        assertEquals(401, http("HEAD", "$base/files/2026-10-26/SAM_0002.JPG").first)
        assertEquals(401, http("PUT", "$base/files/2026-10-26/SAM_0002.JPG", bytes).first)
        assertEquals(401, http("POST", "$base/events", """{"type":"start","camera":"10.0.0.5","sysOff":false}""".toByteArray()).first)

        assertEquals(204, http("POST", "$base/events", """{"type":"start","camera":"10.0.0.5","sysOff":false}""".toByteArray(), auth + ("Content-Type" to "application/json")).first)
        assertEquals(404, http("HEAD", "$base/files/2026-10-26/SAM_0002.JPG", headers = auth).first)
        val putHeaders = auth + mapOf("Content-Type" to "image/jpeg", "X-NX-Camera" to "10.0.0.5", "X-NX-Class" to "object.item.imageItem")
        assertEquals(201, http("PUT", "$base/files/2026-10-26/SAM_0002.JPG", bytes, putHeaders).first)
        assertEquals(200, http("HEAD", "$base/files/2026-10-26/SAM_0002.JPG", headers = auth).first)
        assertEquals(200, http("PUT", "$base/files/2026-10-26/SAM_0002.JPG", bytes, putHeaders).first) // duplicate
        // unknown size -> chunked upload (the app does this when the camera sent no size)
        assertEquals(201, http("PUT", "$base/files/2026-10-26/SAM_0003.MP4", bytes, putHeaders + ("Content-Type" to "video/mp4"), chunked = true).first)
        assertEquals(204, http("POST", "$base/events", """{"type":"done","camera":"10.0.0.5","sysOff":true}""".toByteArray(), auth).first)
        s.pipeline.awaitIdle()

        assertArrayEquals(bytes, File(s.config.storageDir, "2026-10-26/SAM_0002.JPG").readBytes())
        assertArrayEquals(bytes, File(s.config.storageDir, "2026-10-26/SAM_0003.MP4").readBytes())
        assertEquals(2, s.ledger.size())
        assertEquals(400, http("PUT", "$base/files/../SAM_0002.JPG", bytes, putHeaders).first.let { if (it == 404) 400 else it })
        val (_, json) = http("GET", "$base/status")
        assertTrue(json, json.contains("\"filesStored\":2") && json.contains("\"filesSkipped\":1") && json.contains("\"lastCamera\":\"10.0.0.5\""))
    }

    @Test
    fun pipelineRenamesExportsAndCallsWebhook() {
        // tiny webhook receiver
        val received = CopyOnWriteArrayList<String>()
        val hook = HttpServer(0, { req -> received += req.readBodyText(); HttpResponse.empty(200) })
        hook.start()
        val export = File(dataDir, "export")
        val s = start(mapOf(
            "CAMBACKUP_RENAME_PATTERN" to "{yyyy}{MM}{dd}_{HH}{mm}{ss}_{base}.{ext}",
            "CAMBACKUP_EXPORT_DIR" to export.path,
            "CAMBACKUP_EXPORT_SUBFOLDER" to "{yyyy}/{yyyy}-{MM}-{dd}",
            "CAMBACKUP_WEBHOOK_URL" to "http://127.0.0.1:${hook.boundPort}/hook",
            "CAMBACKUP_WEBHOOK_TOKEN" to "hooktoken",
            "CAMBACKUP_EXIF_TOOL" to "definitely-not-installed-exiftool", // forces the no-exiftool fallback
        ))
        try {
            val bytes = ByteArray(100_000).also { Random(3).nextBytes(it) }
            val files = listOf(
                CameraSimulator.SimFile("SAM_0007.JPG", "2026-10-25", "image/jpeg", bytes),
                CameraSimulator.SimFile("SAM_0007.SRW", "2026-10-25", "image/x-samsung-srw", bytes),
            )
            CameraSimulator("127.0.0.1", s.port, log).runBackup(files, sysOff = true)
            s.pipeline.awaitIdle()
            val dir = File(export, "2026/2026-10-25")
            val jpg = File(dir, "20261025_000000_SAM_0007.JPG")
            val srw = File(dir, "20261025_000000_SAM_0007.SRW")
            assertTrue("exported files: ${dir.listFiles()?.map { it.name }}", jpg.isFile && srw.isFile)
            assertArrayEquals(bytes, jpg.readBytes())
            assertFalse(File(s.config.storageDir, "2026-10-25/SAM_0007.JPG").exists())
            // ledger follows the move and still deduplicates by the camera's name
            assertEquals(jpg.path, s.ledger.find("2026-10-25", "SAM_0007.JPG")!!.path)
            CameraSimulator("127.0.0.1", s.port, log).runBackup(files)
            s.pipeline.awaitIdle()
            assertEquals(2, s.backup.stats.filesSkipped.get())
            assertEquals(2, dir.listFiles()!!.size)
            // webhook got both sessions
            assertEquals(2, received.size)
            val first = received[0]
            assertTrue(first, first.contains("\"event\":\"done\"") && first.contains("\"sysOff\":true") && first.contains("\"stored\":2"))
            assertTrue(first, first.contains("20261025_000000_SAM_0007.SRW"))
            assertTrue(received[1].contains("\"skipped\":2"))
        } finally {
            hook.stop()
        }
    }

    @Test
    fun identityOverridesFromConfigRewritePairingFile() {
        val udn = "4a682b0b-0361-dbae-6155-00aabbccddee"
        val s = start(mapOf("CAMBACKUP_SERVER_UDN" to "uuid:$udn", "CAMBACKUP_SERVER_NAME" to "[PC]Shared"))
        assertEquals(udn, s.identity.udn)
        assertEquals("[PC]Shared", s.identity.friendlyName)
        val ini = File(dataDir, "SAMSUNGAutoBackupDESC.ini").readText()
        assertTrue(ini, ini.contains("UDN=uuid:$udn\r\n") && ini.contains("friendlyName=[PC]Shared\r\n"))
        assertNotNull(File(dataDir, Config.FILE_NAME).takeIf { it.isFile && it.readText().contains("exif.timeshift=") })
    }

    @Test
    fun ppmToJpegConversionWorksWithoutImageMagick() {
        val ppm = File(dataDir, "x.ppm")
        val w = 4; val h = 3
        val header = "P6\n# dcraw\n$w $h\n255\n".toByteArray()
        val px = ByteArray(w * h * 3) { i -> (i * 7).toByte() }
        ppm.writeBytes(header + px)
        val img: BufferedImage = Pipeline.readPpm(ppm)
        assertEquals(w, img.width); assertEquals(h, img.height)
        assertEquals(((0 and 0xff) shl 16) or ((7 and 0xff) shl 8) or (14 and 0xff), img.getRGB(0, 0) and 0xffffff)
        val out = File(dataDir, "x.jpg")
        Pipeline.writeJpeg(img, out, 0.9f)
        assertTrue(out.length() > 100)
        assertEquals(0xFF.toByte(), out.readBytes()[0]); assertEquals(0xD8.toByte(), out.readBytes()[1])
    }

    @Test
    fun jsonHelpers() {
        assertEquals("\"a\\\"b\\\\c\\n\"", Json.str("a\"b\\c\n"))
        val j = """{"type":"done","camera":"10.0.0.5","sysOff":true,"n":3}"""
        assertEquals("done", Json.field(j, "type"))
        assertEquals("10.0.0.5", Json.field(j, "camera"))
        assertEquals("true", Json.field(j, "sysOff"))
        assertEquals("3", Json.field(j, "n"))
        assertEquals(null, Json.field(j, "missing"))
    }
}
