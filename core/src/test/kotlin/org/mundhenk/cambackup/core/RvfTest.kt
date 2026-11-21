package org.mundhenk.cambackup.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class RvfTest {
    private val logs = CopyOnWriteArrayList<String>()
    private val sims = ArrayList<RvfCameraSimulator>()

    private val idr = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1e, 0, 0, 0, 1, 0x68, 0xce.toByte(), 0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte())
    private val pFrame = byteArrayOf(0, 0, 1, 0x41, 0x9a.toByte(), 0x22)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    private fun sim(profile: RvfCameraSimulator.Profile, frames: List<ByteArray> = listOf(idr, pFrame), codec: VideoCodec = VideoCodec.H264, photo: ByteArray = jpeg) =
        RvfCameraSimulator(profile, frames, codec, photo = photo, fps = 50, log = { logs += "sim: $it" }).start().also { sims += it }

    private fun client(s: RvfCameraSimulator, id: String = "02:00:00:aa:bb:cc") =
        RvfClient("127.0.0.1", s.controlPort, id, log = { logs += it }, pairTimeoutMs = 5000, timeoutMs = 5000)

    @After
    fun tearDown() { sims.forEach { it.stop() } }

    /** The whole NX1000 session: pair, GetInfomation, stream, AF + Shot while streaming, download. */
    @Test
    fun nx1000Session() {
        val s = sim(RvfCameraSimulator.Profile.NX1000)
        val c = client(s)
        val info = c.connect()
        assertEquals("[Camera]NX1000", c.friendlyName)
        assertEquals("SP1", c.modelName)
        assertTrue(info.streamUrl.endsWith("/livestream.avi"))
        assertTrue(info.lowStreamUrl!!.endsWith("/qvga_livestream.avi"))
        assertEquals(listOf("off", "auto", "fill"), info.flashModes)
        assertEquals("auto", info.flash)
        assertEquals(999, info.availShots)
        assertFalse(c.canZoom)
        assertTrue(c.canFocus)
        assertFalse(c.heartBeat()) // NX1000 has none

        val (resp, reader) = c.openStream()
        resp.use {
            val f0 = reader.next()!!
            assertEquals(VideoCodec.H264, reader.codec)
            assertEquals(640, reader.width); assertEquals(480, reader.height)
            assertEquals(30000.0 / 1001, reader.fps, 0.01)
            assertTrue(f0.keyFrame); assertArrayEquals(idr, f0.data)
            val f1 = reader.next()!!
            assertFalse(f1.keyFrame); assertEquals(1L, f1.index)
            assertEquals("1", c.focus())
            val shot = c.shoot()
            assertEquals(998, shot.availShots)
            assertTrue(shot.url!!.endsWith("/DCIM/100PHOTO/SAM_0001.JPG"))
            assertArrayEquals(jpeg, c.download(shot.url!!))
        }
        assertEquals(listOf("GetInfomation", "MULTIAF", "Shot"), s.calls)
        assertTrue(s.userAgents.all { it == "SEC_RVF_02:00:00:aa:bb:cc" })
        assertEquals(emptyList<String>(), s.violations)
        // undeclared actions are refused before they reach (and hang) the camera
        try { c.zoom(true); fail() } catch (e: RvfException) { assertTrue(e.message!!.contains("ZoomIN")) }
        try { c.setFlash("off"); fail() } catch (e: RvfException) { }
        assertEquals("", c.call("SetLED", listOf("LEDTIME" to "0")).let { "" })
        assertEquals(emptyList<String>(), s.violations)
    }

    @Test
    fun compactWithZoomFlashAndRequestImage() {
        val s = sim(RvfCameraSimulator.Profile.WB250F, listOf(jpeg), VideoCodec.MJPEG)
        val c = client(s)
        val info = c.connect()
        assertEquals(18, info.maxZoom)
        assertTrue(c.canZoom)
        assertEquals(1, c.zoom(true)); assertEquals(2, c.zoom(true)); assertEquals(1, c.zoom(false))
        c.setFlash("off")
        val (resp, reader) = c.openStream(low = true)
        resp.use {
            val f = reader.next()!!
            assertEquals(VideoCodec.MJPEG, reader.codec)
            assertTrue(f.keyFrame)
            assertEquals("1", c.focus()) // AF on this model
            assertNull(c.shoot(toPhone = false).url)
            assertNotNull(c.shoot(toPhone = true).url)
        }
        assertEquals(listOf("GetInfomation", "ZoomIN", "ZoomIN", "ZoomOUT", "SetFlash", "AF", "Shot", "Shot"), s.calls)
        assertEquals(emptyList<String>(), s.violations)
    }

    /** NX3000: the plain prefix is refused, the client knocks on /mode/control and retries as SEC_RVF_ML_. */
    @Test
    fun nx3000NewerPairingGpsAndHeartBeat() {
        val s = sim(RvfCameraSimulator.Profile.NX3000)
        val c = client(s)
        c.connect()
        assertTrue(s.userAgents.first().startsWith("SEC_RVF_02"))
        assertTrue(s.userAgents.last().startsWith("SEC_RVF_ML_"))
        assertTrue(c.heartBeat())
        assertTrue(logs.any { it.contains("/mode/control") })
        assertEquals(emptyList<String>(), s.violations)
    }

    @Test
    fun refusedOnTheCamera() {
        val s = sim(RvfCameraSimulator.Profile.NX1000).apply { allow = { false } }
        try { client(s).connect(); fail() } catch (e: RvfException) {
            assertEquals(401, e.status)
            assertTrue(e.message!!.contains("confirm the prompt"))
        }
    }

    @Test
    fun simulatorFlagsClientMistakes() {
        val s = sim(RvfCameraSimulator.Profile.NX1000)
        val http = RvfHttp()
        fun soap(action: String, args: String = "", ua: String = "SEC_RVF_x"): Int {
            val body = "<s:Envelope><s:Body><u:$action xmlns:u=\"${UpnpXml.CDS_SERVICE_TYPE}\">$args</u:$action></s:Body></s:Envelope>".toByteArray()
            return http.request("127.0.0.1", s.controlPort, "POST", "/smp_4_", listOf("User-Agent" to ua, "SOAPACTION" to "\"${UpnpXml.CDS_SERVICE_TYPE}#$action\""), body).use { it.text(); it.status }
        }
        assertEquals(401, http.request("127.0.0.1", s.controlPort, "GET", "/smp_2_").use { it.status })
        assertEquals(401, soap("Shot", ua = "SEC_RVF_ML_x"))
        assertEquals(500, soap("MULTIAF")) // before GetInfomation and without a stream reader
        assertEquals(500, soap("ZoomIN"))
        assertEquals(500, soap("GetInfomation", "<GPSINFO>UNKNOWN</GPSINFO>"))
        assertEquals(200, soap("GetInfomation"))
        assertEquals(404, http.request("127.0.0.1", s.controlPort, "GET", "/nothing", listOf("User-Agent" to "SEC_RVF_x")).use { it.status })
        assertEquals(404, http.request("127.0.0.1", s.controlPort, "GET", "/DCIM/100PHOTO/other.jpg").use { it.status })
        val v = s.violations.toList()
        assertTrue(v.toString(), v.contains("MULTIAF before GetInfomation"))
        assertTrue(v.contains("MULTIAF while nobody reads the live stream"))
        assertTrue(v.contains("undeclared action ZoomIN"))
        assertTrue(v.contains("GetInfomation with undeclared argument GPSINFO"))

        // two readers: the second one is turned away
        val first = http.request("127.0.0.1", s.streamPort, "GET", "/livestream.avi", listOf("User-Agent" to "SEC_RVF_x"))
        assertEquals(206, first.status)
        AviFrameReader(first.body).next()
        val second = http.request("127.0.0.1", s.streamPort, "GET", "/livestream.avi", listOf("User-Agent" to "nobody"))
        assertEquals(503, second.status); second.close()
        assertTrue(s.violations.contains("second live stream reader"))
        assertTrue(s.violations.contains("live stream requested without the pairing User-Agent"))
        assertEquals(1, s.streamReaders)
        first.close()
        assertTrue(s.framesSent.get() > 0)
    }

    @Test
    fun simulatorNx3000WantsGpsAndStreamNeedsSession() {
        val s = sim(RvfCameraSimulator.Profile.NX3000)
        val http = RvfHttp()
        assertEquals(404, http.request("127.0.0.1", s.streamPort, "GET", "/livestream.avi", listOf("User-Agent" to "SEC_RVF_ML_x")).use { it.status })
        assertTrue(s.violations.contains("live stream requested before GetInfomation"))
        val body = "<u:GetInfomation xmlns:u=\"x\"></u:GetInfomation>".toByteArray()
        assertEquals(500, http.request("127.0.0.1", s.controlPort, "POST", "/smp_4_", listOf("User-Agent" to "SEC_RVF_ML_x", "SOAPACTION" to "x#GetInfomation"), body).use { it.text(); it.status })
        assertTrue(s.violations.contains("GetInfomation without GPSINFO"))
        s.start() // idempotent
        s.stop(); s.stop()
    }

    @Test
    fun cameraErrorsSurfaceAsRvfException() {
        val s = sim(RvfCameraSimulator.Profile.NX1000)
        val c = client(s)
        try { c.openStream(); fail() } catch (e: IllegalStateException) { }
        c.connect()
        // AF without a stream reader: the camera's fault becomes an exception with its description
        try { c.focus(); fail() } catch (e: RvfException) { assertEquals(500, e.status); assertTrue(e.message!!.contains("Action Failed")) }
        try { c.download("http://127.0.0.1:${s.controlPort}/DCIM/100PHOTO/missing.jpg"); fail() } catch (e: RvfException) { assertEquals(404, e.status) }
        s.stop()
        try { c.connect(); fail() } catch (e: IOException) { }
    }

    @Test
    fun streamRefusedByCamera() {
        val s = sim(RvfCameraSimulator.Profile.NX1000)
        val c = client(s)
        c.connect()
        val (keep, _) = c.openStream()
        keep.use { try { c.openStream(); fail() } catch (e: RvfException) { assertEquals(503, e.status) } }
    }

    @Test
    fun parsing() {
        assertEquals("/smp_4_", RvfClient.pathOf("http://192.168.102.1:7676/smp_4_"))
        assertEquals("/", RvfClient.pathOf("http://192.168.102.1:7676"))
        assertEquals("/smp_4_", RvfClient.pathOf("smp_4_"))
        assertEquals("/smp_4_", RvfClient.pathOf(" /smp_4_ "))
        assertEquals("v", RvfClient.tag("<a:Foo x=\"1\">v</a:Foo>", "Foo"))
        assertNull(RvfClient.tag("<Foo>v", "Foo"))
        assertEquals("<a>&", RvfClient.unescape("&lt;a&gt;&amp;"))
        val info = RvfClient.parseInfo("<x/>", "10.0.0.1")
        assertEquals("http://10.0.0.1:7679/livestream.avi", info.streamUrl)
        assertNull(info.lowStreamUrl); assertNull(info.flash); assertNull(info.availShots)
        assertEquals(0, info.maxZoom); assertEquals(0, info.rotation); assertEquals(emptyList<String>(), info.flashModes)
        val lowOnly = RvfClient.parseInfo("<QualityLowUrl>http://c/q.avi</QualityLowUrl>", "c")
        assertEquals("http://c/q.avi", lowOnly.streamUrl)
        val scpd = RvfClient.parseScpd("<action><name>A</name><argumentList><argument><name>X</name><direction>in</direction></argument><argument><name>R</name><direction>out</direction></argument></argumentList></action><action><name>B</name></action><action><argumentList/></action>")
        assertEquals(mapOf("A" to listOf("X"), "B" to emptyList()), scpd)
    }

    @Test
    fun escapedInfoAnswerIsUnescaped() {
        // some firmware sends GETINFORMATIONRESULT as escaped text instead of nested XML
        val server = HttpServer(0, { r ->
            when (r.path) {
                "/smp_2_" -> HttpResponse.xml("<root><friendlyName>[Camera]X</friendlyName><service><serviceType>${UpnpXml.CDS_SERVICE_TYPE}</serviceType><controlURL>http://127.0.0.1/ctl</controlURL><SCPDURL>scpd.xml</SCPDURL></service></root>")
                "/scpd.xml" -> HttpResponse.xml("<scpd></scpd>") // no action list: the client then trusts the caller
                "/ctl" -> HttpResponse.xml("<GETINFORMATIONRESULT>&lt;ZoomInfo&gt;&lt;MaxZoom&gt;5&lt;/MaxZoom&gt;&lt;/ZoomInfo&gt;</GETINFORMATIONRESULT>")
                else -> HttpResponse.notFound()
            }
        })
        server.start()
        try {
            val c = RvfClient("127.0.0.1", server.boundPort, "id")
            assertEquals(5, c.connect().maxZoom)
            assertEquals("", c.modelName)
            assertFalse(c.canZoom)
        } finally { server.stop() }
    }

    @Test
    fun httpEdgeCases() {
        val ss = ServerSocket(0)
        val answers = listOf(
            "", // closes without a word
            "garbage\r\n\r\n",
            "HTTP/1.0 200 OK\r\nNoColonHeader\r\n\r\nbody-until-eof",
            "HTTP/1.0 200 OK\r\nX: " + "y".repeat(17_000) + "\r\n\r\n",
            "HTTP/1.0 200 OK", // status line without newline, then EOF
        )
        val t = thread(isDaemon = true) {
            for (a in answers) ss.accept().use { s ->
                val input = s.getInputStream(); val buf = ByteArray(4096)
                var head = ""
                while (!head.contains("\r\n\r\n")) { val n = input.read(buf); if (n < 0) break; head += String(buf, 0, n) }
                s.getOutputStream().write(a.toByteArray())
            }
        }
        val http = RvfHttp()
        try { http.request("127.0.0.1", ss.localPort, "GET", "/"); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("without an answer")) }
        try { http.request("127.0.0.1", ss.localPort, "GET", "/"); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("not an HTTP answer")) }
        http.request("127.0.0.1", ss.localPort, "GET", "/").use { assertEquals("body-until-eof", it.text()); assertNull(it.header("NoColonHeader")) }
        try { http.request("127.0.0.1", ss.localPort, "GET", "/"); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("too long")) }
        http.request("127.0.0.1", ss.localPort, "GET", "/").use { assertEquals(200, it.status) }
        t.join(5000); ss.close()
    }

    @Test
    fun aviReaderEdgeCases() {
        fun avi(vararg parts: ByteArray) = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        val header = AviFrameReader.header(320, 240, VideoCodec.MJPEG)
        // odd-sized frame (pad byte), an empty frame, an audio chunk and an index that are skipped
        val odd = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 7)
        val audio = "01wb".toByteArray() + byteArrayOf(3, 0, 0, 0, 1, 2, 3, 0)
        val empty = "00dc".toByteArray() + byteArrayOf(0, 0, 0, 0)
        val r = AviFrameReader(ByteArrayInputStream(avi(header, audio, empty, AviFrameReader.chunk(odd), AviFrameReader.chunk(odd))))
        assertArrayEquals(odd, r.next()!!.data)
        assertEquals(VideoCodec.MJPEG, r.codec); assertEquals(320, r.width)
        assertArrayEquals(odd, r.next()!!.data)
        assertNull(r.next())

        // unknown codec in the header, sniffed from the first frame
        val h264Header = AviFrameReader.header(640, 480, VideoCodec.H264)
        val unknown = h264Header.copyOf().also { b -> Regex("H264").findAll(String(b, Charsets.ISO_8859_1)).forEach { m -> "ABCD".toByteArray().copyInto(b, m.range.first) } }
        val r2 = AviFrameReader(ByteArrayInputStream(avi(unknown, AviFrameReader.chunk(idr))))
        r2.next(); assertEquals(VideoCodec.H264, r2.codec)

        fun fails(bytes: ByteArray, expect: Class<out Exception> = IOException::class.java) {
            try { val rr = AviFrameReader(ByteArrayInputStream(bytes), maxFrameBytes = 1000); while (rr.next() != null) { }; fail() } catch (e: Exception) { assertTrue("$e", expect.isInstance(e)) }
        }
        fails(ByteArray(0), EOFException::class.java)
        fails("RIFX".toByteArray() + ByteArray(8))
        fails("RIFF".toByteArray() + ByteArray(4) + "WAVE".toByteArray())
        fails(avi(header, "00dc".toByteArray() + byteArrayOf(0, 0, 1, 0))) // 65536 > max
        fails(avi(header, "JUNK".toByteArray() + byteArrayOf(0, 0, 0, 0x80.toByte()))) // negative
        fails(avi(header, "strh".toByteArray() + byteArrayOf(0, 0, 0, 0x80.toByte())))
        fails(avi(header, "JUNK".toByteArray() + byteArrayOf(9, 0, 0, 0, 1)), EOFException::class.java)
        fails(avi(header, "00d".toByteArray()), EOFException::class.java)
        // a LIST header cut off and short header chunks are tolerated
        assertNull(AviFrameReader(ByteArrayInputStream(avi(header, "LIST".toByteArray() + ByteArray(4)))).next())
        val shortHeaders = avi("RIFF".toByteArray(), ByteArray(4), "AVI ".toByteArray(),
            "avih".toByteArray(), byteArrayOf(2, 0, 0, 0, 0, 0), "strh".toByteArray(), byteArrayOf(2, 0, 0, 0, 0, 0),
            "strf".toByteArray(), byteArrayOf(2, 0, 0, 0, 0, 0), AviFrameReader.chunk(pFrame))
        val r3 = AviFrameReader(ByteArrayInputStream(shortHeaders))
        assertFalse(r3.next()!!.keyFrame); assertEquals(0, r3.width); assertEquals(0.0, r3.fps, 0.0)

        assertEquals(VideoCodec.H264, AviFrameReader.codecOf("avc1"))
        assertEquals(VideoCodec.MJPEG, AviFrameReader.codecOf("jpeg"))
        assertNull(AviFrameReader.codecOf("XVID"))
        assertNull(AviFrameReader.sniff(byteArrayOf(1, 2, 3, 4)))
        assertNull(AviFrameReader.sniff(byteArrayOf(0, 0, 0, 0)))
        assertFalse(AviFrameReader.isKeyFrame(byteArrayOf(1, 2)))
        assertEquals(VideoCodec.UNKNOWN, VideoCodec.valueOf("UNKNOWN"))
    }
}
