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

/** Multi-user (#20): identities, ports, folders, pairing files, overrides and the settings UI for users. */
class UsersTest {
    private lateinit var dataDir: File
    private val servers = ArrayList<CamBackupServer>()
    private val log = java.util.concurrent.CopyOnWriteArrayList<String>()

    @Before fun setUp() { dataDir = TestSupport.tempDir("cambackup-users") }
    @After fun tearDown() { servers.forEach { it.stop() }; dataDir.deleteRecursively() }

    private fun start(env: Map<String, String> = emptyMap()): CamBackupServer =
        CamBackupServer.start(dataDir, env = mapOf("CAMBACKUP_SSDP_ENABLED" to "false", "CAMBACKUP_EXIF_TOOL" to "no-exiftool-here") + env, httpPortOverride = 0, log = { log += it }).also { servers += it }

    private fun form(vararg pairs: Pair<String, String>) =
        pairs.joinToString("&") { (k, v) -> java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8") }.toByteArray()

    @Test
    fun usersGetTheirOwnIdentityPortFolderAndPairingFile() {
        File(dataDir, Config.FILE_NAME).writeText("users=alice, bob,,alice\nuser.alice.name=[PC]Alice\nuser.alice.udn=4a682b0b-0361-dbae-6155-00000000a11c\nexport.dir=export\n")
        val s = start(mapOf("CAMBACKUP_API_TOKEN" to "tok"))
        val base = "http://127.0.0.1:${s.port}"
        // config: alice has her udn, bob's is generated and written back; ports count up from http.port
        val defs = s.config.users()
        assertEquals(listOf("alice", "bob"), defs.map { it.name })
        assertEquals("4a682b0b-0361-dbae-6155-00000000a11c", defs[0].udn); assertEquals("[PC]Alice", defs[0].friendlyName); assertEquals(s.config.httpPort + 1, defs[0].port)
        assertTrue(defs[1].udn.startsWith("4a682b0b-0361-dbae-6155-")); assertEquals("[PC]Bob", defs[1].friendlyName); assertEquals(s.config.httpPort + 2, defs[1].port)
        assertTrue(s.config.file.readText().contains("user.bob.udn=${defs[1].udn}"))
        // two user servers run, each with its own pairing file, ledger and folder
        assertEquals(listOf("alice", "bob"), s.users.all().map { it.name })
        val alice = s.users.get("alice")!!
        assertTrue(File(dataDir, "users/alice/SAMSUNGAutoBackupDESC.ini").readText().contains("UDN=uuid:${defs[0].udn}"))
        assertTrue(log.any { it.startsWith("User alice: '[PC]Alice' uuid:${defs[0].udn} on port ${alice.port}") })
        // the API lists them, hands out their identities and pairing files
        val users = http("GET", "$base/api/v1/users").second
        assertTrue(users, users.contains("\"name\":\"alice\"") && users.contains("\"udn\":\"${defs[0].udn}\"") && users.contains("\"port\":${alice.port}"))
        assertTrue(http("GET", "$base/api/v1/identity?user=alice").second.contains("\"udn\":\"${defs[0].udn}\",\"name\":\"[PC]Alice\""))
        assertEquals(404, http("GET", "$base/api/v1/identity?user=nobody").first)
        assertTrue(http("GET", "$base/SAMSUNGAutoBackupDESC.ini?user=bob").second.contains("UDN=uuid:${defs[1].udn}"))
        assertEquals(200, http("GET", "$base/pairing.zip?user=bob").first); assertEquals(404, http("GET", "$base/pairing.zip?user=nobody").first)
        // alice's camera talks to alice's port: her description, her folder, her ledger, export under her name
        val r = CameraSimulator("127.0.0.1", alice.port) { log += it }.runBackup(listOf(CameraSimulator.SimFile("A.JPG", "2026-10-29", "image/jpeg", ByteArray(9))))
        assertEquals(defs[0].udn, r.udn); assertEquals("[PC]Alice", r.friendlyName)
        s.pipeline.awaitIdle()
        assertTrue(log.joinToString("\n") + "\nfiles: " + dataDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dataDir).path }.joinToString(", "), File(dataDir, "export/alice/2026-10-29/A.JPG").isFile)   // export.dir + user
        assertTrue(alice.ledger.find("2026-10-29", "A.JPG") != null); assertNull(s.ledger.find("2026-10-29", "A.JPG"))
        assertEquals(1, alice.backup.stats.filesStored.get()); assertEquals(0, s.backup.stats.filesStored.get())
        assertTrue(s.devices.all().any { it.kind == Devices.Kind.CAMERA && it.detail == "user alice" })
        // the status page has a Users section with totals and links
        val status = http("GET", "$base/").second
        assertTrue(status, status.contains("<h2>Users</h2>") && status.contains("[PC]Alice") && status.contains("href=\"/pairing.zip?user=alice\"") && status.contains("1 file"))
        // per-user overrides: bob renames, alice does not; bob's own export dir
        val bobExport = File(dataDir, "bob-export")
        s.config.save(mapOf("user.bob.rename.pattern" to "{base}_bob.{ext}", "user.bob.export.dir" to bobExport.path, "user.bob.storage.subfolder" to "year"))
        val bob = s.users.get("bob")!!
        CameraSimulator("127.0.0.1", bob.port) { log += it }.runBackup(listOf(CameraSimulator.SimFile("B.JPG", "2026-10-29", "image/jpeg", ByteArray(9))))
        s.pipeline.awaitIdle()
        assertTrue(log.joinToString("\n"), File(bobExport, "2026-10-29/B_bob.JPG").isFile)                 // own export dir, own pattern, no user subfolder
        assertEquals(mapOf("export.dir" to bobExport.path, "rename.pattern" to "{base}_bob.{ext}", "storage.subfolder" to "year"), s.config.userOverrides("bob"))
        assertEquals("{base}_bob.{ext}", s.config.forCameraAndUser("127.0.0.1", "bob").renamePattern); assertNull(s.config.forUser("alice").renamePattern)
        // a camera override beats the user's
        s.config.save(mapOf("camera.127_0_0_1.rename.pattern" to "{base}_cam.{ext}"))
        assertEquals("{base}_cam.{ext}", s.config.forCameraAndUser("127.0.0.1", "bob").renamePattern)
        // the phone files uploads for a user with X-NX-User; an unknown user is refused
        val auth = mapOf("Authorization" to "Bearer tok", "X-NX-User" to "alice", "X-NX-Device" to "Pixel 7")
        assertEquals(201, http("PUT", "$base/api/v1/files/2026-10-29/P.JPG", ByteArray(4), auth).first)
        assertEquals(200, http("HEAD", "$base/api/v1/files/2026-10-29/P.JPG", headers = auth).first)
        assertEquals(404, http("HEAD", "$base/api/v1/files/2026-10-29/P.JPG", headers = mapOf("Authorization" to "Bearer tok")).first)   // not in the general ledger
        assertEquals(204, http("POST", "$base/api/v1/events", """{"type":"start"}""".toByteArray(), auth).first)
        assertEquals(204, http("POST", "$base/api/v1/events", """{"type":"done"}""".toByteArray(), auth).first)
        assertEquals(400, http("PUT", "$base/api/v1/files/2026-10-29/Q.JPG", ByteArray(4), auth + ("X-NX-User" to "nobody")).first)
        s.pipeline.awaitIdle()
        assertTrue(File(dataDir, "export/alice/2026-10-29/P_cam.JPG").isFile)       // alice's folder, the camera override (127.0.0.1) renamed it
    }

    @Test
    fun usersAreAddedAndRemovedOnTheSettingsPage() {
        val s = start(mapOf("CAMBACKUP_API_TOKEN" to "tok"))
        val base = "http://127.0.0.1:${s.port}"
        val basic = mapOf("Authorization" to "Basic " + java.util.Base64.getEncoder().encodeToString("x:tok".toByteArray()), "Content-Type" to "application/x-www-form-urlencoded")
        assertEquals(0, s.users.all().size)
        var page = http("GET", "$base/settings", headers = basic).second
        assertTrue(page, page.contains("Users</h2>") && page.contains("action=\"/settings/users\"") && page.contains("No users yet"))
        // add: name is validated, the shown name defaults to [PC]<Name>
        assertEquals(401, http("POST", "$base/settings/users", form("name" to "carol"), mapOf("Content-Type" to "application/x-www-form-urlencoded")).first)
        val (badCode, badPage) = http("POST", "$base/settings/users", form("name" to "not valid!"), basic, followRedirects = false)
        assertEquals(200, badCode); assertTrue(badPage.contains("letters, digits"))
        assertEquals(303, http("POST", "$base/settings/users", form("name" to "carol", "shown" to ""), basic, followRedirects = false).first)
        assertEquals(303, http("POST", "$base/settings/users", form("name" to "dave", "shown" to "[PC]Dave's"), basic, followRedirects = false).first)
        assertEquals(303, http("POST", "$base/settings/users", form("name" to "carol"), basic, followRedirects = false).first)   // twice: no duplicate
        assertEquals(listOf("carol", "dave"), s.users.all().map { it.name })
        assertEquals("[PC]Carol", s.users.get("carol")!!.identity.friendlyName); assertEquals("[PC]Dave's", s.users.get("dave")!!.identity.friendlyName)
        assertTrue(s.config.file.readText().contains("users=carol,dave"))
        page = http("GET", "$base/settings", headers = basic).second
        assertTrue(page, page.contains("<code>carol</code>") && page.contains("name=\"user.carol.exif.timeshift\"") && page.contains("action=\"/settings/users/remove\""))
        // the user's camera works right away (no restart), with the default flat layout under her folder
        CameraSimulator("127.0.0.1", s.users.get("carol")!!.port) { log += it }.runBackup(listOf(CameraSimulator.SimFile("C.JPG", "2026-10-29", "image/jpeg", ByteArray(3))))
        s.pipeline.awaitIdle()
        assertTrue(File(s.config.storageDir, "carol/C.JPG").isFile)
        // per-user override through the form; remove dave: his server stops, his files stay
        assertEquals(303, http("POST", "$base/settings", form("user.carol.exif.timeshift" to "+2:00", "user.dave.raw.convert" to "true"), basic, followRedirects = false).first)
        assertEquals("+2:00", s.config.forUser("carol").exifTimeShift)
        val davePort = s.users.get("dave")!!.port
        assertEquals(303, http("POST", "$base/settings/users/remove", form("name" to "dave"), basic, followRedirects = false).first)
        assertEquals(listOf("carol"), s.users.all().map { it.name })
        assertFalse(s.config.file.readText().contains("user.dave.udn=4a68")); assertFalse(s.config.file.readText().contains("raw.convert=true"))
        assertTrue(log.any { it.startsWith("User dave removed") })
        Thread.sleep(100)
        assertEquals(-1, try { java.net.Socket("127.0.0.1", davePort).use { 1 } } catch (e: java.io.IOException) { -1 })
        assertEquals(303, http("POST", "$base/settings/users/remove", form("name" to "nobody"), basic, followRedirects = false).first)
        // an invalid stored identity is skipped with a log line, not a crash
        s.config.save(mapOf("users" to "carol,erin", "user.erin.udn" to "nope"))
        s.users.sync()
        assertEquals(listOf("carol"), s.users.all().map { it.name })
        assertTrue(log.any { it.startsWith("User erin: invalid identity") })
    }
}
