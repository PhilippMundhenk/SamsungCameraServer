package org.mundhenk.cambackup.app

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withSubstring
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mundhenk.cambackup.app.TestSupport.context
import org.mundhenk.cambackup.app.TestSupport.tap
import org.mundhenk.cambackup.app.TestSupport.waitFor
import org.mundhenk.cambackup.app.TestSupport.waitForUi
import org.mundhenk.cambackup.core.PairingFile
import java.io.File
import java.util.zip.ZipInputStream

/** Settings screen and the first-start wizard; the system pickers are answered by Espresso Intents. */
@RunWith(AndroidJUnit4::class)
class SettingsAndWizardTest {
    @get:Rule
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33)
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    @Before
    fun setUp() { TestSupport.reset(Mode.PHONE); Intents.init() }

    @After
    fun tearDown() { Intents.release(); TestSupport.reset(Mode.PHONE) }

    private fun answerPicker(action: String, uri: Uri?) {
        intending(hasAction(action)).respondWith(Instrumentation.ActivityResult(if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK, uri?.let { Intent().setData(it) }))
    }

    @Test
    fun settingsPersistEverythingAndExportThePairingFile() {
        val prefs = Prefs(context)
        ActivityScenario.launch(SettingsActivity::class.java).use {
            waitForUi { onView(withId(R.id.modePhone)).check(matches(isChecked())) }
            onView(withId(R.id.serverGroup)).check(matches(not(isDisplayed())))
            onView(withId(R.id.modePhoneAndServer)).perform(scrollTo(), tap())
            assertEquals(Mode.PHONE_AND_SERVER, prefs.mode)
            onView(withId(R.id.serverGroup)).check(matches(isDisplayed()))
            onView(withId(R.id.folderGroup)).check(matches(isDisplayed()))
            onView(withId(R.id.modeServer)).perform(scrollTo(), tap())
            onView(withId(R.id.folderGroup)).check(matches(not(isDisplayed())))
            onView(withId(R.id.modePhone)).perform(scrollTo(), tap())

            // folder picker (cancelled, then a SAF tree from the test provider), and back to app storage
            answerPicker(Intent.ACTION_OPEN_DOCUMENT_TREE, null)
            onView(withId(R.id.chooseFolder)).perform(scrollTo(), tap())
            assertNull(prefs.folderUri)
            answerPicker(Intent.ACTION_OPEN_DOCUMENT_TREE, TestDocumentsProvider.treeUri())
            onView(withId(R.id.chooseFolder)).perform(scrollTo(), tap())
            waitFor { prefs.folderUri != null }
            onView(withId(R.id.folderLabel)).check(matches(withSubstring("root (org")))
            onView(withId(R.id.resetFolder)).perform(scrollTo(), tap())
            assertNull(prefs.folderUri)
            onView(withId(R.id.folderLabel)).check(matches(withText(R.string.folder_default)))

            // server fields are only shown in the server modes
            onView(withId(R.id.modePhoneAndServer)).perform(scrollTo(), tap())
            onView(withId(R.id.backendUrl)).perform(scrollTo(), replaceText("http://nas.lan:52235/"), closeSoftKeyboard())
            onView(withId(R.id.backendToken)).perform(scrollTo(), replaceText(" secret "), closeSoftKeyboard())

            // invalid UDN blocks the export and shows an error
            onView(withId(R.id.udn)).perform(scrollTo(), replaceText("not-a-udn"), closeSoftKeyboard())
            onView(withId(R.id.exportPairing)).perform(scrollTo(), tap())
            assertTrue(Intents.getIntents().none { it.action == Intent.ACTION_CREATE_DOCUMENT })
            onView(withId(R.id.regenerate)).perform(scrollTo(), tap())
            onView(withId(R.id.serverName)).perform(scrollTo(), replaceText("  [PC]Phone "), closeSoftKeyboard())
            // export: cancelled, failing target, then a document in the test provider
            answerPicker(Intent.ACTION_CREATE_DOCUMENT, null)
            onView(withId(R.id.exportPairing)).perform(scrollTo(), tap())
            answerPicker(Intent.ACTION_CREATE_DOCUMENT, Uri.parse("content://no.such.provider/x"))
            onView(withId(R.id.exportPairing)).perform(scrollTo(), tap())
            waitFor { LogBus.snapshot().any { it.contains("Pairing export failed") } }
            answerPicker(Intent.ACTION_CREATE_DOCUMENT, TestDocumentsProvider.documentUri("pairing.zip"))
            onView(withId(R.id.exportPairing)).perform(scrollTo(), tap())
            waitFor { LogBus.snapshot().any { it.contains("Pairing file exported") } }
            val zip = File(TestSupport.safRoot(), "pairing.zip").readBytes()
            ZipInputStream(zip.inputStream()).use { z ->
                assertEquals(PairingFile.SD_CARD_PATH, z.nextEntry!!.name)
                val id = PairingFile.parse(z.readBytes().toString(Charsets.US_ASCII))
                assertEquals("[PC]Phone", id.friendlyName); assertEquals(prefs.udn, id.udn)
            }
            onView(withId(R.id.showWizard)).perform(scrollTo(), tap())
            waitForUi { onView(withId(R.id.stepLabel)).check(matches(isDisplayed())) }
            pressBack()
        }
        // leaving the screen saved url/token/name
        assertEquals(Mode.PHONE_AND_SERVER, prefs.mode)
        assertEquals("http://nas.lan:52235", prefs.backendUrl)
        assertEquals("secret", prefs.backendToken)
        assertEquals("[PC]Phone", prefs.serverName)
    }

    @Test
    fun settingsAreLockedWhileTheServerRuns() {
        BackupService.start(context); waitFor { BackupService.running }
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use {
                waitForUi { onView(withId(R.id.lockedNotice)).check(matches(isDisplayed())) }
                onView(withId(R.id.modeServer)).check(matches(not(isEnabled())))
                onView(withId(R.id.udn)).check(matches(not(isEnabled())))
            }
        } finally { BackupService.stop(context); waitFor { !BackupService.running } }
    }

    @Test
    fun wizardRunsOnFirstStartAndSavesEveryStep() {
        val prefs = Prefs(context)
        prefs.setupDone = false
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForUi { onView(withId(R.id.stepLabel)).check(matches(withText("Step 1 of 3"))) }
            onView(withId(R.id.back)).check(matches(withText(R.string.wizard_skip)))
            onView(withId(R.id.wizModePhoneAndServer)).perform(scrollTo(), tap())
            onView(withId(R.id.next)).perform(tap())
            assertEquals(Mode.PHONE_AND_SERVER, prefs.mode)
            onView(withId(R.id.stepLabel)).check(matches(withText("Step 2 of 3")))
            onView(withId(R.id.folderGroup)).check(matches(isDisplayed()))
            onView(withId(R.id.serverGroup)).check(matches(isDisplayed()))
            // folder from the test provider
            answerPicker(Intent.ACTION_OPEN_DOCUMENT_TREE, TestDocumentsProvider.treeUri())
            onView(withId(R.id.chooseFolder)).perform(scrollTo(), tap())
            waitForUi { onView(withId(R.id.folderLabel)).check(matches(withSubstring("root ("))) }
            // server URL is required for this mode
            onView(withId(R.id.next)).perform(tap())
            onView(withId(R.id.stepLabel)).check(matches(withText("Step 2 of 3")))
            onView(withId(R.id.backendUrl)).perform(scrollTo(), replaceText("http://10.0.0.2:52235"), closeSoftKeyboard())
            onView(withId(R.id.backendToken)).perform(scrollTo(), replaceText("t"), closeSoftKeyboard())
            onView(withId(R.id.next)).perform(tap())
            onView(withId(R.id.stepLabel)).check(matches(withText("Step 3 of 3")))
            onView(withId(R.id.next)).check(matches(withText(R.string.wizard_finish)))
            // back and forth keeps the state
            onView(withId(R.id.back)).perform(tap())
            onView(withId(R.id.stepLabel)).check(matches(withText("Step 2 of 3")))
            onView(withId(R.id.next)).perform(tap())
            val before = prefs.udn
            onView(withId(R.id.regenerate)).perform(scrollTo(), tap())
            assertNotEquals(before, prefs.udn)
            onView(withId(R.id.udn)).check(matches(withText(prefs.udn)))
            onView(withId(R.id.serverName)).perform(scrollTo(), replaceText(""), closeSoftKeyboard())
            answerPicker(Intent.ACTION_CREATE_DOCUMENT, TestDocumentsProvider.documentUri("wizard.zip"))
            onView(withId(R.id.exportPairing)).perform(scrollTo(), tap())
            waitForUi { onView(withId(R.id.exported)).check(matches(isDisplayed())) }
            assertTrue(File(TestSupport.safRoot(), "wizard.zip").length() > 100)
            onView(withId(R.id.next)).perform(tap())
            waitFor { prefs.setupDone }
            assertEquals("[PC]AutoBackup", prefs.serverName) // blank name -> default
            assertEquals("http://10.0.0.2:52235", prefs.backendUrl)
            assertEquals(TestDocumentsProvider.treeUri(), prefs.folderUri)
            waitForUi { onView(withId(R.id.destination)).check(matches(withSubstring("Phone + server"))) }
        }
    }

    @Test
    fun wizardSkipAndServerOnlyPath() {
        val prefs = Prefs(context)
        prefs.setupDone = false
        ActivityScenario.launch(WizardActivity::class.java).use {
            waitForUi { onView(withId(R.id.wizModeServer)).perform(scrollTo(), tap()) }
            onView(withId(R.id.next)).perform(tap())
            onView(withId(R.id.folderGroup)).check(matches(not(isDisplayed())))
            onView(withId(R.id.serverGroup)).check(matches(isDisplayed()))
            onView(withId(R.id.back)).perform(tap())
            onView(withId(R.id.wizModePhone)).perform(scrollTo(), tap())
            onView(withId(R.id.next)).perform(tap())
            onView(withId(R.id.serverGroup)).check(matches(not(isDisplayed())))
            onView(withId(R.id.next)).perform(tap()) // no URL needed in phone mode
            onView(withId(R.id.stepLabel)).check(matches(withText("Step 3 of 3")))
            onView(withId(R.id.back)).perform(tap()); onView(withId(R.id.back)).perform(tap())
            onView(withId(R.id.back)).perform(tap()) // skip at step 1 -> finish
            waitFor { prefs.setupDone }
        }
        assertEquals(Mode.PHONE, prefs.mode)
        assertFalse(BackupService.running)
    }
}
