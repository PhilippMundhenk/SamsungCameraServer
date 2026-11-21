package org.mundhenk.cambackup.core

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** Codec of the live view: the stills/preview mode streams H.264, movie mode on some models MJPEG. */
enum class VideoCodec { H264, MJPEG, UNKNOWN }

/** One video frame from the stream: an H.264 access unit (Annex B) or a complete JPEG. */
class VideoFrame(val data: ByteArray, val keyFrame: Boolean, val index: Long)

/**
 * Reads the camera's endless RIFF/AVI live stream frame by frame. The camera writes zero for every
 * RIFF/LIST length (it cannot know them), so the reader never trusts a container length: LISTs are
 * entered rather than skipped, and only leaf chunk sizes are used. Video frames are the `##dc` /
 * `##db` chunks inside `movi`; everything else (JUNK, idx1, audio) is skipped.
 */
class AviFrameReader(input: InputStream, private val maxFrameBytes: Int = 8 * 1024 * 1024) {
    private val input = DataInputStream(input)
    private var started = false
    private var frames = 0L

    var width = 0; private set
    var height = 0; private set
    /** From the stream header (`strh` handler / `strf` compression), refined by the first frame. */
    var codec = VideoCodec.UNKNOWN; private set
    /** Frames per second from `strh` (rate / scale), 0 when unknown. */
    var fps = 0.0; private set

    /** The next video frame, or null at the end of the stream. */
    fun next(): VideoFrame? {
        if (!started) readRiffHeader()
        while (true) {
            val id = readFourCc() ?: return null
            val size = readLeInt()
            when {
                id == "LIST" -> { readFourCc() ?: return null } // enter: hdrl, strl, movi, rec
                id == "avih" -> parseAvih(readBody(size))
                id == "strh" -> parseStrh(readBody(size))
                id == "strf" -> parseStrf(readBody(size))
                isVideoChunk(id) -> {
                    if (size < 0 || size > maxFrameBytes) throw IOException("implausible frame size $size")
                    val data = readBody(size)
                    if (data.isEmpty()) continue
                    if (codec == VideoCodec.UNKNOWN || frames == 0L) sniff(data)?.let { codec = it }
                    return VideoFrame(data, isKeyFrame(data), frames++)
                }
                else -> skip(size)
            }
        }
    }

    private fun readRiffHeader() {
        started = true
        val riff = readFourCc() ?: throw EOFException("empty stream")
        if (riff != "RIFF") throw IOException("not an AVI stream (starts with '$riff')")
        readLeInt()
        val form = readFourCc()
        if (form != "AVI ") throw IOException("not an AVI stream (form '$form')")
    }

    private fun isVideoChunk(id: String) =
        id.length == 4 && id[0].isDigit() && id[1].isDigit() && (id.endsWith("dc") || id.endsWith("db"))

    private fun parseAvih(b: ByteArray) {
        if (b.size >= 40) { width = le(b, 32); height = le(b, 36) }
    }

    private fun parseStrh(b: ByteArray) {
        if (b.size < 28 || String(b, 0, 4, Charsets.ISO_8859_1) != "vids") return
        codecOf(String(b, 4, 4, Charsets.ISO_8859_1))?.let { codec = it }
        val scale = le(b, 20); val rate = le(b, 24)
        if (scale > 0 && rate > 0) fps = rate.toDouble() / scale
    }

    private fun parseStrf(b: ByteArray) {
        if (b.size < 20) return
        val w = le(b, 4); val h = le(b, 8)
        if (w in 1..10_000 && h in 1..10_000) { width = w; height = h }
        codecOf(String(b, 16, 4, Charsets.ISO_8859_1))?.let { codec = it }
    }

    private fun readFourCc(): String? {
        val b = ByteArray(4)
        var n = 0
        while (n < 4) {
            val r = input.read(b, n, 4 - n)
            if (r < 0) return if (n == 0) null else throw EOFException("stream ended inside a chunk id")
            n += r
        }
        return String(b, Charsets.ISO_8859_1)
    }

    private fun readLeInt(): Int {
        val b = ByteArray(4)
        input.readFully(b)
        return le(b, 0)
    }

    private fun readBody(size: Int): ByteArray {
        if (size < 0 || size > maxFrameBytes) throw IOException("implausible chunk size $size")
        val b = ByteArray(size)
        input.readFully(b)
        if (size % 2 == 1) input.read() // chunks are padded to even length
        return b
    }

    private fun skip(size: Int) {
        if (size < 0) throw IOException("implausible chunk size $size")
        var left = size.toLong() + (size % 2)
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) { if (input.read() < 0) throw EOFException("stream ended inside a chunk"); left-- } else left -= s
        }
    }

    companion object {
        fun le(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
                ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

        fun codecOf(fourCc: String): VideoCodec? = when (fourCc.trim().uppercase()) {
            "H264", "AVC1", "X264", "DAVC" -> VideoCodec.H264
            "MJPG", "JPEG", "MJPEG" -> VideoCodec.MJPEG
            else -> null
        }

        /** What the bytes look like: a JPEG starts with FF D8, an Annex B access unit with a start code. */
        fun sniff(d: ByteArray): VideoCodec? = when {
            d.size >= 2 && d[0] == 0xFF.toByte() && d[1] == 0xD8.toByte() -> VideoCodec.MJPEG
            d.size >= 4 && d[0].toInt() == 0 && d[1].toInt() == 0 && (d[2].toInt() == 1 || (d[2].toInt() == 0 && d[3].toInt() == 1)) -> VideoCodec.H264
            else -> null
        }

        /** JPEGs are always key frames; an H.264 access unit is one when it holds an IDR slice or an SPS. */
        fun isKeyFrame(d: ByteArray): Boolean {
            if (sniff(d) == VideoCodec.MJPEG) return true
            var i = 0
            while (i + 3 < d.size) {
                if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) {
                    val type = d[i + 3].toInt() and 0x1f
                    if (type == 5 || type == 7) return true
                    i += 3
                } else i++
            }
            return false
        }

        /** Builds a stream the way the cameras write it (zero container lengths); used by the simulator and tests. */
        fun header(width: Int, height: Int, codec: VideoCodec): ByteArray {
            val fourCc = if (codec == VideoCodec.MJPEG) "MJPG" else "H264"
            val out = java.io.ByteArrayOutputStream()
            fun cc(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
            fun i32(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff); out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff) }
            fun i16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
            cc("RIFF"); i32(0); cc("AVI ")
            cc("LIST"); i32(0); cc("hdrl")
            cc("avih"); i32(56); i32(33366); i32(0); i32(0); i32(0x110); i32(0); i32(0); i32(1); i32(0); i32(width); i32(height); repeat(4) { i32(0) }
            cc("LIST"); i32(0); cc("strl")
            cc("strh"); i32(56); cc("vids"); cc(fourCc); i32(0); i32(0); i32(0); i32(1001); i32(30000); i32(0); i32(0); i32(1_048_576); i32(10_000); i32(width * height * 3); i16(0); i16(0); i16(width); i16(height)
            cc("strf"); i32(40); i32(40); i32(width); i32(height); i16(1); i16(24); cc(fourCc); i32(width * height * 3); repeat(4) { i32(0) }
            cc("JUNK"); i32(3); out.write(byteArrayOf(0, 0, 0, 0)) // odd size + pad byte, like real files
            cc("LIST"); i32(0); cc("movi")
            return out.toByteArray()
        }

        /** One `00dc` chunk around [frame], padded to even length. */
        fun chunk(frame: ByteArray): ByteArray {
            val pad = frame.size % 2
            val out = ByteArray(8 + frame.size + pad)
            "00dc".toByteArray(Charsets.ISO_8859_1).copyInto(out)
            val n = frame.size
            out[4] = n.toByte(); out[5] = (n shr 8).toByte(); out[6] = (n shr 16).toByte(); out[7] = (n shr 24).toByte()
            frame.copyInto(out, 8)
            return out
        }
    }
}
