package org.mundhenk.nxbackup.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mundhenk.nxbackup.server.TestSupport.config
import org.mundhenk.nxbackup.server.TestSupport.fake
import org.mundhenk.nxbackup.server.TestSupport.isWindows
import java.io.File

class ConfigLedgerToolsTest {
    private lateinit var dir: File
    private val log = ArrayList<String>()

    @Before fun setUp() { dir = TestSupport.tempDir("nxbackup-clt") }
    @After fun tearDown() { dir.deleteRecursively() }

    @Test
    fun configPrecedenceAndTypedAccessors() {
        val c = config(dir,
            "http.port" to " 8080 ", "ssdp.enabled" to "no", "api.token" to "", "raw.convert" to "yes", "raw.quality" to "lots",
            "export.dir" to "out", "storage.dir" to dir.absolutePath, "ui.enabled" to "1", "tools.path" to "a${File.pathSeparator}${File.pathSeparator}b",
            env = mapOf("NXBACKUP_HTTP_PORT" to "9090", "NXBACKUP_API_TOKEN" to "   ", "NXBACKUP_RAW_ONLYIFNOJPEG" to "FALSE"))
        assertEquals(9090, c.httpPort)                 // env wins
        assertNull(c.apiToken)                         // blank in both
        assertFalse(c.ssdpEnabled); assertTrue(c.rawConvert); assertTrue(c.uiEnabled); assertFalse(c.rawOnlyIfNoJpeg)
        assertEquals(92, c.rawJpegQuality)             // unparsable -> default
        assertEquals(File(dir, "out"), c.exportDir)    // relative to data dir
        assertEquals(dir.absoluteFile, c.storageDir)   // absolute stays
        assertEquals(listOf(File(dir, "a"), File(dir, "b")), c.toolsPath)
        assertNull(c.serverName); assertNull(c.serverUdn); assertNull(c.exifTimeShift); assertNull(c.renamePattern)
        assertNull(c.webhookUrl); assertNull(c.webhookToken); assertNull(c.hookCommand); assertNull(c.sysOffCommand)
        assertEquals("exiftool", c.exifTool); assertEquals("auto", c.rawConverter); assertEquals("{yyyy}-{MM}-{dd}", c.exportSubfolder)
        assertTrue(c.ssdpEnabled.not())
        val s = c.summary().toMap()
        assertEquals("(none - LAN only)", s["api.token"]); assertEquals("(off)", s["exif.timeshift"]); assertEquals("auto", s["raw.convert"])
        assertEquals(File(dir, "out").path, s["export.dir"]); assertEquals("(off)", s["webhook.url"])
        val full = config(dir, "api.token" to "x", "exif.timeshift" to "+1:00", "rename.pattern" to "{name}", "webhook.url" to "http://h", "hook.command" to "echo", "sysoff.command" to "halt")
        val fs = full.summary().toMap()
        assertEquals("(set)", fs["api.token"]); assertEquals("+1:00", fs["exif.timeshift"]); assertEquals("false", fs["raw.convert"])
        assertEquals("{name}", fs["rename.pattern"]); assertEquals("(off)", fs["export.dir"]); assertEquals("http://h", fs["webhook.url"]); assertEquals("echo", fs["hook.command"]); assertEquals("halt", fs["sysoff.command"])
        assertTrue(config(dir).toolsPath.isEmpty())
        assertNull(config(dir).fileOrNull("nothing"))
        assertEquals("d", config(dir).string("nothing", "d"))
    }

    @Test
    fun configFileIsWrittenOnFirstLoadAndReadAfterwards() {
        val c = Config.load(dir, emptyMap())
        val f = File(dir, Config.FILE_NAME)
        assertTrue(f.readText().contains("tools.path="))
        assertEquals(52235, c.httpPort)
        f.writeText("http.port=1234\nserver.name=[PC]FromFile\n")
        val c2 = Config.load(dir, emptyMap())
        assertEquals(1234, c2.httpPort); assertEquals("[PC]FromFile", c2.serverName)
    }

    @Test
    fun ledgerPersistsAndTolerateGarbage() {
        val f = File(dir, "sub/received.tsv")
        f.parentFile.mkdirs()
        f.writeText("short\tline\n2026-10-27\tA.JPG\tnotanumber\t/p/A.JPG\tnotanumber\n2026-10-27\tB.JPG\t10\t/p/B.JPG\t5\n2026-10-27\tb.jpg\t20\t/q/b.jpg\t6\n")
        val l = Ledger(f)
        assertEquals(2, l.size())
        assertEquals(-1L, l.find("2026-10-27", "A.JPG")!!.size)
        assertEquals(0L, l.find("2026-10-27", "A.JPG")!!.receivedAt)
        assertEquals("/q/b.jpg", l.find("2026-10-27", "B.JPG")!!.path) // last line wins, case-insensitive key
        assertTrue(l.contains("2026-10-27", "A.JPG", 999))               // unknown stored size matches anything
        assertTrue(l.contains("2026-10-27", "B.JPG", -1))                // unknown requested size matches
        assertTrue(l.contains("2026-10-27", "B.JPG", 20))
        assertFalse(l.contains("2026-10-27", "B.JPG", 21))
        assertFalse(l.contains("2026-10-28", "B.JPG", 20))
        l.updatePath("nope", "x", File("y")) // no-op
        l.record("2026-10-28", "C.JPG", 3, File("/p/C.JPG"))
        l.updatePath("2026-10-28", "C.JPG", File("/moved/C.JPG"))
        assertEquals(listOf("C.JPG", "b.jpg", "A.JPG"), l.recent(10).map { it.name })
        assertEquals(1, l.recent(1).size)
        val reloaded = Ledger(f)
        assertEquals(3, reloaded.size())
        assertEquals(File("/moved/C.JPG").path, reloaded.find("2026-10-28", "c.jpg")!!.path)
        assertEquals(0, Ledger(File(dir, "missing.tsv")).size())
    }

    @Test
    fun toolsResolveRunAndShell() {
        val bin = File(dir, "bin")
        fake(bin, "hello", "echo hi %1", "echo hi \$1")
        val t = Tools({ log += it }, listOf(File(dir, "nowhere"), bin))
        assertTrue(t.has("hello"))
        assertTrue(t.has("hello")) // cached
        assertFalse(t.has("definitely-not-a-program-xyz"))
        assertEquals(mapOf("definitely-not-a-program-xyz" to false, "hello" to true), t.status())
        val r = t.run("hello", "there")
        assertTrue(r.ok); assertEquals("hi there", r.output.trim())
        assertEquals("hello", Tools({}).resolve("hello"))                       // no search path: untouched
        assertEquals("a/b", t.resolve("a/b"))                                  // paths are not looked up
        assertTrue(t.resolve("hello").startsWith(bin.path))
        assertEquals("missing", t.resolve("missing"))
        // shell: platform shell, env passed through
        val sh = t.shell(if (isWindows) "echo %NX_T%" else "echo \$NX_T", mapOf("NX_T" to "env-ok"))
        assertTrue(sh.ok); assertEquals("env-ok", sh.output.trim())
        assertFalse(t.shell("exit 3", emptyMap()).ok)
        // timeout kills the process
        val slow = t.shell(if (isWindows) "ping -n 6 127.0.0.1 >nul" else "sleep 5", emptyMap(), timeoutSec = 1)
        assertEquals(-1, slow.exitCode); assertTrue(slow.output.contains("timeout after 1s"))
        // run with a working directory
        val cwd = t.run(*(if (isWindows) arrayOf("cmd", "/c", "cd") else arrayOf("pwd")), dir = bin)
        assertTrue(cwd.output, cwd.output.trim().endsWith("bin"))
        t.probeInBackground(listOf("hello", "nope2"))
        TestSupport.waitFor { t.status().containsKey("nope2") }
        assertEquals(false, t.status()["nope2"])
    }

    @Test
    fun serverLogKeepsARingBuffer() {
        ServerLog.log("one")
        repeat(600) { ServerLog.log("line $it") }
        val snap = ServerLog.snapshot()
        assertEquals(500, snap.size)
        assertTrue(snap.last().endsWith("line 599"))
        assertFalse(snap.any { it.endsWith(" one") })
    }
}
