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

    /** Fresh prefs + transfer list + log, service stopped. */
    fun reset(mode: Mode = Mode.PHONE, setupDone: Boolean = true) {
        if (BackupService.running) { BackupService.stop(context); waitFor { !BackupService.running } }
        context.getSharedPreferences("cambackup", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(context).apply { this.mode = mode; this.setupDone = setupDone; serverName = "[PC]UITest" }
        Transfers.init(context); Transfers.clear()
        LogBus.clear()
        Sinks.defaultFolder(context).deleteRecursively()
        Thread.sleep(50)
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
        @Volatile var healthy = true
        private val server = HttpServer(0, ::handle)
        val url: String get() = "http://127.0.0.1:${server.boundPort}"
        fun start() = apply { server.start() }
        fun stop() = server.stop()

        private fun handle(req: HttpRequest): HttpResponse {
            if (req.path == "/health") return HttpResponse.empty(if (healthy) 200 else 503)
            if (token.isNotEmpty() && req.header("authorization") != "Bearer $token") return HttpResponse.empty(401)
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
