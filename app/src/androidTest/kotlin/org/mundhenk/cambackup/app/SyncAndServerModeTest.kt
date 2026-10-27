package org.mundhenk.cambackup.app

import android.Manifest
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withSubstring
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mundhenk.cambackup.app.TestSupport.context
import org.mundhenk.cambackup.app.TestSupport.tap
import org.mundhenk.cambackup.app.TestSupport.waitFor
import org.mundhenk.cambackup.app.TestSupport.waitForUi
import org.mundhenk.cambackup.core.BackupServer
import org.mundhenk.cambackup.core.CameraSimulator
import java.io.File
import java.util.Random

/** Phone + server (store and forward, ✓ -> ✓✓) and server-only (forward, ✓✓) against a fake home server. */
@RunWith(AndroidJUnit4::class)
class SyncAndServerModeTest {
    @get:Rule
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33)
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    private lateinit var backend: TestSupport.FakeBackend
    private val log: (String) -> Unit = { android.util.Log.i("UITest", it) }

    @Before
    fun setUp() {
        backend = TestSupport.FakeBackend("tok").start()
        TestSupport.reset(Mode.PHONE_AND_SERVER)
        Prefs(context).apply { backendUrl = backend.url; backendToken = "tok" }
    }

    @After
    fun tearDown() { TestSupport.reset(Mode.PHONE); backend.stop() }

    private fun camera() = CameraSimulator("127.0.0.1", BackupServer.DEFAULT_HTTP_PORT, log)
    private fun file(name: String, seed: Int) = CameraSimulator.SimFile(name, "2026-10-27", Uploader.mimeFor(name), ByteArray(200_000).also { Random(seed.toLong()).nextBytes(it) })

    @Test
    fun phoneAndServer_uploadsAfterSessionAndOnDemand() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.destination)).check(matches(withSubstring("Phone + server")))
            onView(withId(R.id.syncNow)).check(matches(isDisplayed()))
            onView(withId(R.id.startStop)).perform(tap())
            waitFor { BackupService.running }

            // session 1: stored on the phone (✓), then uploaded automatically (✓✓)
            val a = file("SAM_0001.JPG", 1)
            camera().runBackup(listOf(a))
            waitFor { Transfers.get("2026-10-27", "SAM_0001.JPG")?.state == TransferState.ON_SERVER }
            assertArrayEquals(a.bytes, backend.files["2026-10-27/SAM_0001.JPG"])
            assertTrue(File(Sinks.defaultFolder(context), "2026-10-27/SAM_0001.JPG").isFile) // still on the phone
            assertTrue(backend.events.any { it.contains("\"type\":\"start\"") && it.contains("\"camera\":\"phone\"") })
            waitForUi { onView(withText("SAM_0001.JPG")).check(matches(isDisplayed())) }
            waitForUi { onView(withId(R.id.syncNow)).check(matches(withText(R.string.sync_now))) }

            // session 2 while the server is down: stays ✓, button shows how many are waiting
            backend.healthy = false
            camera().runBackup(listOf(file("SAM_0002.SRW", 2)))
            waitFor { LogBus.snapshot().any { it.contains("not reachable, 1 file(s) waiting") } }
            assertEquals(TransferState.ON_PHONE, Transfers.get("2026-10-27", "SAM_0002.SRW")!!.state)
            waitForUi { onView(withId(R.id.syncNow)).check(matches(withSubstring("(1 waiting)"))) }

            // server back: Sync now uploads it
            backend.healthy = true
            onView(withId(R.id.syncNow)).perform(tap())
            waitFor { Transfers.get("2026-10-27", "SAM_0002.SRW")?.state == TransferState.ON_SERVER }
            assertTrue(backend.files.containsKey("2026-10-27/SAM_0002.SRW"))
            waitForUi { onView(withId(R.id.syncNow)).check(matches(withText(R.string.sync_now))) }
            assertTrue(LogBus.snapshot().any { it.contains("Sync: 1 uploaded, 0 failed, 0 missing") })
            assertTrue(LogBus.snapshot().any { it.contains("Sync: everything is on the server") })

            onView(withId(R.id.startStop)).perform(tap())
            waitFor { !BackupService.running }
        }
    }

    @Test
    fun uploaderEdgeCases() {
        val prefs = Prefs(context)
        Transfers.record("2026-10-27", "GONE.JPG", 10, TransferState.ON_PHONE, "x")
        assertTrue(Uploader.sync(context).contains("0 uploaded, 0 failed, 1 missing"))
        // wrong token -> 401 -> failed, loop stops
        File(Sinks.defaultFolder(context), "2026-10-27").mkdirs()
        File(Sinks.defaultFolder(context), "2026-10-27/A.JPG").writeBytes(ByteArray(10))
        Transfers.record("2026-10-27", "A.JPG", 10, TransferState.ON_PHONE, "x")
        prefs.backendToken = "wrong"
        assertTrue(Uploader.sync(context).contains("0 uploaded, 1 failed"))
        assertEquals(TransferState.ON_PHONE, Transfers.get("2026-10-27", "A.JPG")!!.state)
        prefs.backendToken = "tok"
        assertTrue(Uploader.sync(context).contains("1 uploaded, 0 failed, 1 missing"))
        assertEquals(TransferState.ON_SERVER, Transfers.get("2026-10-27", "A.JPG")!!.state)
        // nothing left / no url / wrong mode
        Transfers.clear()
        assertEquals("Sync: everything is on the server", Uploader.sync(context))
        Transfers.record("2026-10-27", "B.JPG", 1, TransferState.ON_PHONE, "x")
        prefs.backendUrl = ""
        assertTrue(Uploader.sync(context).startsWith("Sync: no server URL configured (1 waiting)"))
        prefs.mode = Mode.PHONE
        assertTrue(Uploader.sync(context).contains("has no server"))
        // background run: second request while running is refused
        prefs.mode = Mode.PHONE_AND_SERVER; prefs.backendUrl = backend.url
        var finished = false
        assertTrue(Uploader.syncInBackground(context) { finished = true })
        waitFor { finished }
        assertFalse(Uploader.running)
        assertTrue(Uploader.lastResult.startsWith("Sync:"))
        assertEquals("image/jpeg", Uploader.mimeFor("x.JPEG")); assertEquals("video/quicktime", Uploader.mimeFor("x.mov"))
        assertEquals("video/x-msvideo", Uploader.mimeFor("x.avi")); assertEquals("application/octet-stream", Uploader.mimeFor("x"))
    }

    @Test
    fun serverOnly_forwardsEverythingAndShowsDoubleChecks() {
        Prefs(context).mode = Mode.SERVER
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.destination)).check(matches(withSubstring("Server only")))
            onView(withId(R.id.syncNow)).check(matches(org.hamcrest.Matchers.not(isDisplayed())))
            onView(withId(R.id.startStop)).perform(tap())
            waitFor { BackupService.running }
            val a = file("SAM_0010.JPG", 10); val b = file("SAM_0011.MP4", 11)
            val r = camera().runBackup(listOf(a, b), sysOff = true)
            assertEquals(listOf("SAM_0010.JPG", "SAM_0011.MP4"), r.uploaded)
            assertArrayEquals(a.bytes, backend.files["2026-10-27/SAM_0010.JPG"])
            assertArrayEquals(b.bytes, backend.files["2026-10-27/SAM_0011.MP4"])
            assertTrue(backend.events.last().contains("\"sysOff\":true"))
            waitFor { Transfers.list().size == 2 }
            assertTrue(Transfers.list().all { it.state == TransferState.ON_SERVER && it.location == backend.url })
            assertFalse(File(Sinks.defaultFolder(context), "2026-10-27").exists())
            waitForUi { onView(withText("SAM_0011.MP4")).check(matches(isDisplayed())) }
            // duplicates: HEAD says exists -> discarded
            camera().runBackup(listOf(a))
            waitFor { LogBus.snapshot().any { it.contains("Discarded duplicate SAM_0010.JPG") } }
            onView(withId(R.id.startStop)).perform(tap())
            waitFor { !BackupService.running }
        }
    }
}
