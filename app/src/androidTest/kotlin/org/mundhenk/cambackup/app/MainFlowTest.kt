package org.mundhenk.cambackup.app

import android.Manifest
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
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
import java.net.ServerSocket
import java.util.Random

/**
 * UI end-to-end in phone-only mode: start the foreground service from the main screen, act as the
 * camera against it, and verify files, the ✓ list and the log screen.
 */
@RunWith(AndroidJUnit4::class)
class MainFlowTest {
    @get:Rule
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33)
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    private lateinit var folder: File

    @Before
    fun setUp() {
        TestSupport.reset(Mode.PHONE)
        folder = Sinks.defaultFolder(context)
    }

    @After
    fun tearDown() { TestSupport.reset(Mode.PHONE) }

    @Test
    fun startServer_receiveBackupFromSimulatedCamera_listShowsChecks_stop() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.status)).check(matches(withText(R.string.status_stopped)))
            onView(withId(R.id.empty)).check(matches(isDisplayed()))
            onView(withId(R.id.destination)).check(matches(withSubstring("Phone only")))
            onView(withId(R.id.startStop)).perform(tap())
            waitFor { BackupService.running }
            waitForUi { onView(withId(R.id.startStop)).check(matches(withText(R.string.stop))) }
            onView(withId(R.id.status)).check(matches(withText(R.string.status_running)))

            val rnd = Random(7)
            val jpg = ByteArray(2_000_000).also { rnd.nextBytes(it) }
            val srw = ByteArray(1_000_000).also { rnd.nextBytes(it) }
            val sim = CameraSimulator("127.0.0.1", BackupServer.DEFAULT_HTTP_PORT) { android.util.Log.i("UITest", it) }
            val result = sim.runBackup(listOf(
                CameraSimulator.SimFile("SAM_0042.JPG", "2026-10-26", "image/jpeg", jpg),
                CameraSimulator.SimFile("SAM_0042.SRW", "2026-10-26", "image/x-samsung-srw", srw),
            ))
            assertEquals("[PC]UITest", result.friendlyName)
            assertEquals(Prefs(context).udn, result.udn)
            assertEquals(listOf("SAM_0042.JPG", "SAM_0042.SRW"), result.uploaded)

            val stored = File(File(folder, "2026-10-26"), "SAM_0042.JPG")
            waitFor { stored.isFile && stored.length() == jpg.size.toLong() }
            assertArrayEquals(jpg, stored.readBytes())
            assertArrayEquals(srw, File(File(folder, "2026-10-26"), "SAM_0042.SRW").readBytes())

            // the list shows both files with a single check (on phone)
            waitFor { Transfers.list().size == 2 }
            assertTrue(Transfers.list().all { t -> t.state == TransferState.ON_PHONE })
            waitForUi { onView(withText("SAM_0042.SRW")).check(matches(isDisplayed())) }
            onView(withId(R.id.transfersTitle)).check(matches(withSubstring("(2)")))
            onView(withId(R.id.empty)).check(matches(org.hamcrest.Matchers.not(isDisplayed())))

            // second run is deduplicated
            sim.runBackup(listOf(CameraSimulator.SimFile("SAM_0042.JPG", "2026-10-26", "image/jpeg", jpg)))
            waitFor { LogBus.snapshot().any { l -> l.contains("Discarded duplicate SAM_0042.JPG") } }
            assertEquals(2, Transfers.list().size)

            // log screen shows what happened
            onView(withId(R.id.action_log)).perform(tap())
            waitForUi { onView(withId(R.id.log)).check(matches(withSubstring("Stored SAM_0042.JPG"))) }
            onView(withId(R.id.action_clear_log)).perform(tap())
            onView(withId(R.id.log)).check(matches(withText("")))
            assertTrue(LogBus.snapshot().isEmpty())
            androidx.test.espresso.Espresso.pressBack()

            onView(withId(R.id.startStop)).perform(tap())
            waitFor { !BackupService.running }
            waitForUi { onView(withId(R.id.startStop)).check(matches(withText(R.string.start))) }

            // clear the list from the overflow menu
            openActionBarOverflowOrOptionsMenu(context)
            onView(withText(R.string.clear_transfers)).perform(click())
            waitForUi { onView(withId(R.id.empty)).check(matches(isDisplayed())) }
            assertTrue(Transfers.list().isEmpty())
        }
    }

    @Test
    fun startFailsWhenThePortIsTaken() {
        ServerSocket(BackupServer.DEFAULT_HTTP_PORT).use {
            ActivityScenario.launch(MainActivity::class.java).use {
                onView(withId(R.id.startStop)).perform(tap())
                waitFor { LogBus.snapshot().any { l -> l.contains("Start failed") } }
                Thread.sleep(500)
                assertTrue(!BackupService.running)
                onView(withId(R.id.startStop)).check(matches(withText(R.string.start)))
            }
        }
        // starting twice is harmless (second start is a no-op), stop via the service action
        BackupService.start(context); waitFor { BackupService.running }
        BackupService.start(context); Thread.sleep(300)
        assertTrue(BackupService.running)
        BackupService.stop(context); waitFor { !BackupService.running }
    }

    @Test
    fun serverModeWithoutUrlOpensSettingsInsteadOfStarting() {
        Prefs(context).apply { mode = Mode.SERVER; backendUrl = "" }
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.destination)).check(matches(withSubstring("Server only")))
            onView(withId(R.id.startStop)).perform(tap())
            waitForUi { onView(withId(R.id.modeServer)).check(matches(isDisplayed())) } // settings opened
            assertTrue(!BackupService.running)
            assertTrue(LogBus.snapshot().any { it.contains("Enter the server URL first") })
        }
    }
}
