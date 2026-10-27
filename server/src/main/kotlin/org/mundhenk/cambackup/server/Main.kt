package org.mundhenk.cambackup.server

import org.mundhenk.cambackup.core.BackupServer
import org.mundhenk.cambackup.core.PairingFile
import org.mundhenk.cambackup.core.ServerIdentity
import java.io.File

/**
 * Self-hosted backup target.
 *
 *   cambackup-server [dataDir]      (default: $CAMBACKUP_DATA or ./data)
 *
 * The data directory holds cambackup.conf, SAMSUNGAutoBackupDESC.ini (the identity),
 * received.tsv (the ledger) and, by default, incoming/<date>/<name>.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() in setOf("-h", "--help")) {
        println("usage: cambackup-server [dataDir]   (env CAMBACKUP_DATA, config keys as CAMBACKUP_<KEY>)"); return
    }
    val dataDir = File(args.firstOrNull() ?: System.getenv("CAMBACKUP_DATA") ?: "data").absoluteFile
    val server = CamBackupServer.start(dataDir)
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    Thread.currentThread().join()
}

/** Wires config, identity, sink, pipeline, API and the UPnP server. Also used by the tests. */
class CamBackupServer private constructor(
    val config: Config,
    val identity: ServerIdentity,
    val ledger: Ledger,
    val pipeline: Pipeline,
    val sink: ServerSink,
    val backup: BackupServer,
) {
    val port: Int get() = backup.boundPort

    fun stop() {
        backup.stop()
        pipeline.shutdown()
    }

    companion object {
        fun start(dataDir: File, env: Map<String, String> = System.getenv(), httpPortOverride: Int? = null, log: (String) -> Unit = ServerLog::log): CamBackupServer {
            val config = Config.load(dataDir, env)
            val identity = loadIdentity(config, log)
            val ledger = Ledger(File(dataDir, "received.tsv"))
            val tools = Tools(log, config.toolsPath)
            tools.probeInBackground(listOf(config.exifTool, "darktable-cli", "rawtherapee-cli", "dcraw_emu", "dcraw"))
            val pipeline = Pipeline(config, ledger, tools, log)
            val sink = ServerSink(config.storageDir.apply { mkdirs() }, ledger, pipeline, log)
            var statsRef: BackupServer? = null
            val api = Api(config, identity, sink, ledger, tools, { statsRef?.stats }, log)
            val backup = BackupServer(
                identity, sink, httpPort = httpPortOverride ?: config.httpPort, log = log,
                enableSsdp = config.ssdpEnabled, fallback = api,
            )
            statsRef = backup
            backup.start()
            log("Config: ${File(dataDir, Config.FILE_NAME)} | storage: ${config.storageDir} | web UI: http://<host>:${backup.boundPort}/")
            log("Pairing file for the SD card: http://<host>:${backup.boundPort}/pairing.zip  (UDN uuid:${identity.udn})")
            if (config.apiToken == null) log("api.token is empty: the backend API accepts uploads from anyone on the network")
            return CamBackupServer(config, identity, ledger, pipeline, sink, backup)
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
