package org.mundhenk.cambackup.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mundhenk.cambackup.core.HttpResponse
import org.mundhenk.cambackup.core.HttpServer
import org.mundhenk.cambackup.server.TestSupport.config
import org.mundhenk.cambackup.server.TestSupport.fake
import org.mundhenk.cambackup.server.TestSupport.fakeDarktable
import org.mundhenk.cambackup.server.TestSupport.fakeDcraw
import org.mundhenk.cambackup.server.TestSupport.fakeExiftool
import org.mundhenk.cambackup.server.TestSupport.fakeFailing
import org.mundhenk.cambackup.server.TestSupport.fakeHook
import org.mundhenk.cambackup.server.TestSupport.ppm
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Every pipeline stage, driven with fake tools dropped into tools.path. */
class PipelineTest {
    private lateinit var dir: File
    private lateinit var bin: File
    private lateinit var storage: File
    private val log = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        dir = TestSupport.tempDir("cambackup-pipe"); bin = File(dir, "bin"); storage = File(dir, "incoming")
    }

    @After
    fun tearDown() { dir.deleteRecursively() }

    private fun pipeline(vararg pairs: Pair<String, String>): Triple<Pipeline, Ledger, Config> {
        val cfg = config(dir, "tools.path" to bin.path, *pairs)
        val ledger = Ledger(File(dir, "received.tsv"))
        return Triple(Pipeline(cfg, ledger, Tools({ log += it }, cfg.toolsPath), { log += it }), ledger, cfg)
    }

    private fun stored(ledger: Ledger, name: String, date: String = "2026-10-27", bytes: ByteArray = ByteArray(100) { it.toByte() }): ProcessedFile {
        val f = File(File(storage, date), name).apply { parentFile.mkdirs(); writeBytes(bytes) }
        ledger.record(date, name, bytes.size.toLong(), f)
        return ProcessedFile(date, name, bytes.size.toLong(), "10.0.0.9", f)
    }

    @Test
    fun timeShiftRenameAndExportWithExiftoolMetadata() {
        fakeExiftool(bin)
        val export = File(dir, "export")
        val (p, ledger, _) = pipeline("exif.timeshift" to "-0:30", "rename.pattern" to "{yyyy}{MM}{dd}_{HH}{mm}{ss}_{camera}_{base}.{ext}",
            "export.dir" to export.path, "export.subfolder" to "{yyyy}/{MM}\\{dd}")
        val jpg = stored(ledger, "SAM_0001.JPG")
        val txt = stored(ledger, "NOTES.TXT")
        p.submitFile(jpg); p.submitFile(txt); p.awaitIdle()
        val target = File(export, "2024/05/06/20240506_070809_NX1000_SAM_0001.JPG")
        assertTrue(log.joinToString("\n"), target.isFile)
        assertEquals(target, jpg.file)
        assertEquals(target.path, ledger.find("2026-10-27", "SAM_0001.JPG")!!.path)
        assertTrue(log.any { it == "Shifted EXIF dates of SAM_0001.JPG by -0:30" })
        assertTrue(log.any { it.startsWith("Renamed SAM_0001.JPG -> 20240506_070809_NX1000_SAM_0001.JPG") })
        // not an image: no exiftool calls, renamed with the camera date and midnight, exported under its own date
        assertEquals(File(export, "2026/10/27/20261027_000000__NOTES.TXT"), txt.file)
        assertNull(txt.error)
    }

    @Test
    fun missingOrFailingExiftoolDegradesGracefully() {
        val (p1, l1, _) = pipeline("exif.timeshift" to "+1:00", "rename.pattern" to "{yyyy}-{name}", "exif.tool" to "no-such-exiftool")
        val a = stored(l1, "A.JPG")
        p1.submitFile(a); p1.awaitIdle()
        assertTrue(log.any { it.contains("'no-such-exiftool' not found; skipped") })
        assertEquals("2026-A.JPG", a.file.name)
        fakeFailing(bin, "exiftool-broken")
        val (p2, l2, _) = pipeline("exif.timeshift" to "+1:00", "rename.pattern" to "{name}", "exif.tool" to "exiftool-broken")
        val b = stored(l2, "B.JPG")
        p2.submitFile(b); p2.awaitIdle()
        assertTrue(log.any { it.startsWith("exiftool time shift failed for B.JPG") })
        assertEquals("B.JPG", b.file.name) // pattern {name} = unchanged -> no rename
        p2.shutdown()
    }

    @Test
    fun rawPreviewWithDcrawAndItsFallbacks() {
        fakeDcraw(bin, ppm(4, 2))
        val (p, ledger, _) = pipeline("raw.convert" to "true", "raw.converter" to "dcraw", "rename.pattern" to "{base}_r.{ext}")
        val raw = stored(ledger, "SAM_0002.SRW")
        p.submitFile(raw); p.awaitIdle()
        assertNotNull(log.joinToString("\n"), raw.preview)
        assertEquals("SAM_0002_r.SRW", raw.file.name)
        assertEquals("SAM_0002_r.SRW.jpg", raw.preview!!.name) // preview followed the rename
        assertTrue(raw.preview!!.length() > 100)
        assertFalse(File(raw.file.parentFile, "SAM_0002.SRW.ppm").exists())
        assertTrue(log.any { it == "Rendered SAM_0002.SRW.jpg with dcraw" })
        // camera delivered the JPEG of the pair -> no preview
        stored(ledger, "SAM_0003.JPG")
        val pair = stored(ledger, "SAM_0003.SRW")
        p.submitFile(pair); p.awaitIdle()
        assertNull(pair.preview)
        assertTrue(log.any { it.contains("JPEG from camera present") })
        // not a RAW -> nothing
        val jpg = stored(ledger, "SAM_0004.JPG")
        p.submitFile(jpg); p.awaitIdle()
        assertNull(jpg.preview)
        // auto picks dcraw when it is the only one present; onlyifnojpeg=false ignores the sibling
        val (p2, l2, _) = pipeline("raw.convert" to "true", "raw.converter" to "auto", "raw.onlyifnojpeg" to "false")
        stored(l2, "SAM_0005.JPG")
        val r2 = stored(l2, "SAM_0005.SRW")
        p2.submitFile(r2); p2.awaitIdle()
        assertNotNull(r2.preview)
    }

    @Test
    fun rawPreviewWithDarktableRawtherapeeAndNone() {
        fakeDarktable(bin, "\u00FF\u00D8fakejpeg".toByteArray(Charsets.ISO_8859_1))
        val (p, ledger, _) = pipeline("raw.convert" to "true", "raw.converter" to "darktable")
        val r = stored(ledger, "D.SRW")
        p.submitFile(r); p.awaitIdle()
        assertEquals("D.SRW.jpg", r.preview?.name)
        assertTrue(File(dir, "darktable").isDirectory)
        // darktable failing
        fakeFailing(bin, "darktable-cli")
        val (pf, lf, _) = pipeline("raw.convert" to "true", "raw.converter" to "darktable")
        val rf = stored(lf, "F.SRW")
        pf.submitFile(rf); pf.awaitIdle()
        assertNull(rf.preview); assertTrue(log.any { it.startsWith("darktable-cli:") })
        // rawtherapee that exits 0 but writes nothing -> "produced no output"
        fake(bin, "rawtherapee-cli", "exit /b 0", "exit 0")
        val (pr, lr, _) = pipeline("raw.convert" to "true", "raw.converter" to "rawtherapee")
        val rr = stored(lr, "R.SRW")
        pr.submitFile(rr); pr.awaitIdle()
        assertNull(rr.preview); assertTrue(log.any { it.contains("with rawtherapee produced no output") })
        fakeFailing(bin, "rawtherapee-cli")
        val (pr2, lr2, _) = pipeline("raw.convert" to "true", "raw.converter" to "rawtherapee")
        val rr2 = stored(lr2, "R2.SRW")
        pr2.submitFile(rr2); pr2.awaitIdle()
        assertTrue(log.any { it.startsWith("rawtherapee-cli:") })
        // none / unknown converter / dcraw missing
        for (conv in listOf("none", "bogus", "dcraw")) {
            val (pn, ln, _) = pipeline("raw.convert" to "true", "raw.converter" to conv)
            val rn = stored(ln, "N_$conv.SRW")
            pn.submitFile(rn); pn.awaitIdle()
            assertNull(conv, rn.preview)
        }
        assertTrue(log.count { it.startsWith("raw.convert on but no converter available") } >= 3)
        // dcraw present but producing no ppm -> logged
        fake(bin, "dcraw_emu", "exit /b 0", "exit 0")
        val (pd, ld, _) = pipeline("raw.convert" to "true", "raw.converter" to "dcraw")
        val rd = stored(ld, "DD.SRW")
        pd.submitFile(rd); pd.awaitIdle()
        assertNull(rd.preview); assertTrue(log.any { it.startsWith("dcraw_emu:") })
        // classic dcraw binary name is accepted as well
        fake(bin, "dcraw", "if \"%1\"==\"-v\" exit /b 0\r\ncopy /y \"%~dp0fake.ppm\" \"%~dpn2.ppm\" >nul", "[ \"\$1\" = \"-v\" ] && exit 0\ncp \"\$(dirname \"\$0\")/fake.ppm\" \"\${2%.*}.ppm\"")
        File(bin, "fake.ppm").writeBytes(ppm(2, 2))
        File(bin, if (TestSupport.isWindows) "dcraw_emu.cmd" else "dcraw_emu").delete()
        val (pc, lc, _) = pipeline("raw.convert" to "true", "raw.converter" to "dcraw")
        val rc = stored(lc, "CC.SRW")
        pc.submitFile(rc); pc.awaitIdle()
        assertNotNull(log.joinToString("\n"), rc.preview)
    }

    @Test
    fun uniqueNamesExportWithoutSubfolderAndFailures() {
        val (p, ledger, _) = pipeline("rename.pattern" to "fixed.{ext}", "export.dir" to File(dir, "flat").path, "export.subfolder" to "/")
        val a = stored(ledger, "A.JPG"); val b = stored(ledger, "B.JPG")
        p.submitFile(a); p.submitFile(b); p.awaitIdle()
        assertEquals("fixed.JPG", a.file.name); assertEquals("fixed_1.JPG", b.file.name)
        assertEquals(File(dir, "flat"), a.file.parentFile)
        // pattern with illegal characters is sanitised; a pattern that expands to blank is ignored
        val (p2, l2, _) = pipeline("rename.pattern" to "a:b*c?\"<>|{base}")
        val c = stored(l2, "C.JPG")
        p2.submitFile(c); p2.awaitIdle()
        assertEquals("a_b_c_____C", c.file.name)
        val (p3, l3, _) = pipeline("rename.pattern" to "   ")
        val d = stored(l3, "D.JPG")
        p3.submitFile(d); p3.awaitIdle()
        assertEquals("D.JPG", d.file.name)
        // export into a path that is a file -> stage throws, error recorded, ledger still right
        val blocker = File(dir, "blocker").apply { writeText("x") }
        val (p4, l4, _) = pipeline("export.dir" to blocker.path, "export.subfolder" to "")
        val e = stored(l4, "E.JPG")
        p4.submitFile(e); p4.awaitIdle()
        assertNotNull(e.error)
        assertTrue(log.any { it.startsWith("Pipeline failed for E.JPG") })
        assertEquals(e.file.path, l4.find("2026-10-27", "E.JPG")!!.path)
    }

    @Test
    fun sessionHooksWebhookAndSysOff() {
        val received = CopyOnWriteArrayList<String>()
        val hook = HttpServer(0, { req -> received += "${req.header("authorization")}|${req.readBodyText()}"; HttpResponse.empty(202) }).apply { start() }
        val out = File(dir, "hook.out")
        val script = fakeHook(bin, "hook", out)
        val sysOut = File(dir, "sysoff.out")
        val sysScript = fakeHook(bin, "sysoff", sysOut)
        try {
            val (p, ledger, _) = pipeline("webhook.url" to "http://127.0.0.1:${hook.boundPort}/x", "webhook.token" to "wt",
                "hook.command" to "\"${script.path}\"", "sysoff.command" to "\"${sysScript.path}\"")
            val s = Session("10.0.0.9")
            val f = stored(ledger, "S.JPG").also { it.preview = File(dir, "S.jpg"); it.error = "e\"x" }
            s.files += f; s.stored = 1; s.skipped = 2
            p.submitSessionDone(s, sysOff = false); p.awaitIdle()
            assertEquals(1, received.size)
            val body = received[0]
            assertTrue(body, body.startsWith("Bearer wt|{\"event\":\"done\",\"camera\":\"10.0.0.9\",\"sysOff\":false,\"stored\":1,\"skipped\":2"))
            assertTrue(body.contains("\"preview\":") && body.contains("\"error\":\"e\\\"x\""))
            TestSupport.waitFor { out.isFile }
            assertEquals(log.joinToString(" | ") + " --- " + out.readText(), "done 10.0.0.9 false 1 2", out.readLines().firstOrNull()?.trim())
            assertFalse(sysOut.exists())
            assertTrue(log.any { it.startsWith("Webhook http://127.0.0.1:${hook.boundPort}/x -> HTTP 202") })
            assertTrue(log.any { it.startsWith("hook.command exit 0") })
            // sysOff -> sysoff.command runs too
            p.submitSessionDone(Session("10.0.0.9"), sysOff = true); p.awaitIdle()
            TestSupport.waitFor { sysOut.isFile }
            assertEquals("done 10.0.0.9 true 0 0", sysOut.readLines()[0].trim())
            assertTrue(log.any { it.contains("Camera requested shutdown") && it.contains("sysoff.command") })
            assertTrue(log.any { it.startsWith("sysoff.command exit 0") })
        } finally { hook.stop() }
        // unreachable webhook, failing hook command
        val (p2, _, _) = pipeline("webhook.url" to "http://127.0.0.1:1/x", "hook.command" to "exit 7")
        p2.submitSessionDone(Session("c"), false); p2.awaitIdle()
        assertTrue(log.any { it.startsWith("Webhook failed:") })
        assertTrue(log.any { it.startsWith("hook.command exit 7") })
        p2.shutdown()
    }

    @Test
    fun ppmReaderRejectsWhatItCannotRead() {
        fun bad(bytes: ByteArray): String = runCatching { Pipeline.readPpm(File(dir, "x.ppm").apply { writeBytes(bytes) }) }.exceptionOrNull()!!.message!!
        assertTrue(bad("P3\n1 1\n255\n".toByteArray()).contains("not a binary PPM"))
        assertTrue(bad("P6\n1 1\n65535\n".toByteArray()).contains("only 8-bit"))
        assertTrue(bad("P6\n2 2\n255\nabc".toByteArray()).contains("truncated"))
        val img = Pipeline.readPpm(File(dir, "ok.ppm").apply { writeBytes(ppm(3, 2)) })
        assertEquals(3, img.width); assertEquals(2, img.height)
        val ex = runCatching { Json.str("\u0001") }.getOrNull()
        assertEquals("\"\\u0001\"", ex)
        assertEquals("\"\\r\\t\"", Json.str("\r\t"))
        assertEquals("a\\b", Json.field("""{"p":"a\\b"}""", "p"))
        assertEquals("null", Json.field("""{"p":null}""", "p"))
        assertEquals("-1.5", Json.field("""{"p":-1.5}""", "p"))
    }

    @Test
    fun submitAfterFailureKeepsWorking() {
        val (p, ledger, _) = pipeline()
        val f = stored(ledger, "Z.JPG")
        f.file.delete()
        val (p2, _, _) = pipeline("rename.pattern" to "{base}2.{ext}")
        p2.submitFile(f); p2.awaitIdle()
        assertNotNull(f.error) // rename of a vanished file fails
        p.awaitIdle(); p.shutdown(); p2.shutdown()
    }
}
