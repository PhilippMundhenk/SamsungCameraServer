package org.mundhenk.cambackup.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

class CoreTest {
    @Test
    fun pairingFileFormatMatchesFirmwareParser() {
        val id = ServerIdentity("4a682b0b-0361-dbae-6155-0123456789ab", "[PC]Test", "02:4E:58:42:4B:50")
        val expected = "MacAddr=02:4E:58:42:4B:50\r\nUDN=uuid:4a682b0b-0361-dbae-6155-0123456789ab\r\nfriendlyName=[PC]Test\r\nWOLSupport=1\r\nServerFlag=1\r\n"
        assertEquals(expected, PairingFile.render(id))
        assertEquals(id, PairingFile.parse(expected))
    }

    @Test
    fun pairingZipContainsFileAtSdCardPath() {
        val id = ServerIdentity.generate()
        ZipInputStream(PairingFile.zip(id).inputStream()).use { zip ->
            val entry = zip.nextEntry!!
            assertEquals(PairingFile.SD_CARD_PATH, entry.name)
            assertEquals(PairingFile.render(id), zip.readBytes().toString(Charsets.US_ASCII))
        }
    }

    @Test
    fun generatedUdnKeepsSamsungPrefix() {
        repeat(20) { assertTrue(ServerIdentity.generateUdn().startsWith(ServerIdentity.SAMSUNG_UDN_PREFIX + "-")) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedUdn() { ServerIdentity("not-a-udn", "x", ServerIdentity.DEFAULT_MAC) }

    @Test
    fun deviceDescriptionCarriesIdentity() {
        val id = ServerIdentity.generate("[PC]Desc")
        val xml = UpnpXml.deviceDescription(id)
        assertTrue(xml.contains("<UDN>uuid:${id.udn}</UDN>"))
        assertTrue(xml.contains("<friendlyName>[PC]Desc</friendlyName>"))
        assertTrue(xml.contains("<controlURL>${UpnpXml.CDS_CONTROL_PATH}</controlURL>"))
    }

    @Test
    fun endToEndLoopback() {
        assertTrue(EndToEnd.run(log = { println(it) }, withSsdp = false))
    }
}
