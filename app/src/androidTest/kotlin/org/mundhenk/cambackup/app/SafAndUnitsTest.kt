package org.mundhenk.cambackup.app

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mundhenk.cambackup.app.TestSupport.context
import org.mundhenk.cambackup.app.TestSupport.waitFor
import org.mundhenk.cambackup.core.BackupServer
import org.mundhenk.cambackup.core.CameraSimulator
import org.mundhenk.cambackup.core.IncomingFile
import org.mundhenk.cambackup.core.StoreResult
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Random

/** SAF folder through the service, plus the small classes (Prefs, Transfers, Ui, LogBus). */
@RunWith(AndroidJUnit4::class)
class SafAndUnitsTest {
    @get:Rule
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33)
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    @Before fun setUp() { TestSupport.reset(Mode.PHONE); TestSupport.safRoot().deleteRecursively(); TestSupport.safRoot().mkdirs() }
    @After fun tearDown() { TestSupport.reset(Mode.PHONE) }

    @Test
    fun safFolderReceivesFilesFromTheCamera() {
        Prefs(context).folderUri = TestDocumentsProvider.treeUri()
        BackupService.start(context); waitFor { BackupService.running }
        try {
            val bytes = ByteArray(300_000).also { Random(3).nextBytes(it) }
            val sim = CameraSimulator("127.0.0.1", BackupServer.DEFAULT_HTTP_PORT) { android.util.Log.i("UITest", it) }
            val r = sim.runBackup(listOf(CameraSimulator.SimFile("SAM_0500.JPG", "2026-10-27", "image/jpeg", bytes)))
            assertEquals(listOf("SAM_0500.JPG"), r.uploaded)
            val stored = File(TestSupport.safRoot(), "2026-10-27/SAM_0500.JPG")
            waitFor { stored.isFile && stored.length() == bytes.size.toLong() }
            assertArrayEquals(bytes, stored.readBytes())
            waitFor { Transfers.get("2026-10-27", "SAM_0500.JPG") != null }
            assertTrue(Transfers.get("2026-10-27", "SAM_0500.JPG")!!.location.contains("2026-10-27/SAM_0500.JPG"))
            sim.runBackup(listOf(CameraSimulator.SimFile("SAM_0500.JPG", "2026-10-27", "image/jpeg", bytes)))
            waitFor { LogBus.snapshot().any { it.contains("Discarded duplicate SAM_0500.JPG") } }
        } finally { BackupService.stop(context); waitFor { !BackupService.running } }

        // the sink directly: open/describe, partial file replaced, missing folder
        val sink = Sinks.direct(context, TestDocumentsProvider.treeUri()) as SafSink
        assertArrayEquals(bytesOf(sink.open("2026-10-27", "SAM_0500.JPG")!!), File(TestSupport.safRoot(), "2026-10-27/SAM_0500.JPG").readBytes())
        assertNull(sink.open("2026-10-27", "nope.jpg")); assertNull(sink.open("2099-01-01", "nope.jpg"))
        assertTrue(sink.describe("2026-10-27", "x").endsWith("/2026-10-27/x"))
        val partial = IncomingFile("SAM_0500.JPG", "2026-10-27", 5, "image/jpeg", "object.item.imageItem", "c")
        assertFalse(sink.exists(partial))                                   // size differs
        assertEquals(StoreResult.STORED, sink.store(partial, ByteArrayInputStream("abcde".toByteArray())))
        assertEquals("abcde", File(TestSupport.safRoot(), "2026-10-27/SAM_0500.JPG").readText())
        assertEquals(StoreResult.ALREADY_EXISTS, sink.store(partial.copy(size = -1), ByteArrayInputStream("zzz".toByteArray())))
        val broken = SafSink(context, Uri.parse("content://no.such.provider/tree/root"))
        assertTrue(runCatching { broken.store(partial, ByteArrayInputStream(ByteArray(5))) }.isFailure)
        assertNull(broken.open("2026-10-27", "SAM_0500.JPG"))
        // FileSink helpers
        val fs = Sinks.direct(context, null) as FileSink
        assertNull(fs.open("2026-10-27", "missing.jpg"))
        assertTrue(fs.describe("2026-10-27", "a.jpg").endsWith("2026-10-27${File.separator}a.jpg"))
    }

    private fun bytesOf(s: java.io.InputStream) = s.use { it.readBytes() }

    @Test
    fun prefsTransfersAndHelpers() {
        val prefs = Prefs(context)
        // legacy mode names, defaults, trimming
        context.getSharedPreferences("cambackup", 0).edit().putString("mode", "DIRECT").commit()
        assertEquals(Mode.PHONE, prefs.mode)
        context.getSharedPreferences("cambackup", 0).edit().putString("mode", "BACKEND").commit()
        assertEquals(Mode.SERVER, prefs.mode)
        context.getSharedPreferences("cambackup", 0).edit().putString("mode", "garbage").commit()
        assertEquals(Mode.PHONE, prefs.mode)
        assertEquals(Mode.PHONE_AND_SERVER, Mode.parse("PHONE_AND_SERVER")); assertEquals(Mode.PHONE, Mode.parse(null))
        prefs.backendUrl = "  http://x/  "; assertEquals("http://x", prefs.backendUrl)
        prefs.serverName = " "; assertEquals("[PC]AutoBackup", prefs.serverName)
        assertNull(prefs.saveIdentity("4A682B0B-0361-DBAE-6155-00000000ABCD", " [PC]X "))
        assertEquals("4a682b0b-0361-dbae-6155-00000000abcd", prefs.udn); assertEquals("[PC]X", prefs.serverName)
        assertTrue(prefs.saveIdentity("bad", "x")!!.contains("UDN"))
        assertEquals(prefs.udn, prefs.identity().udn)
        prefs.mode = Mode.PHONE; prefs.folderUri = null
        assertEquals("Phone only · App storage", prefs.destinationSummary(context))
        prefs.mode = Mode.SERVER; prefs.backendUrl = ""
        assertEquals("Server only · ?", prefs.destinationSummary(context))
        prefs.mode = Mode.PHONE_AND_SERVER; prefs.folderUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures%2FNX")
        assertEquals("Phone + server · Pictures/NX (com) → ?", prefs.destinationSummary(context))
        assertEquals("/ (com)", Ui.friendlyTree(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A")))
        assertEquals("content://x", Ui.friendlyTree(Uri.parse("content://x")))

        // transfers persist as TSV, later lines win, listeners fire on the main thread
        Transfers.record("2026-10-27", "A.JPG", 5, TransferState.ON_PHONE, "/a")
        Transfers.record("2026-10-27", "B.JPG", 6, TransferState.ON_SERVER, "http://s")
        Transfers.markOnServer("2026-10-27", "a.jpg", "http://s")
        Transfers.markOnServer("2026-10-27", "missing", "http://s")
        val tsv = File(context.filesDir, "transfers.tsv").readLines()
        assertEquals(3, tsv.size)
        assertTrue(tsv[2].startsWith("2026-10-27\tA.JPG\t5\tON_SERVER\t"))
        assertEquals(listOf("A.JPG", "B.JPG"), Transfers.list().map { it.name })
        assertTrue(Transfers.pending().isEmpty())
        val fired = java.util.concurrent.atomic.AtomicInteger() // listener runs on the main thread
        val l: () -> Unit = { fired.incrementAndGet() }
        Transfers.addListener(l)
        Transfers.record("2026-10-27", "C.JPG", 1, TransferState.ON_PHONE, "/c")
        waitFor { fired.get() >= 1 }
        Transfers.removeListener(l)
        assertEquals(listOf("C.JPG"), Transfers.pending().map { it.name })
        Transfers.clear(); assertFalse(File(context.filesDir, "transfers.tsv").exists())

        // helpers
        assertEquals("?", Ui.formatSize(-1)); assertEquals("999 B", Ui.formatSize(999)); assertEquals("12 kB", Ui.formatSize(12_345))
        assertEquals("2.0 MB", Ui.formatSize(2_000_000)); assertEquals("1.50 GB", Ui.formatSize(1_500_000_000))
        assertTrue(Ui.formatTime(0).isNotBlank())
        LogBus.clear(); repeat(450) { LogBus.log("line $it") }
        waitFor { LogBus.snapshot().size == 400 }
        assertTrue(LogBus.snapshot().last().endsWith("line 449"))
    }
}
