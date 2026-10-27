package org.mundhenk.cambackup.core

import java.security.SecureRandom

/**
 * The identity a Samsung camera matches against when looking for its
 * Auto Backup target. Only [udn] is actually compared by the firmware
 * (AutoBackup::GetPCInfo -> targetUDN). [friendlyName] is shown on the
 * camera, [macAddr] is only used for the Wake-on-LAN packet.
 */
data class ServerIdentity(
    val udn: String,
    val friendlyName: String,
    val macAddr: String,
) {
    init {
        require(UDN_REGEX.matches(udn)) { "UDN must look like $SAMSUNG_UDN_PREFIX-xxxxxxxxxxxx" }
        require(friendlyName.isNotBlank()) { "friendlyName must not be blank" }
        require(MAC_REGEX.matches(macAddr)) { "MAC must look like AA:BB:CC:DD:EE:FF" }
    }

    companion object {
        /** Prefix the original Samsung PC AutoBackup tool used; kept for safety. */
        const val SAMSUNG_UDN_PREFIX = "4a682b0b-0361-dbae-6155"
        const val DEFAULT_NAME = "[PC]AutoBackup"
        /** Locally administered, unicast MAC. Irrelevant unless WoL is needed. */
        const val DEFAULT_MAC = "02:4E:58:42:4B:50" // "NXBKP"

        private val UDN_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val MAC_REGEX = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

        fun generateUdn(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(6).also { random.nextBytes(it) }
            val suffix = bytes.joinToString("") { "%02x".format(it) }
            return "$SAMSUNG_UDN_PREFIX-$suffix"
        }

        fun generate(name: String = DEFAULT_NAME, mac: String = DEFAULT_MAC) =
            ServerIdentity(generateUdn(), name, mac)
    }
}
