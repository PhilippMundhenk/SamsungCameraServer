package org.mundhenk.cambackup.app

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withSubstring
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mundhenk.cambackup.app.TestSupport.context
import org.mundhenk.cambackup.app.TestSupport.tap
import org.mundhenk.cambackup.app.TestSupport.waitFor
import org.mundhenk.cambackup.app.TestSupport.waitForUi
import org.mundhenk.cambackup.core.RvfCameraSimulator
import org.mundhenk.cambackup.core.RvfClient
import org.mundhenk.cambackup.core.VideoCodec
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/**
 * Remote Viewfinder end to end: a simulated camera on the loopback interface (as strict as the
 * NX1000), real H.264 from the phone's own encoder decoded by MediaCodec, MJPEG, shutter, zoom,
 * saving the picture, refusals and a camera that goes away.
 */
@RunWith(AndroidJUnit4::class)
class ViewfinderTest {
    private val sims = ArrayList<RvfCameraSimulator>()

    @Before
    fun setUp() { TestSupport.reset(Mode.PHONE); ViewfinderActivity.reopenTimeoutMs = 60_000 }

    @After
    fun tearDown() { sims.forEach { it.stop() }; TestSupport.reset(Mode.PHONE); ViewfinderActivity.reopenTimeoutMs = 60_000 }

    private fun waitForShutter() = waitForUi(30_000) { onView(withId(R.id.shutter)).check(matches(androidx.test.espresso.matcher.ViewMatchers.isEnabled())) }

    private fun jpeg(w: Int, h: Int, color: Int): ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.compress(Bitmap.CompressFormat.JPEG, 80, out)
    }.toByteArray()

    private fun sim(profile: RvfCameraSimulator.Profile, frames: List<ByteArray>, codec: VideoCodec, w: Int, h: Int, photo: ByteArray = jpeg(64, 48, Color.RED), preview: ByteArray = jpeg(32, 24, Color.MAGENTA)) =
        RvfCameraSimulator(profile, frames, codec, w, h, fps = 15, photo = photo, preview = preview, log = { android.util.Log.i("UITest", "sim: $it") }).start().also { sims += it }

    private fun connectTo(s: RvfCameraSimulator) {
        onView(withId(R.id.address)).perform(replaceText("127.0.0.1:${s.controlPort}"))
        onView(withId(R.id.connect)).perform(tap())
    }

    @Test
    fun nx1000_h264LiveView_shutterSavesPreview_streamComesBack_disconnect() {
        val photo = jpeg(320, 240, Color.BLUE)
        val preview = jpeg(160, 120, Color.CYAN)
        val frames = H264.encode(320, 240, 30)
        assertTrue(frames.first().let { org.mundhenk.cambackup.core.AviFrameReader.isKeyFrame(it) })
        val s = sim(RvfCameraSimulator.Profile.NX1000, frames, VideoCodec.H264, 320, 240, photo, preview)
        ActivityScenario.launch(ViewfinderActivity::class.java).use { sc ->
            onView(withId(R.id.liveStatus)).check(matches(withText(R.string.vf_idle)))
            onView(withId(R.id.saveShots)).check(matches(androidx.test.espresso.matcher.ViewMatchers.isChecked()))
            connectTo(s)
            waitForUi { onView(withId(R.id.shutter)).check(matches(isDisplayed())) }
            waitForShutter() // after the start-up half-press
            onView(withId(R.id.zoomIn)).check(matches(withEffectiveVisibility(Visibility.GONE))) // NX1000: lens-ring zoom only
            onView(withId(R.id.setupCard)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            onView(withId(R.id.connect)).check(matches(withText(R.string.vf_disconnect)))
            waitForUi { onView(withId(R.id.liveInfo)).check(matches(withText("320×240 H.264"))) }
            var session: ViewfinderSession? = null
            sc.onActivity { session = it.session }
            waitFor(30_000) { session!!.framesDecoded >= 3 }

            onView(withId(R.id.shutter)).perform(tap())
            waitForUi(30_000) { onView(withId(R.id.lastShot)).check(matches(withText(context.getString(R.string.vf_preview_saved, "SAM_0001_preview.JPG")))) }
            val date = LocalDate.now().toString()
            assertNotNull(Transfers.get(date, "SAM_0001_preview.JPG"))
            // the 2012 firmware only hands out the _SM copy; the original stays on the card for Auto Backup
            assertArrayEquals(preview, Sinks.direct(context, null).open(date, "SAM_0001_preview.JPG")!!.use { it.readBytes() })
            assertEquals(listOf("/DCIM/100PHOTO/SAM_0001.JPG_SM"), s.downloads.toList())
            assertEquals(listOf("GetInfomation", "MULTIAF", "ReleaseSelfTimer", "MULTIAF", "Shot"), s.calls.toList())
            assertEquals(listOf(RvfClient.UA_PREFIX + Prefs(context).rvfClientId), s.userAgents.toList())
            // the camera ended the live view for the picture; it is back afterwards
            waitFor(30_000) { session!!.streamOpens.get() >= 2 && s.streamReaders == 1 }
            val decoded = session!!.framesDecoded
            waitFor(30_000) { session!!.framesDecoded > decoded }
            waitForShutter()

            onView(withId(R.id.connect)).perform(tap())
            waitForUi { onView(withId(R.id.setupCard)).check(matches(isDisplayed())) }
            onView(withId(R.id.liveStatus)).check(matches(withText(R.string.vf_closed)))
            waitFor { s.streamReaders == 0 }
            assertEquals(emptyList<String>(), s.violations.toList())
            assertTrue(LogBus.snapshot().any { it.contains("saved SAM_0001.JPG") })
        }
        // the address typed is remembered for next time
        assertEquals("127.0.0.1:${s.controlPort}", Prefs(context).rvfAddress)
    }

    @Test
    fun compact_mjpegLiveView_zoom_shotStaysOnCard() {
        val s = sim(RvfCameraSimulator.Profile.WB250F, listOf(jpeg(320, 240, Color.GREEN), jpeg(320, 240, Color.YELLOW)), VideoCodec.MJPEG, 320, 240)
        ActivityScenario.launch(ViewfinderActivity::class.java).use { sc ->
            onView(withId(R.id.saveShots)).perform(tap())
            assertEquals(false, Prefs(context).rvfSaveShots)
            connectTo(s)
            waitForUi { onView(withId(R.id.zoomIn)).check(matches(isDisplayed())) }
            waitForUi { onView(withId(R.id.liveImage)).check(matches(isDisplayed())) }
            var session: ViewfinderSession? = null
            sc.onActivity { session = it.session }
            waitFor { session!!.framesShown.get() >= 2 }
            waitForShutter()
            onView(withId(R.id.zoomIn)).perform(tap())
            waitForUi { onView(withId(R.id.liveInfo)).check(matches(withSubstring("zoom 1"))) }
            onView(withId(R.id.zoomIn)).perform(tap())
            waitForUi { onView(withId(R.id.liveInfo)).check(matches(withText("320×240 MJPEG · zoom 2"))) }
            onView(withId(R.id.zoomOut)).perform(tap())
            waitForUi { onView(withId(R.id.liveInfo)).check(matches(withText("320×240 MJPEG · zoom 1"))) }
            onView(withId(R.id.shutter)).perform(tap())
            waitForUi { onView(withId(R.id.lastShot)).check(matches(withText(R.string.vf_shot_on_card))) }
            assertEquals(listOf("GetInfomation", "AF", "ZoomIN", "ZoomIN", "ZoomOUT", "AF", "Shot"), s.calls.toList())
            assertTrue(Transfers.list().isEmpty())
        } // closing the screen while live ends the session (onStop)
        waitFor { s.streamReaders == 0 }
        assertEquals(emptyList<String>(), s.violations.toList())
    }

    @Test
    fun refusedOnCamera_invalidAddress_wifiSettings() {
        val s = sim(RvfCameraSimulator.Profile.NX1000, listOf(byteArrayOf(0, 0, 0, 1, 0x65)), VideoCodec.H264, 320, 240).apply { allow = { false } }
        Intents.init()
        try {
            ActivityScenario.launch(ViewfinderActivity::class.java).use {
                onView(withId(R.id.address)).perform(replaceText("not an address"))
                onView(withId(R.id.connect)).perform(tap())
                onView(withId(R.id.addressLayout)).check(matches(withTextInputError(context.getString(R.string.vf_address_invalid))))
                connectTo(s)
                waitForUi(30_000) { onView(withId(R.id.liveStatus)).check(matches(withSubstring("confirm the prompt"))) }
                onView(withId(R.id.liveStatus)).check(matches(withSubstring("Connection lost")))
                onView(withId(R.id.setupCard)).check(matches(isDisplayed()))
                onView(withId(R.id.connect)).check(matches(withText(R.string.vf_connect)))

                intending(hasAction(Settings.ACTION_WIFI_SETTINGS)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
                onView(withId(R.id.wifiSettings)).perform(tap())
                intended(hasAction(Settings.ACTION_WIFI_SETTINGS))
            }
        } finally { Intents.release() }
    }

    /** The camera goes away mid-session: the next command reports the error, the lost stream ends the session. */
    @Test
    fun cameraGoesAway() {
        ViewfinderActivity.reopenTimeoutMs = 3_000
        val s = sim(RvfCameraSimulator.Profile.NX1000, listOf(jpeg(160, 120, Color.WHITE)), VideoCodec.MJPEG, 160, 120)
        ActivityScenario.launch(ViewfinderActivity::class.java).use { sc ->
            connectTo(s)
            waitForUi { onView(withId(R.id.shutter)).check(matches(isDisplayed())) }
            var session: ViewfinderSession? = null
            sc.onActivity { session = it.session }
            s.stop()
            waitForUi(30_000) { onView(withId(R.id.liveStatus)).check(matches(withSubstring("Connection lost"))) }
            // a command after the session failed is ignored, nothing crashes
            session!!.shoot(); session!!.zoom(true)
            sc.onActivity { assertNull(it.session) }
        }
    }

    /** NX: a failed focus cancels the shot; a capture the camera fails shows its message; the session stays live. */
    @Test
    fun focusFailsAndCaptureFails() {
        val s = sim(RvfCameraSimulator.Profile.NX1000, listOf(jpeg(160, 120, Color.WHITE)), VideoCodec.MJPEG, 160, 120)
        ActivityScenario.launch(ViewfinderActivity::class.java).use {
            connectTo(s)
            waitForShutter()
            s.focusOk = false
            onView(withId(R.id.shutter)).perform(tap())
            waitForUi { onView(withId(R.id.lastShot)).check(matches(withText(R.string.vf_focus_failed))) }
            assertEquals(0, s.shotCount)
            assertEquals(listOf("GetInfomation", "MULTIAF", "ReleaseSelfTimer", "MULTIAF", "ReleaseSelfTimer"), s.calls.toList())
            s.focusOk = true; s.failShots = true
            onView(withId(R.id.shutter)).perform(tap())
            waitForUi { onView(withId(R.id.lastShot)).check(matches(withSubstring("could not take the picture"))) }
            onView(withId(R.id.shutter)).check(matches(isDisplayed()))
            assertEquals(emptyList<String>(), s.violations.toList())
        }
    }

    @Test
    fun mainMenuOpensViewfinder_andNetworkHelpers() {
        Intents.init()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                onView(withId(R.id.action_viewfinder)).perform(tap())
                intended(hasComponent(ViewfinderActivity::class.java.name))
            }
        } finally { Intents.release() }
        assertEquals("192.168.102.1" to 7676, CameraNetwork.parse(" http://192.168.102.1/ "))
        assertEquals("10.0.0.5" to 8080, CameraNetwork.parse("10.0.0.5:8080"))
        assertNull(CameraNetwork.parse(""))
        assertNull(CameraNetwork.parse("a b"))
        assertNull(CameraNetwork.parse(":7676"))
        assertNull(CameraNetwork.parse("host:x"))
        assertNull(CameraNetwork.parse("host:70000"))
        assertEquals(javax.net.SocketFactory.getDefault(), CameraNetwork.socketFactory(context, "localhost"))
        assertNotNull(CameraNetwork.socketFactory(context, "192.168.102.1")) // the emulator's Wi-Fi, or the default
        CameraNetwork.gateway(context) // emulator Wi-Fi gateway or null; must not throw
        val id = Prefs(context).rvfClientId
        assertTrue(id, Regex("02(:[0-9a-f]{2}){5}").matches(id))
        assertEquals(id, Prefs(context).rvfClientId)
    }

    private fun withTextInputError(text: String) = object : org.hamcrest.TypeSafeMatcher<android.view.View>() {
        override fun describeTo(d: org.hamcrest.Description) { d.appendText("TextInputLayout with error $text") }
        override fun matchesSafely(v: android.view.View) = (v as? com.google.android.material.textfield.TextInputLayout)?.error?.toString() == text
    }

    /** Real H.264 from the device's encoder, Annex B, SPS/PPS in front of the first key frame like the cameras send it. */
    object H264 {
        fun encode(w: Int, h: Int, count: Int): List<ByteArray> {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 400_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 15)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            val out = ArrayList<ByteArray>()
            var config = ByteArray(0)
            val info = MediaCodec.BufferInfo()
            var queued = 0
            var done = false
            val deadline = System.currentTimeMillis() + 30_000
            while (!done && System.currentTimeMillis() < deadline) {
                if (queued <= count) {
                    val i = enc.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        if (queued == count) enc.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        else {
                            val img = enc.getInputImage(i)!!
                            val shade = (queued * 8) and 0xff
                            for ((p, plane) in img.planes.withIndex()) {
                                val pw = if (p == 0) w else w / 2; val ph = if (p == 0) h else h / 2
                                val buf = plane.buffer
                                for (y in 0 until ph) for (x in 0 until pw) buf.put(y * plane.rowStride + x * plane.pixelStride, (if (p == 0) (shade + x) and 0xff else 128).toByte())
                            }
                            enc.queueInputBuffer(i, 0, w * h * 3 / 2, queued * 66_666L, 0)
                        }
                        queued++
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val b = enc.getOutputBuffer(o)!!
                    val bytes = ByteArray(info.size).also { b.position(info.offset); b.get(it) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) config = bytes
                    else if (bytes.isNotEmpty()) out += if (out.isEmpty()) config + bytes else bytes
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    enc.releaseOutputBuffer(o, false)
                }
            }
            enc.stop(); enc.release()
            check(out.isNotEmpty()) { "encoder produced nothing" }
            return out
        }
    }
}
