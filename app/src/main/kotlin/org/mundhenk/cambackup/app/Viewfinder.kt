package org.mundhenk.cambackup.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.Surface
import org.mundhenk.cambackup.core.AviFrameReader
import org.mundhenk.cambackup.core.IncomingFile
import org.mundhenk.cambackup.core.RvfClient
import org.mundhenk.cambackup.core.RvfHttp
import org.mundhenk.cambackup.core.RvfHttpResponse
import org.mundhenk.cambackup.core.RvfInfo
import org.mundhenk.cambackup.core.VideoCodec
import org.mundhenk.cambackup.core.VideoFrame
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

/** The camera's own Wi-Fi: in Remote Viewfinder mode the camera is the access point and the gateway. */
object CameraNetwork {
    /** The Wi-Fi network the phone is on, if any (the camera's network has no internet). */
    fun wifi(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    /** The camera's address: the Wi-Fi gateway (192.168.102.1 on most models), null without Wi-Fi. */
    fun gateway(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = wifi(context) ?: return null
        return cm.getLinkProperties(net)?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
    }

    /**
     * Sockets over the camera's Wi-Fi even when mobile data is the default network (Android sends
     * everything else over mobile data once it notices the Wi-Fi has no internet). Loopback (tests,
     * a simulator on the phone) stays on the default factory.
     */
    fun socketFactory(context: Context, host: String): SocketFactory {
        if (host == "localhost" || host.startsWith("127.")) return SocketFactory.getDefault()
        return wifi(context)?.socketFactory ?: SocketFactory.getDefault()
    }

    /** "192.168.102.1" or "192.168.102.1:7676" -> host and port. */
    fun parse(address: String): Pair<String, Int>? {
        val a = address.trim().removePrefix("http://").trimEnd('/')
        if (a.isEmpty() || a.contains(' ')) return null
        val host = a.substringBefore(':')
        val port = if (a.contains(':')) a.substringAfter(':').toIntOrNull() ?: return null else RvfClient.DEFAULT_PORT
        return if (host.isEmpty() || port !in 1..65535) null else host to port
    }
}

/** Decodes the H.264 live view straight onto a Surface (hardware decoder where there is one). */
class H264Decoder(surface: Surface, width: Int, height: Int) {
    private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val info = MediaCodec.BufferInfo()
    private var started = false
    val decoded = AtomicLong(0)

    init {
        // no csd: the cameras send SPS/PPS in-band in front of every key frame
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, if (width > 0) width else 640, if (height > 0) height else 480)
        codec.configure(format, surface, null, 0)
        codec.start()
        started = true
    }

    fun feed(frame: VideoFrame, ptsUs: Long) {
        val idx = codec.dequeueInputBuffer(100_000)
        if (idx >= 0) {
            val buf = codec.getInputBuffer(idx)!!
            buf.clear(); buf.put(frame.data)
            codec.queueInputBuffer(idx, 0, frame.data.size, ptsUs, if (frame.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        drain()
    }

    private fun drain() {
        while (true) {
            val out = codec.dequeueOutputBuffer(info, 0)
            if (out >= 0) { codec.releaseOutputBuffer(out, true); decoded.incrementAndGet() } else if (out != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) return
        }
    }

    fun release() {
        if (!started) return
        started = false
        runCatching { codec.stop() }
        codec.release()
    }
}

/**
 * One Remote Viewfinder session, run the way Samsung's apps run it: pairing + GetInfomation, the
 * live stream opened right away (the NX1000 needs that), a half-press + release once the first
 * frame is there, and a single command thread so focus / shutter / zoom never overlap. The camera
 * ends the stream for every picture; it is opened again once the picture is fetched. All
 * callbacks arrive on the main thread.
 */
class ViewfinderSession(
    private val context: Context,
    private val host: String,
    private val port: Int,
    private val listener: Listener,
    /** How long to keep trying to reopen the live view (the camera needs up to a minute). */
    private val reopenTimeoutMs: Long = 60_000,
    private val surface: () -> Surface?,
) {
    enum class State { CONNECTING, LIVE, CLOSED, FAILED }

    interface Listener {
        fun onState(state: State, message: String)
        fun onConnected(client: RvfClient, info: RvfInfo) {}
        fun onStream(codec: VideoCodec, width: Int, height: Int) {}
        /** The camera answered the first half-press: the shutter can be used. */
        fun onReady() {}
        fun onJpegFrame(bitmap: Bitmap) {}
        fun onShot(name: String?, savedAs: String?, message: String) {}
        fun onZoom(position: Int?) {}
    }

    private val prefs = Prefs(context)
    private val main = Handler(Looper.getMainLooper())
    private val commands: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "rvf-commands").apply { isDaemon = true } }
    private val closed = AtomicBoolean(false)
    /** A picture is being taken / fetched: the stream is down on purpose, do not reopen yet. */
    private val capturing = AtomicBoolean(false)
    private val captureDone = Object()
    @Volatile private var stream: RvfHttpResponse? = null
    @Volatile private var decoder: H264Decoder? = null
    @Volatile var client: RvfClient? = null; private set
    val framesShown = AtomicLong(0)
    /** How often the live view was (re)opened. */
    val streamOpens = AtomicLong(0)
    val framesDecoded: Long get() = decoder?.decoded?.get() ?: framesShown.get()

    fun start() {
        post { listener.onState(State.CONNECTING, context.getString(R.string.vf_connecting, host)) }
        Thread({ run() }, "rvf-stream").apply { isDaemon = true }.start()
    }

    private fun run() {
        try {
            val c = RvfClient(host, port, prefs.rvfClientId, RvfHttp(CameraNetwork.socketFactory(context, host)), { LogBus.log("Viewfinder: $it") })
            val info = c.connect()
            client = c
            if (c.has("HeartBeat")) commands.scheduleWithFixedDelay({ if (!capturing.get()) runCatching { c.heartBeat() } }, 3, 3, TimeUnit.SECONDS)
            post { listener.onConnected(c, info); listener.onState(State.LIVE, context.getString(R.string.vf_live, c.friendlyName.ifBlank { host })) }
            var first = true
            while (!closed.get()) {
                val reader = if (first) open(c) else reopen(c) ?: break
                if (first) startUp(c)
                first = false
                try { pump(reader) } catch (e: java.io.IOException) { if (closed.get()) break else LogBus.log("Viewfinder: live view interrupted (${e.message})") }
            }
        } catch (e: Exception) {
            if (!closed.get()) fail(e.message ?: e.toString())
        } finally {
            decoder?.release(); decoder = null
        }
    }

    private fun open(c: RvfClient): AviFrameReader {
        val (resp, reader) = c.openStream()
        stream = resp
        streamOpens.incrementAndGet()
        if (closed.get()) resp.close()
        return reader
    }

    /** After the stream ended: wait for a picture in progress, then open it again for up to [reopenTimeoutMs]. Null = gave up (session failed). */
    private fun reopen(c: RvfClient): AviFrameReader? {
        runCatching { stream?.close() }
        synchronized(captureDone) { while (capturing.get() && !closed.get()) captureDone.wait(500) }
        val deadline = System.currentTimeMillis() + reopenTimeoutMs
        var last: Exception? = null
        while (!closed.get() && System.currentTimeMillis() < deadline) {
            try { return open(c) } catch (e: Exception) { last = e; Thread.sleep(1_000) }
        }
        if (!closed.get()) fail(context.getString(R.string.vf_stream_ended) + (last?.message?.let { " ($it)" } ?: ""))
        return null
    }

    /** What Samsung's app does once the first frame arrived: half-press, let go, then "connected". */
    private fun startUp(c: RvfClient) = command {
        if (c.canFocus) c.focus()
        c.releaseFocus()
        if (c.info?.availShots == 0) post { listener.onShot(null, null, context.getString(R.string.vf_card_full)) }
        post { listener.onReady() }
    }

    private fun pump(reader: AviFrameReader) {
        var announced = false
        var keySeen = false
        val t0 = System.nanoTime()
        while (!closed.get()) {
            val frame = reader.next() ?: return
            if (!announced) { announced = true; val (cdc, w, h) = Triple(reader.codec, reader.width, reader.height); post { listener.onStream(cdc, w, h) } }
            when (reader.codec) {
                VideoCodec.MJPEG -> BitmapFactory.decodeStream(ByteArrayInputStream(frame.data))?.let { bmp -> framesShown.incrementAndGet(); post { listener.onJpegFrame(bmp) } }
                else -> {
                    if (!keySeen && !frame.keyFrame) continue // a decoder needs SPS/PPS + IDR first
                    keySeen = true
                    val d = decoder ?: surface()?.let { H264Decoder(it, reader.width, reader.height) }?.also { decoder = it } ?: continue
                    d.feed(frame, (System.nanoTime() - t0) / 1000)
                }
            }
        }
    }

    /**
     * Half-press then full press, then (optionally) fetch the picture into the phone folder. On NX
     * models a failed focus cancels the shot, like on the camera (and in Samsung's app).
     */
    fun shoot() = command {
        // not connected (yet): give the shutter back instead of leaving it disabled
        val c = client ?: run { post { listener.onReady() }; return@command }
        if (c.canFocus) {
            val f = c.focus()
            if (!f.ok && c.cancelsShotOnFocusFail) {
                c.releaseFocus()
                post { listener.onShot(null, null, context.getString(R.string.vf_focus_failed)) }
                return@command
            }
        }
        capturing.set(true)
        try {
            val shot = c.shoot(toPhone = prefs.rvfSaveShots)
            val url = shot.url
            if (shot.availShots == 0) post { listener.onShot(null, null, context.getString(R.string.vf_card_full)) }
            if (!prefs.rvfSaveShots || url == null) {
                post { listener.onShot(url?.substringAfterLast('/'), null, context.getString(R.string.vf_shot_on_card)) }
                return@command
            }
            val pic = c.downloadShot(url)
            // the camera's small copy gets its own name: the full picture comes later with Auto Backup
            val name = if (pic.preview) pic.name.substringBeforeLast('.') + "_preview." + pic.name.substringAfterLast('.', "jpg") else pic.name
            val date = LocalDate.now().toString()
            val sink = Sinks.direct(context, prefs.folderUri)
            sink.store(IncomingFile(name, date, pic.bytes.size.toLong(), "image/jpeg", "object.item.imageItem", host), ByteArrayInputStream(pic.bytes))
            val where = sink.describe(date, name)
            Transfers.record(date, name, pic.bytes.size.toLong(), TransferState.ON_PHONE, where)
            LogBus.log("Viewfinder: saved $name (${Ui.formatSize(pic.bytes.size.toLong())}) to $where")
            post { listener.onShot(name, where, context.getString(if (pic.preview) R.string.vf_preview_saved else R.string.vf_shot_saved, name)) }
        } finally {
            capturing.set(false)
            synchronized(captureDone) { captureDone.notifyAll() }
        }
    }

    fun zoom(tele: Boolean) = command { val z = client?.zoom(tele); post { listener.onZoom(z) } }

    private fun command(block: () -> Unit) {
        if (closed.get()) return
        commands.execute {
            try { block() } catch (e: Exception) {
                val msg = context.getString(R.string.vf_command_failed, e.message ?: e.toString())
                LogBus.log("Viewfinder: $msg")
                post { listener.onShot(null, null, msg) }
            }
        }
    }

    private fun fail(message: String) {
        LogBus.log("Viewfinder: $message")
        closed.set(true)
        commands.shutdownNow()
        runCatching { stream?.close() }
        post { listener.onState(State.FAILED, message) }
    }

    /** Ends the session; the camera closes its viewfinder when the stream goes away (no bye on these models). */
    fun stop() {
        if (closed.getAndSet(true)) return
        commands.shutdownNow()
        synchronized(captureDone) { captureDone.notifyAll() }
        Thread { runCatching { stream?.close() } }.start()
        post { listener.onState(State.CLOSED, context.getString(R.string.vf_closed)) }
    }

    private fun post(r: () -> Unit) { main.post(r) }
}
