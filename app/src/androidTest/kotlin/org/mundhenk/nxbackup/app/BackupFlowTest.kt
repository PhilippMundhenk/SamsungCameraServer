package org.mundhenk.nxbackup.app

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isClickable
import org.hamcrest.Matcher
import android.view.View
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withSubstring
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mundhenk.nxbackup.core.BackupServer
import org.mundhenk.nxbackup.core.CameraSimulator
import org.mundhenk.nxbackup.core.PairingFile
import java.io.File
import java.util.Random

/**
 * UI end-to-end: configure direct mode in the real activity, start the
 * foreground service through the button, act as the camera against the
 * running service, and verify files + UI feedback.
 */
@RunWith(AndroidJUnit4::class)
class BackupFlowTest {
    /** Avoid the POST_NOTIFICATIONS system dialog blocking Espresso on API 33+. */
    @get:Rule
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33)
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File

    @Before
    fun setUp() {
        Prefs(context).apply { mode = Mode.DIRECT; folderUri = null; serverName = "[PC]UITest" }
        folder = Sinks.defaultFolder(context).also { it.deleteRecursively(); it.mkdirs() }
        if (BackupService.running) { BackupService.stop(context); waitFor { !BackupService.running } }
    }

    @After
    fun tearDown() {
        if (BackupService.running) BackupService.stop(context)
        waitFor { !BackupService.running }
    }

    @Test
    fun startServer_receiveBackupFromSimulatedCamera_stop() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.modeDirect)).check(matches(isChecked()))
            onView(withId(R.id.serverName)).perform(scrollTo(), replaceText("[PC]UITest"), closeSoftKeyboard())
            onView(withId(R.id.startStop)).perform(scrollTo(), tap())
            waitFor { BackupService.running }
            waitForUi { onView(withId(R.id.startStop)).check(matches(withText(R.string.stop))) }
            onView(withId(R.id.status)).check(matches(withText(R.string.status_running)))

            val rnd = Random(7)
            val jpg = ByteArray(2_000_000).also { rnd.nextBytes(it) }
            val srw = ByteArray(1_000_000).also { rnd.nextBytes(it) }
            val sim = CameraSimulator("127.0.0.1", BackupServer.DEFAULT_HTTP_PORT) { android.util.Log.i("UITest", it) }
            val result = sim.runBackup(
                listOf(
                    CameraSimulator.SimFile("SAM_0042.JPG", "2026-10-26", "image/jpeg", jpg),
                    CameraSimulator.SimFile("SAM_0042.SRW", "2026-10-26", "image/x-samsung-srw", srw),
                )
            )
            assertEquals("[PC]UITest", result.friendlyName)
            assertEquals(Prefs(context).udn, result.udn)
            assertEquals(listOf("SAM_0042.JPG", "SAM_0042.SRW"), result.uploaded)

            val stored = File(File(folder, "2026-10-26"), "SAM_0042.JPG")
            waitFor { stored.isFile && stored.length() == jpg.size.toLong() }
            assertArrayEquals(jpg, stored.readBytes())
            assertArrayEquals(srw, File(File(folder, "2026-10-26"), "SAM_0042.SRW").readBytes())

            // log is rendered in the UI
            waitFor { LogBus.snapshot().any { l -> l.contains("Stored SAM_0042.SRW") } }
            onView(withId(R.id.log)).perform(scrollTo()).check(matches(withSubstring("Stored SAM_0042.JPG")))
            onView(withId(R.id.log)).check(matches(withSubstring("Backup session finished")))

            // second run is deduplicated
            sim.runBackup(listOf(CameraSimulator.SimFile("SAM_0042.JPG", "2026-10-26", "image/jpeg", jpg)))
            waitFor { LogBus.snapshot().any { l -> l.contains("Discarded duplicate SAM_0042.JPG") } }

            onView(withId(R.id.startStop)).perform(scrollTo(), tap())
            waitFor { !BackupService.running }
            waitForUi { onView(withId(R.id.startStop)).check(matches(withText(R.string.start))) }
        }
    }

    @Test
    fun pairingFile_matchesIdentityShownInUi() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.regenerate)).perform(scrollTo(), tap())
            onView(withId(R.id.serverName)).perform(scrollTo(), replaceText("[PC]Phone"), closeSoftKeyboard())
            // Export goes through the system file picker (not automatable); exercise the same code path directly.
            onView(withId(R.id.startStop)).perform(scrollTo(), tap()) // persists identity from the fields
            waitFor { BackupService.running }
            val id = Prefs(context).identity()
            val ini = PairingFile.render(id)
            assertTrue(ini.startsWith("MacAddr="))
            assertTrue(ini.contains("UDN=uuid:${id.udn}\r\n"))
            assertTrue(ini.contains("friendlyName=[PC]Phone\r\n"))
            assertTrue(ini.endsWith("ServerFlag=1\r\n"))
            assertEquals(id, PairingFile.parse(ini))
            assertTrue(PairingFile.zip(id).size > 100)
        }
    }

    /**
     * Click through View.performClick() instead of an injected touch: on slow (software rendered)
     * emulators the injected up-event can arrive after the long-press timeout, Android then
     * reports a long press and the OnClickListener never fires. The real listener still runs.
     */
    private fun tap(): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isClickable()
        override fun getDescription() = "performClick()"
        override fun perform(uiController: UiController, view: View) {
            view.performClick()
            uiController.loopMainThreadUntilIdle()
        }
    }

    /** Retry an Espresso assertion until it passes; the UI follows the service state asynchronously. */
    private fun waitForUi(timeoutMs: Long = 15_000, assertion: () -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            try { assertion(); return } catch (e: Throwable) {
                if (System.currentTimeMillis() > deadline) throw e
                Thread.sleep(200)
            }
        }
    }

    private fun waitFor(timeoutMs: Long = 45_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timeout waiting for condition")
            Thread.sleep(100)
        }
    }
}
