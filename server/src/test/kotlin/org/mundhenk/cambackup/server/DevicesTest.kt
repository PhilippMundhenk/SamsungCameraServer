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
    private val log = java.util.concurrent.CopyOnWriteArrayList<String>()   // written by server threads too

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
        assertTrue(page, page.contains("Devices</h2>") && page.contains("Pixel 7") && page.contains("Phone &middot; 1 session &middot; 1 file"))
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

    /** Cameras (by address) and phones can be given names on the status page; the name follows them into log and JSON. */
    @Test
    fun camerasAndPhonesCanBeNamed() {
        val s = start(mapOf("CAMBACKUP_API_TOKEN" to "tok", "CAMBACKUP_EXIF_TOOL" to "no-exiftool-here"))
        val base = "http://127.0.0.1:${s.port}"
        val basic = mapOf("Authorization" to "Basic " + java.util.Base64.getEncoder().encodeToString("x:tok".toByteArray()), "Content-Type" to "application/x-www-form-urlencoded")
        CameraSimulator("127.0.0.1", s.port) { log += it }.runBackup(listOf(CameraSimulator.SimFile("A.JPG", "2026-10-28", "image/jpeg", ByteArray(10))))
        s.pipeline.awaitIdle()
        // the form is on the status page, the row shows the address until a name is given
        var page = http("GET", "$base/").second
        assertTrue(page, page.contains("action=\"/devices/rename\"") && page.contains("name=\"id\" value=\"127.0.0.1\"") && page.contains("Name this camera"))
        assertTrue(page, page.contains("<p>Last: <code>A.JPG</code> from 127.0.0.1</p>"))
        // protected like the settings page
        assertEquals(401, http("POST", "$base/devices/rename", "kind=CAMERA&id=127.0.0.1&nickname=NX1000".toByteArray(), mapOf("Content-Type" to "application/x-www-form-urlencoded")).first)
        // bad input
        assertEquals(400, http("POST", "$base/devices/rename", "kind=TOASTER&id=1&nickname=x".toByteArray(), basic).first)
        assertEquals(400, http("POST", "$base/devices/rename", "kind=CAMERA&nickname=x".toByteArray(), basic).first)
        assertEquals(404, http("POST", "$base/devices/rename", "kind=CAMERA&id=10.9.9.9&nickname=x".toByteArray(), basic).first)
        // the real thing: trimmed, control characters dropped, capped at 60 chars
        val (code, _) = http("POST", "$base/devices/rename", "kind=CAMERA&id=127.0.0.1&nickname=%20NX1000%09living%20room%20".toByteArray(), basic, followRedirects = false)
        assertEquals(303, code)
        val cam = s.devices.find(Devices.Kind.CAMERA, "127.0.0.1")!!
        assertEquals("NX1000 living room", cam.nickname); assertEquals("NX1000 living room", cam.displayName)
        assertEquals("NX1000 living room", s.devices.displayName(Devices.Kind.CAMERA, "127.0.0.1"))
        assertEquals("10.9.9.9", s.devices.displayName(Devices.Kind.CAMERA, "10.9.9.9"))
        assertTrue(log.any { it == "Camera 127.0.0.1 named \"NX1000 living room\" from 127.0.0.1" })
        page = http("GET", "$base/").second
        assertTrue(page, page.contains("<div class=\"name\">NX1000 living room <span class=\"help\">127.0.0.1</span></div>") && page.contains("value=\"NX1000 living room\"") && page.contains(">Rename</button>"))
        assertTrue(page, page.contains("<p>Last: <code>A.JPG</code> from NX1000 living room</p>"))
        assertTrue(http("GET", "$base/api/v1/status").second.contains("\"devices\":[{\"kind\":\"camera\",\"id\":\"127.0.0.1\",\"name\":\"NX1000 living room\",\"sessions\":1,\"files\":1,"))
        // the next session is logged under the name; the name survives new activity and a restart
        CameraSimulator("127.0.0.1", s.port) { log += it }.runBackup(listOf(CameraSimulator.SimFile("B.JPG", "2026-10-28", "image/jpeg", ByteArray(10))))
        s.pipeline.awaitIdle()
        assertTrue(log.joinToString("\n"), log.any { it.startsWith("Session from NX1000 living room (127.0.0.1): 1 stored") })
        assertEquals(2, s.devices.find(Devices.Kind.CAMERA, "127.0.0.1")!!.sessions)
        assertEquals("NX1000 living room", Devices(File(dataDir, "devices.tsv")).find(Devices.Kind.CAMERA, "127.0.0.1")!!.nickname)
        // phones too; a long name is cut; an empty name removes it
        s.devices.seen(Devices.Kind.PHONE, "Pixel 7", "10.0.0.9", "Dalvik", session = true)
        assertEquals("x".repeat(60), s.devices.rename(Devices.Kind.PHONE, "pixel 7", "x".repeat(70))!!.nickname)
        assertEquals(303, http("POST", "$base/devices/rename", "kind=PHONE&id=Pixel+7&nickname=".toByteArray(), basic, followRedirects = false).first)
        assertEquals("", s.devices.find(Devices.Kind.PHONE, "Pixel 7")!!.nickname)
        assertTrue(log.any { it == "Phone Pixel 7 name removed from 127.0.0.1" })
        // a name an operator gives is kept when the device is seen again
        s.devices.rename(Devices.Kind.PHONE, "Pixel 7", "Philipp's phone")
        s.devices.seen(Devices.Kind.PHONE, "Pixel 7", "10.0.0.10", "Dalvik", files = 1)
        assertEquals("Philipp's phone", s.devices.find(Devices.Kind.PHONE, "Pixel 7")!!.nickname)
        assertEquals(null, s.devices.rename(Devices.Kind.PHONE, "nobody", "x"))
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
        org.junit.Assert.assertNotNull(log.joinToString("\n"), result)
        assertEquals(before.udn, result!!.udn)
        assertEquals("[PC]Other", result.friendlyName)                                                    // the name was not overridden by the environment
    }
}
