package org.mundhenk.cambackup.app

import android.content.Context
import android.view.View
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isClickable
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
import org.mundhenk.cambackup.core.HttpRequest
import org.mundhenk.cambackup.core.HttpResponse
import org.mundhenk.cambackup.core.HttpServer
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

object TestSupport {
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Fresh prefs + transfer list + log, service stopped, no system dialog in the way. */
    @Volatile private var workManagerReady = false

    fun reset(mode: Mode = Mode.PHONE, setupDone: Boolean = true) {
        clearScreen()
        if (!workManagerReady) {
            // synchronous WorkManager: jobs run only when the test meets their constraints
            androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
            workManagerReady = true
        }
        androidx.work.WorkManager.getInstance(context).cancelAllWork()
        Thumbnails.clear()
        if (BackupService.running) { BackupService.stop(context); waitFor { !BackupService.running } }
        context.getSharedPreferences("cambackup", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(context).apply { this.mode = mode; this.setupDone = setupDone; serverName = "[PC]UITest" }
        Transfers.init(context); Transfers.clear()
        LogBus.clear()
        Sinks.defaultFolder(context).deleteRecursively()
        Thread.sleep(50)
    }

    /**
     * CI emulators sometimes boot with the keyguard, an ANR dialog ("Wait" / "Close app") or a
     * permission prompt on top; Espresso then never sees a focused window. Dismiss what is there
     * and log the focused window so a failing run can be diagnosed from logcat.
     */
    fun clearScreen() {
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            device.executeShellCommand("wm dismiss-keyguard")
            device.executeShellCommand("settings put global hide_error_dialogs 1")   // the launcher's ANR dialog steals focus
            device.executeShellCommand("svc power stayon true")                        // long tests must not hit the screen timeout
            device.wakeUp()
            for (label in listOf("Wait", "Close app", "OK", "Allow", "Got it", "Don't allow")) {
                device.findObject(androidx.test.uiautomator.By.text(label))?.let { android.util.Log.i("UITest", "dismissing '$label'"); it.click() }
            }
            // the launcher's long-press / MENU popup holds window focus until it is closed
            for (label in listOf("Wallpaper & style", "Home settings", "Widgets")) {
                if (device.hasObject(androidx.test.uiautomator.By.text(label))) { android.util.Log.i("UITest", "closing launcher popup '$label'"); device.pressBack(); break }
            }
            val focus = device.executeShellCommand("dumpsys window").lineSequence().filter { "mCurrentFocus" in it || "mFocusedApp" in it }.joinToString(" | ") { it.trim() }
            android.util.Log.i("UITest", "focus before test: $focus")
        } catch (e: Exception) { android.util.Log.w("UITest", "clearScreen: $e") }
    }

    /**
     * Click through View.performClick() instead of an injected touch: on slow (software rendered)
     * emulators the injected up-event can arrive after the long-press timeout, Android then
     * reports a long press and the OnClickListener never fires. The real listener still runs.
     */
    fun tap(): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isClickable()
        override fun getDescription() = "performClick()"
        override fun perform(uiController: UiController, view: View) {
            view.performClick()
            uiController.loopMainThreadUntilIdle()
        }
    }

    /** Retry an Espresso assertion until it passes; the UI follows the service state asynchronously. */
    fun waitForUi(timeoutMs: Long = 15_000, assertion: () -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            try { assertion(); return } catch (e: Throwable) {
                if (System.currentTimeMillis() > deadline) throw e
                Thread.sleep(200)
            }
        }
    }

    fun waitFor(timeoutMs: Long = 45_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timeout waiting for condition")
            Thread.sleep(100)
        }
    }

    /** A home server stand-in implementing the backend contract on the loopback interface. */
    class FakeBackend(private val token: String = "") {
        val files = LinkedHashMap<String, ByteArray>()
        val events = CopyOnWriteArrayList<String>()
        val deviceNames = CopyOnWriteArrayList<String>()
        @Volatile var healthy = true
        @Volatile var identity = org.mundhenk.cambackup.core.ServerIdentity("4a682b0b-0361-dbae-6155-00000000beef", "[PC]FakeHome", org.mundhenk.cambackup.core.ServerIdentity.DEFAULT_MAC)
        @Volatile var serveIdentity = true
        private fun identityJson() = "{\"udn\":\"" + identity.udn + "\",\"name\":\"" + identity.friendlyName + "\",\"mac\":\"" + identity.macAddr + "\"}"
        private val server = HttpServer(0, ::handle)
        val url: String get() = "http://127.0.0.1:${server.boundPort}"
        fun start() = apply { server.start() }
        fun stop() = server.stop()

        private fun handle(req: HttpRequest): HttpResponse {
            if (req.path == "/health") return HttpResponse.empty(if (healthy) 200 else 503)
            req.header("x-nx-device")?.let { deviceNames += it }
            if (req.path == "/api/v1/identity" && req.method == "GET") return if (serveIdentity) HttpResponse(200, identityJson().toByteArray(), "application/json") else HttpResponse.notFound()
            if (token.isNotEmpty() && req.header("authorization") != "Bearer $token") return HttpResponse.empty(401)
            if (req.path == "/api/v1/identity" && req.method == "POST") {
                val body = req.readBodyText()
                val udn = Regex("\"udn\":\"([^\"]+)\"").find(body)!!.groupValues[1]
                val name = Regex("\"name\":\"([^\"]+)\"").find(body)!!.groupValues[1]
                identity = org.mundhenk.cambackup.core.ServerIdentity(udn, name, identity.macAddr)
                return HttpResponse(200, identityJson().toByteArray(), "application/json")
            }
            return when {
                req.path == "/api/v1/events" -> { events += req.readBodyText(); HttpResponse.empty(204) }
                req.path.startsWith("/api/v1/files/") -> {
                    val key = req.path.removePrefix("/api/v1/files/")
                    when (req.method) {
                        "HEAD" -> HttpResponse.empty(if (files.containsKey(key)) 200 else 404)
                        "PUT" -> { val b = req.body.readBytes(); val dup = files.containsKey(key); files[key] = b; HttpResponse.empty(if (dup) 200 else 201) }
                        else -> HttpResponse.empty(405)
                    }
                }
                else -> HttpResponse.notFound()
            }
        }
    }

    fun safRoot(): File = TestDocumentsProvider.rootDir(context)
}
