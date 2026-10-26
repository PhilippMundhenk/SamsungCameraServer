package org.mundhenk.nxbackup.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The file the camera reads to learn which media server it may push to.
 * Format verified against NX1000 firmware 1.15 (AutoBackup::GetPCInfo):
 * keys MacAddr, UDN, friendlyName, WOLSupport, ServerFlag — nothing else.
 * Lines are CRLF terminated, as written by Samsung's Windows tool.
 */
object PairingFile {
    const val SD_CARD_PATH = "dlna_web_root/SAMSUNGAutoBackupDESC.ini"

    fun render(identity: ServerIdentity): String =
        listOf(
            "MacAddr=${identity.macAddr}",
            "UDN=uuid:${identity.udn}",
            "friendlyName=${identity.friendlyName}",
            "WOLSupport=1",
            "ServerFlag=1",
        ).joinToString("\r\n", postfix = "\r\n")

    /** Zip containing the file at its SD-card path; unzip into the card's root. */
    fun zip(identity: ServerIdentity): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry(SD_CARD_PATH))
            zip.write(render(identity).toByteArray(Charsets.US_ASCII))
            zip.closeEntry()
        }
        return bos.toByteArray()
    }

    /** Parse an existing file (e.g. from a card paired with the Windows tool). */
    fun parse(content: String, mac: String = ServerIdentity.DEFAULT_MAC): ServerIdentity {
        val map = content.lines()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }.toMap()
        val udn = map["UDN"]?.removePrefix("uuid:") ?: error("no UDN")
        val name = map["friendlyName"] ?: error("no friendlyName")
        return ServerIdentity(udn, name, map["MacAddr"] ?: mac)
    }
}
