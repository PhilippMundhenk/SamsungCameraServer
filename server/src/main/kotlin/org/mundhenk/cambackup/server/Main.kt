package org.mundhenk.cambackup.server

import org.mundhenk.cambackup.core.BackupServer
import org.mundhenk.cambackup.core.CameraSimulator
import org.mundhenk.cambackup.core.PairingFile
import org.mundhenk.cambackup.core.ServerIdentity
import java.io.File
import java.io.PrintStream

/**
 * Self-hosted backup target.
 *
 *   cambackup-server [dataDir]            run the server (default: $CAMBACKUP_DATA or ./data)
 *   cambackup-server discover [seconds]   send an SSDP search like the camera does, print who answers
 *   cambackup-server simulate <host> [port] [count]   act as a camera against a running target
 *
 * The data directory holds cambackup.conf, SAMSUNGAutoBackupDESC.ini (the identity),
 * received.tsv (the ledger) and, by default, incoming/<date>/<name>.
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "-h", "--help" -> println(ServerCli.USAGE)
        "discover", "simulate" -> kotlin.system.exitProcess(ServerCli.run(args, System.out))
        else -> {
            val dataDir = File(args.firstOrNull() ?: System.getenv("CAMBACKUP_DATA") ?: "data").absoluteFile
            val server = CamBackupServer.start(dataDir)
            Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
            Thread.currentThread().join()
        }
    }
}

/** Troubleshooting commands: run them from any machine on the camera's network (e.g. `docker run --network host ... discover`). */
object ServerCli {
    const val USAGE = "usage: cambackup-server [dataDir] | discover [seconds] | simulate <host> [port] [files]   (env CAMBACKUP_DATA, config keys as CAMBACKUP_<KEY>)"

    fun run(args: Array<String>, out: PrintStream): Int = when (args.firstOrNull()) {
        "discover" -> {
            val seconds = args.getOrNull(1)?.toIntOrNull() ?: 3
            out.println("SSDP: searching for MediaServers for $seconds s (this is what the camera does)...")
            val location = CameraSimulator.discover(seconds * 1000, { out.println("  $it") })
            if (location == null) { out.println("No server answered. Same network segment as the server? Host networking / macvlan? See docs/SERVER.md."); 1 }
            else { out.println("Found: $location"); 0 }
        }
        "simulate" -> {
            val host = args.getOrNull(1)
            if (host == null) { out.println(USAGE); 2 } else {
                val port = args.getOrNull(2)?.toIntOrNull() ?: BackupServer.DEFAULT_HTTP_PORT
                val count = args.getOrNull(3)?.toIntOrNull() ?: 2
                val rnd = java.util.Random()
                val files = (1..count).map { i ->
                    CameraSimulator.SimFile(String.format("SIM_%04d.JPG", i), java.time.LocalDate.now().toString(), "image/jpeg", ByteArray(500_000).also { rnd.nextBytes(it) })
                }
                val r = CameraSimulator(host, port) { out.println("  $it") }.runBackup(files)
                out.println("Session done: server '${r.friendlyName}' uuid:${r.udn}, uploaded ${r.uploaded.size} of ${files.size}")
                if (r.uploaded.size == files.size) 0 else 1
            }
        }
        else -> { out.println(USAGE); 2 }
    }
}

/** Wires config, identity, sink, pipeline, API and the UPnP server. Also used by the tests. */
class CamBackupServer private constructor(
    val config: Config,
    identity: ServerIdentity,
    val ledger: Ledger,
    val pipeline: Pipeline,
    val sink: ServerSink,
    backup: BackupServer,
    val devices: Devices,
    private val api: Api,
    private val log: (String) -> Unit,
) {
    @Volatile var identity: ServerIdentity = identity
        private set
    val backup: BackupServer = backup
    val port: Int get() = backup.boundPort

    fun stop() {
        backup.stop()
        pipeline.shutdown()
    }

    /**
     * Switches to [wanted] at runtime: config file (server.udn / server.name), pairing file, and
     * the UPnP side (description + SSDP) so the camera sees the new UDN immediately. The HTTP
     * server keeps running: this is called from inside a request, which must still be answered.
     */
    @Synchronized
    fun applyIdentity(wanted: ServerIdentity): ServerIdentity {
        config.save(mapOf("server.udn" to wanted.udn, "server.name" to wanted.friendlyName))
        val effective = loadIdentity(config, log)      // honours environment overrides and rewrites the .ini
        if (effective == identity) return identity
        backup.changeIdentity(effective)
        identity = effective
        api.identity = effective
        log("Identity changed to ${effective.friendlyName} uuid:${effective.udn}; pairing file rewritten, export it to the SD cards again")
        return effective
    }

    companion object {
        fun start(dataDir: File, env: Map<String, String> = System.getenv(), httpPortOverride: Int? = null, log: (String) -> Unit = ServerLog::log): CamBackupServer {
            val config = Config.load(dataDir, env)
            val identity = loadIdentity(config, log)
            val ledger = Ledger(File(dataDir, "received.tsv"))
            val tools = Tools(log, config.toolsPath)
            tools.probeInBackground(listOf(config.exifTool, "darktable-cli", "rawtherapee-cli", "dcraw_emu"))
            val pipeline = Pipeline(config, ledger, tools, log)
            val devices = Devices(File(dataDir, "devices.tsv"))
            val sink = ServerSink(config.storageDir.apply { mkdirs() }, ledger, pipeline, log, { camera -> config.forCamera(camera).storageSubfolder }, devices)
            val backupRef = java.util.concurrent.atomic.AtomicReference<BackupServer>()
            val api = Api(config, identity, sink, ledger, tools, { backupRef.get()?.stats }, log, devices, Thumbnails(File(dataDir, "thumbs"), log))
            val backup = BackupServer(
                identity, sink, httpPort = httpPortOverride ?: config.httpPort, log = log,
                enableSsdp = config.ssdpEnabled, fallback = api, spoolDir = File(dataDir, "spool"),
            )
            backupRef.set(backup)
            backup.start()
            log("Config: ${File(dataDir, Config.FILE_NAME)} | storage: ${config.storageDir}${File.separator}${config.storageSubfolder.ifEmpty { "(flat)" }} | web UI: http://<host>:${backup.boundPort}/")
            log("Pairing file for the SD card: http://<host>:${backup.boundPort}/pairing.zip  (UDN uuid:${identity.udn})")
            if (config.apiToken == null) log("api.token is empty: the backend API accepts uploads from anyone on the network")
            return CamBackupServer(config, identity, ledger, pipeline, sink, backup, devices, api, log).also { server ->
                api.onIdentityChange = { wanted -> server.applyIdentity(wanted) }
            }
        }

        /** Identity lives in the pairing file itself; config may override name and UDN. */
        fun loadIdentity(config: Config, log: (String) -> Unit): ServerIdentity {
            val file = File(config.dataDir, "SAMSUNGAutoBackupDESC.ini")
            val existing = if (file.isFile) try { PairingFile.parse(file.readText()) } catch (e: Exception) { log("Ignoring unreadable ${file.name}: $e"); null } else null
            val udn = config.serverUdn?.removePrefix("uuid:")?.lowercase() ?: existing?.udn ?: ServerIdentity.generateUdn()
            val name = config.serverName ?: existing?.friendlyName ?: ServerIdentity.DEFAULT_NAME
            val identity = ServerIdentity(udn, name, existing?.macAddr ?: ServerIdentity.DEFAULT_MAC)
            if (existing != identity) {
                file.writeText(PairingFile.render(identity), Charsets.US_ASCII)
                log(if (existing == null) "Generated identity ${identity.friendlyName} uuid:${identity.udn} -> ${file.path}" else "Updated identity -> ${file.path}")
            }
            return identity
        }
    }
}
