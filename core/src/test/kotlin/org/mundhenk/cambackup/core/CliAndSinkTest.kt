package org.mundhenk.cambackup.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class CliAndSinkTest {
    private fun cli(vararg args: String, block: Boolean = false): Pair<Int, String> {
        val bos = ByteArrayOutputStream()
        val code = Cli.run(arrayOf(*args), PrintStream(bos, true, "UTF-8"), {}, block)
        return code to bos.toString("UTF-8")
    }

    @Test
    fun commandLine() {
        val (pc, pairing) = cli("pairing", "[PC]Cli", "4a682b0b-0361-dbae-6155-000000000001")
        assertEquals(0, pc)
        assertEquals("MacAddr=${ServerIdentity.DEFAULT_MAC}\r\nUDN=uuid:4a682b0b-0361-dbae-6155-000000000001\r\nfriendlyName=[PC]Cli\r\nWOLSupport=1\r\nServerFlag=1\r\n", pairing)
        assertTrue(cli("pairing").second.contains("friendlyName=${ServerIdentity.DEFAULT_NAME}"))
        val (uc, usage) = cli()
        assertEquals(2, uc); assertTrue(usage.startsWith("usage:"))
        assertEquals(2, cli("bogus").first)
        val dir = File.createTempFile("cambackup-cli", "").apply { delete() }
        val (sc, serveOut) = cli("serve", dir.path, "[PC]Serve")
        assertEquals(0, sc)
        assertTrue(serveOut, serveOut.contains("friendlyName=[PC]Serve"))
        val (ec, e2e) = cli("e2e")
        assertEquals(0, ec); assertTrue(e2e.trim().endsWith("E2E PASSED"))
    }

    @Test
    fun fileSystemSinkDedupAndPartialWrites() {
        val root = File.createTempFile("cambackup-fs", "").apply { delete(); mkdirs() }
        try {
            val sink = FileSystemSink(root)
            val f = IncomingFile("../weird name?.JPG", "2026/10/27", 3, "image/jpeg", "object.item", "c")
            assertFalse(sink.exists(f))
            assertEquals(StoreResult.STORED, sink.store(f, ByteArrayInputStream("abc".toByteArray())))
            val stored = File(File(root, "2026-10-27"), ".._weird_name_.JPG")   // the date is parsed into the day layout, the name only sanitised
            assertEquals("abc", stored.readText())
            assertTrue(sink.exists(f))
            assertTrue(sink.exists(f.copy(size = -1)))
            assertFalse(sink.exists(f.copy(size = 4)))
            assertEquals(StoreResult.ALREADY_EXISTS, sink.store(f, ByteArrayInputStream("xyz".toByteArray())))
            assertEquals("abc", stored.readText())
            // size mismatch = different file -> overwritten (renameTo onto an existing file fails on Windows -> copy path)
            assertEquals(StoreResult.STORED, sink.store(f.copy(size = 4), ByteArrayInputStream("abcd".toByteArray())))
            assertEquals("abcd", stored.readText())
            assertFalse(File(stored.parentFile, stored.name + ".part").exists())
            assertEquals("unknown", FileSystemSink.safeSegment("")); assertEquals("___", FileSystemSink.safeSegment("///"))
            assertEquals("a-b_c.d", FileSystemSink.safeSegment("a-b c.d"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun identityAndPairingValidation() {
        assertTrue(runCatching { ServerIdentity("4a682b0b-0361-dbae-6155-000000000001", " ", ServerIdentity.DEFAULT_MAC) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ServerIdentity("4a682b0b-0361-dbae-6155-000000000001", "x", "not-a-mac") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { PairingFile.parse("friendlyName=x\r\n") }.exceptionOrNull()!!.message!!.contains("no UDN"))
        assertTrue(runCatching { PairingFile.parse("UDN=uuid:4a682b0b-0361-dbae-6155-000000000001\r\n") }.exceptionOrNull()!!.message!!.contains("no friendlyName"))
        // lines without '=' and a missing MacAddr are tolerated (default MAC)
        val id = PairingFile.parse("junk\n=nokey\nUDN=4a682b0b-0361-dbae-6155-000000000001\nfriendlyName=[PC]X\nWOLSupport=1")
        assertEquals(ServerIdentity("4a682b0b-0361-dbae-6155-000000000001", "[PC]X", ServerIdentity.DEFAULT_MAC), id)
        assertEquals("a&amp;b&lt;c&gt;d&quot;e&apos;f", UpnpXml.escape("a&b<c>d\"e'f"))
        val sim = CameraSimulator.SimFile("SAM.MP4", "2026-10-27", "video/mp4", ByteArray(0))
        assertEquals("object.item.videoItem", sim.upnpClass)
        assertEquals("object.item.imageItem", sim.copy(mime = "image/jpeg").upnpClass)
    }

    @Test
    fun simulatorToleratesRefusedCreateObject() {
        // sink says every file exists, server answers with the fault -> simulator skips the upload and continues
        val sink = object : BackupSink {
            override fun exists(file: IncomingFile) = true
            override fun store(file: IncomingFile, input: java.io.InputStream) = StoreResult.ALREADY_EXISTS
        }
        val server = BackupServer(ServerIdentity.generate(), sink, httpPort = 0, fileExistsErrorCode = 701, enableSsdp = false).apply { start() }
        try {
            val lines = ArrayList<String>()
            val r = CameraSimulator("127.0.0.1", server.boundPort) { lines += it }.runBackup(listOf(CameraSimulator.SimFile("A.JPG", "2026-10-27", "image/jpeg", ByteArray(10))))
            assertTrue(r.uploaded.isEmpty())
            assertTrue(lines.any { it.contains("refused: HTTP 500") })
            assertEquals(1, server.stats.filesSkipped.get())
        } finally { server.stop() }
    }
}
