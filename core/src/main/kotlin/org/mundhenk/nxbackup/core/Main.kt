package org.mundhenk.nxbackup.core

import java.io.File
import java.time.LocalTime

/**
 * Command line entry points (JVM):
 *   serve <dir> [name] [udn]   – run a backup target writing into <dir>
 *   pairing <name> [udn]       – print the SD-card pairing file
 *   e2e                        – loopback end-to-end self test (used by tools/e2e-jvm.sh)
 */
fun main(args: Array<String>) {
    val log: (String) -> Unit = { println("${LocalTime.now().withNano(0)} $it") }
    when (args.firstOrNull()) {
        "serve" -> {
            val dir = File(args.getOrNull(1) ?: "backup")
            val identity = ServerIdentity(
                udn = args.getOrNull(3) ?: ServerIdentity.generateUdn(),
                friendlyName = args.getOrNull(2) ?: ServerIdentity.DEFAULT_NAME,
                macAddr = ServerIdentity.DEFAULT_MAC,
            )
            println("Pairing file (put at <SD>/${PairingFile.SD_CARD_PATH}):\n" + PairingFile.render(identity))
            val server = BackupServer(identity, FileSystemSink(dir), log = log)
            server.start()
            Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
            Thread.currentThread().join()
        }
        "pairing" -> {
            val identity = ServerIdentity(args.getOrNull(2) ?: ServerIdentity.generateUdn(), args.getOrNull(1) ?: ServerIdentity.DEFAULT_NAME, ServerIdentity.DEFAULT_MAC)
            print(PairingFile.render(identity))
        }
        "e2e" -> {
            val ok = EndToEnd.run(log, withSsdp = args.contains("--ssdp"))
            println(if (ok) "E2E PASSED" else "E2E FAILED")
            if (!ok) System.exit(1)
        }
        else -> { System.err.println("usage: serve <dir> [name] [udn] | pairing <name> [udn] | e2e"); System.exit(2) }
    }
}

/** Loopback test shared by the CLI (no JUnit needed) and the JUnit test. */
object EndToEnd {
    fun run(log: (String) -> Unit, withSsdp: Boolean = false): Boolean {
        val dir = File.createTempFile("nxbackup-e2e", "").apply { delete(); mkdirs() }
        val identity = ServerIdentity.generate("[PC]E2E")
        val sinkEvents = ArrayList<String>()
        val sink = object : BackupSink {
            private val fs = FileSystemSink(dir)
            override fun onBackupStart(cameraAddress: String) { sinkEvents += "start:$cameraAddress" }
            override fun exists(file: IncomingFile) = fs.exists(file)
            override fun store(file: IncomingFile, input: java.io.InputStream) = fs.store(file, input)
            override fun onBackupDone(cameraAddress: String, sysOff: Boolean) { sinkEvents += "done:sysOff=$sysOff" }
        }
        val server = BackupServer(identity, sink, httpPort = 0, log = log, enableSsdp = withSsdp)
        server.start()
        try {
            val port = server.boundPort
            val sim = CameraSimulator("127.0.0.1", port, log)
            val rnd = java.util.Random(42)
            val jpg = ByteArray(3_000_000).also { rnd.nextBytes(it) }
            val srw = ByteArray(1_500_000).also { rnd.nextBytes(it) }
            val mp4 = ByteArray(500_000).also { rnd.nextBytes(it) }
            val files = listOf(
                CameraSimulator.SimFile("SAM_0001.JPG", "2026-10-26", "image/jpeg", jpg),
                CameraSimulator.SimFile("SAM_0001.SRW", "2026-10-26", "image/x-samsung-srw", srw),
                CameraSimulator.SimFile("SAM_0002.MP4", "2026-10-25", "video/mp4", mp4),
            )
            val r1 = sim.runBackup(files, sysOff = true)
            check(r1.udn == identity.udn) { "udn mismatch" }
            check(r1.friendlyName == "[PC]E2E") { "name mismatch" }
            check(r1.uploaded.size == 3) { "uploaded ${r1.uploaded}" }
            for (f in files) {
                val stored = File(File(dir, f.date), f.name)
                check(stored.isFile) { "missing ${stored}" }
                check(stored.readBytes().contentEquals(f.bytes)) { "bytes differ for ${f.name}" }
            }
            check(server.stats.filesStored.get() == 3) { "stats stored=${server.stats.filesStored.get()}" }
            // second run: everything is a duplicate -> discarded, nothing overwritten
            val r2 = sim.runBackup(files)
            check(r2.uploaded.size == 3)
            check(server.stats.filesStored.get() == 3 && server.stats.filesSkipped.get() == 3) { "dedup failed: ${server.stats.filesSkipped.get()}" }
            check(sinkEvents == listOf("start:127.0.0.1", "done:sysOff=true", "start:127.0.0.1", "done:sysOff=false")) { "events $sinkEvents" }
            // pairing file round trip
            val parsed = PairingFile.parse(PairingFile.render(identity))
            check(parsed == identity) { "pairing round trip" }
            if (withSsdp) {
                val loc = CameraSimulator.discover(log = log)
                check(loc != null && loc.endsWith(":$port${UpnpXml.DEVICE_DESC_PATH}")) { "ssdp discovery failed: $loc" }
            }
            log("All checks passed (${server.stats.bytesStored.get()} bytes stored in $dir)")
            return true
        } catch (e: Throwable) {
            log("FAILED: $e"); e.printStackTrace()
            return false
        } finally {
            server.stop(); dir.deleteRecursively()
        }
    }
}
