package org.mundhenk.cambackup.server

import org.mundhenk.cambackup.core.BackupServer
import org.mundhenk.cambackup.core.PairingFile
import org.mundhenk.cambackup.core.ServerIdentity
import java.io.File

/**
 * Multi-user (#20): every user in `users=` gets an identity of their own (UDN, name shown on the
 * camera), their own camera-facing server on their own port, their own folder under the storage
 * directory (`<storage>/<user>/…`), ledger, spool and pairing file. The pipeline is shared;
 * `user.<name>.<key>` overrides settings for that user, and exports default to
 * `<export.dir>/<user>`. Cameras are told apart by the pairing file they carry, so nothing has
 * to be configured per camera for this to work.
 */
class UserServers(
    private val config: Config,
    private val pipeline: Pipeline,
    private val devices: Devices,
    private val dataDir: File,
    private val log: (String) -> Unit,
    /** Tests pass 0 (any free port) instead of the configured per-user ports. */
    private val portOverride: Int? = null,
) {
    class UserServer(val def: Config.UserDef, val identity: ServerIdentity, val ledger: Ledger, val sink: ServerSink, val backup: BackupServer) {
        val name get() = def.name
        val port get() = backup.boundPort
    }

    private val running = LinkedHashMap<String, UserServer>()

    @Synchronized fun all(): List<UserServer> = running.values.toList()
    @Synchronized fun get(name: String): UserServer? = running[name]

    /** Starts servers for users that are new in the config and stops the ones that were removed. */
    @Synchronized
    fun sync() {
        val wanted = config.users()
        for (name in running.keys.toList()) {
            if (wanted.none { it.name == name }) {
                running.remove(name)!!.backup.stop()
                log("User $name removed; the files under ${File(config.storageDir, name)} stay")
            }
        }
        for (def in wanted) if (def.name !in running) start(def)
    }

    private fun start(def: Config.UserDef) {
        val identity = try { ServerIdentity(def.udn, def.friendlyName, ServerIdentity.DEFAULT_MAC) } catch (e: IllegalArgumentException) {
            log("User ${def.name}: invalid identity (${e.message}), not started"); return
        }
        val dir = File(dataDir, "users/${def.name}").apply { mkdirs() }
        File(dir, "SAMSUNGAutoBackupDESC.ini").writeText(PairingFile.render(identity), Charsets.US_ASCII)
        val ledger = Ledger(File(dir, "received.tsv"))
        val storage = File(config.storageDir, def.name).apply { mkdirs() }
        val sink = ServerSink(storage, ledger, pipeline, log, { camera -> config.forCameraAndUser(camera, def.name).storageSubfolder }, devices, user = def.name)
        val backup = BackupServer(identity, sink, httpPort = portOverride ?: def.port, log = log, enableSsdp = config.ssdpEnabled, spoolDir = File(dir, "spool"))
        try { backup.start() } catch (e: Exception) {
            log("User ${def.name}: cannot listen on port ${def.port} ($e); change user.${def.name}.port"); return
        }
        running[def.name] = UserServer(def, identity, ledger, sink, backup)
        log("User ${def.name}: '${identity.friendlyName}' uuid:${identity.udn} on port ${backup.boundPort}, files under $storage")
    }

    @Synchronized
    fun stopAll() {
        running.values.forEach { it.backup.stop() }
        running.clear()
    }
}
