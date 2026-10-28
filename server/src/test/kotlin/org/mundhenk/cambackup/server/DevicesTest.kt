package org.mundhenk.cambackup.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mundhenk.cambackup.core.BackendSink
import org.mundhenk.cambackup.core.CameraSimulator
import org.mundhenk.cambackup.server.TestSupport.http
import java.io.ByteArrayInputStream
import java.io.File

/** Identity endpoint and the device registry (cameras by address, phones by nickname). */
class DevicesTest {
    private lateinit var dataDir: File
    private val servers = ArrayList<CamBackupServer>()
    private val log = ArrayList<String>()

    @Before fun setUp() { dataDir = TestSupport.tempDir("cambackup-devices") }
    @After fun tearDown() { servers.forEach { it.stop() }; dataDir.deleteRecursively() }

    private fun start(env: Map<String, String> = emptyMap()): CamBackupServer =
        CamBackupServer.start(dataDir, env = mapOf("CAMBACKUP_SSDP_ENABLED" to "false") + env, httpPortOverride = 0, log = { log += it }).also { servers += it }

    @Test
    fun identityEndpointAndDeviceList() {
        val s = start(mapOf("CAMBACKUP_SERVER_NAME" to "[PC]Home", "CAMBACKUP_API_TOKEN" to "tok", "CAMBACKUP_EXIF_TOOL" to "no-exiftool-here"))
        val base = "http://127.0.0.1:${s.port}"
        // identity is public (the device description already is) and what the app imports
        val (code, json) = http("GET", "$base/api/v1/identity")
        assertEquals(200, code)
        assertEquals("""{"udn":"${s.identity.udn}","name":"[PC]Home","mac":"${s.identity.macAddr}"}""", json)
        val imported = BackendSink(base, { log += it }, "tok", deviceName = "Pixel 7").fetchIdentity()
        assertEquals(s.identity, imported)

        // nothing seen yet -> hint on the status page
        assertTrue(http("GET", "$base/").second.contains("No camera or phone has talked to this server yet"))

        // a camera session and a phone (with nickname) are recorded
        CameraSimulator("127.0.0.1", s.port) { log += it }.runBackup(listOf(CameraSimulator.SimFile("A.JPG", "2026-10-28", "image/jpeg", ByteArray(10))))
        val phone = BackendSink(base, { log += it }, "tok", deviceName = "Pixel 7")
        phone.onBackupStart("192.168.1.50")
        phone.store(org.mundhenk.cambackup.core.IncomingFile("B.JPG", "2026-10-28", 3, "image/jpeg", "object.item.imageItem", "192.168.1.50"), ByteArrayInputStream("abc".toByteArray()))
        phone.onBackupDone("192.168.1.50", false)
        s.pipeline.awaitIdle()
        val devices = s.devices.all()
        assertEquals(setOf("CAMERA/127.0.0.1", "PHONE/pixel 7", "CAMERA/192.168.1.50"), devices.map { it.key }.toSet())
        val cam = devices.first { it.id == "127.0.0.1" && it.kind == Devices.Kind.CAMERA }
        assertEquals(1, cam.sessions); assertEquals(1, cam.files)
        val px = devices.first { it.kind == Devices.Kind.PHONE }
        assertEquals("Pixel 7", px.id); assertEquals(1, px.sessions); assertEquals(1, px.files); assertTrue(px.detail.startsWith("Java/"))
        val page = http("GET", "$base/").second
        assertTrue(page, page.contains("<h2>Devices</h2>") && page.contains("Pixel 7") && page.contains("Phone &middot; 1 session &middot; 1 file"))
        assertTrue(page.contains("Camera &middot; 1 session &middot; 1 file"))

        // persisted across restarts, counters keep growing, last line wins
        s.stop(); servers.remove(s)
        val s2 = start(mapOf("CAMBACKUP_SERVER_NAME" to "[PC]Home", "CAMBACKUP_API_TOKEN" to "tok"))
        assertEquals(3, s2.devices.all().size)
        s2.devices.seen(Devices.Kind.PHONE, "pixel 7", "10.0.0.9", "Dalvik", session = true, files = 2)
        val again = s2.devices.all().first { it.kind == Devices.Kind.PHONE }
        assertEquals(2, again.sessions); assertEquals(3, again.files); assertEquals("Dalvik", again.detail); assertEquals("pixel 7", again.id)
        s2.devices.seen(Devices.Kind.PHONE, "", "x")   // ignored
        assertEquals(3, s2.devices.all().size)
        // garbage lines in the file are skipped
        File(dataDir, "devices.tsv").appendText("broken\tline\n")
        assertEquals(3, Devices(File(dataDir, "devices.tsv")).all().size)
    }

    /** Phone first, server later: the phone hands its identity to the server, which adopts it live. */
    @Test
    fun phonePushesItsIdentityToTheServer() {
        val s = start(mapOf("CAMBACKUP_API_TOKEN" to "tok", "CAMBACKUP_EXIF_TOOL" to "no-exiftool-here"))
        val base = "http://127.0.0.1:${s.port}"
        val before = s.identity
        val mine = org.mundhenk.cambackup.core.ServerIdentity("4a682b0b-0361-dbae-6155-00000000cafe", "[PC]Pixel", org.mundhenk.cambackup.core.ServerIdentity.DEFAULT_MAC)
        // no token -> refused, nothing changes
        assertEquals(null, BackendSink(base, { log += it }, "", deviceName = "Pixel 7").pushIdentity(mine))
        assertEquals(before, s.identity)
        // invalid identities are refused
        assertEquals(400, http("POST", "$base/api/v1/identity", """{"udn":"nope"}""".toByteArray(), mapOf("Authorization" to "Bearer tok")).first)
        assertEquals(400, http("POST", "$base/api/v1/identity", """{"name":"x"}""".toByteArray(), mapOf("Authorization" to "Bearer tok")).first)
        // the real thing
        val adopted = BackendSink(base, { log += it }, "tok", deviceName = "Pixel 7").pushIdentity(mine)
        assertEquals(mine, adopted)
        assertEquals(mine, s.identity)
        assertEquals(s.port, s.backup.boundPort)                                                       // same port after the restart
        assertTrue(http("GET", "$base/api/v1/identity").second.contains(mine.udn))
        assertTrue(http("GET", "$base${org.mundhenk.cambackup.core.UpnpXml.DEVICE_DESC_PATH}").second.contains("<UDN>uuid:${mine.udn}</UDN>"))
        assertTrue(File(dataDir, "SAMSUNGAutoBackupDESC.ini").readText().contains("UDN=uuid:${mine.udn}\r\nfriendlyName=[PC]Pixel"))
        assertTrue(s.config.file.readText().contains("server.udn=${mine.udn}") && s.config.file.readText().contains("server.name=[PC]Pixel"))
        assertTrue(log.any { it.startsWith("Identity adopted from Pixel 7: [PC]Pixel uuid:${mine.udn}") })
        // the camera talks to the new identity right away, counters survived the restart
        val r = CameraSimulator("127.0.0.1", s.port) { log += it }.runBackup(listOf(CameraSimulator.SimFile("Z.JPG", "2026-10-28", "image/jpeg", ByteArray(4))))
        assertEquals(mine.udn, r.udn); assertEquals("[PC]Pixel", r.friendlyName)
        assertEquals(1, s.backup.stats.filesStored.get())
        assertTrue(http("GET", "$base/api/v1/status").second.contains("\"filesStored\":1"))                 // status follows the restarted server
        // pushing the same identity again is a no-op; a restart keeps it
        assertEquals(mine, BackendSink(base, { log += it }, "tok").pushIdentity(mine))
        assertTrue(log.any { it.contains("already in use") })
        s.stop(); servers.remove(s)
        val s2 = start(mapOf("CAMBACKUP_API_TOKEN" to "tok"))
        assertEquals(mine, s2.identity)
        // the environment still wins: with CAMBACKUP_SERVER_UDN set, a push changes the file but not the identity
        s2.stop(); servers.remove(s2)
        val s3 = start(mapOf("CAMBACKUP_API_TOKEN" to "tok", "CAMBACKUP_SERVER_UDN" to before.udn))
        val other = org.mundhenk.cambackup.core.ServerIdentity("4a682b0b-0361-dbae-6155-00000000d00d", "[PC]Other", org.mundhenk.cambackup.core.ServerIdentity.DEFAULT_MAC)
        val result = BackendSink("http://127.0.0.1:${s3.port}", { log += it }, "tok").pushIdentity(other)
        assertEquals(before.udn, result!!.udn)
        assertEquals("[PC]Other", result.friendlyName)                                                    // the name was not overridden by the environment
    }
}
