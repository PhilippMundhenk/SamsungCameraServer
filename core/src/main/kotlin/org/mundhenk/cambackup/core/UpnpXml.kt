package org.mundhenk.cambackup.core

/** All XML the camera ever fetches or receives from us. */
object UpnpXml {
    const val DEVICE_DESC_PATH = "/DMS/SamsungDmsDesc.xml"
    const val CDS_SCPD_PATH = "/DMS/ContentDirectory1.xml"
    const val CMS_SCPD_PATH = "/DMS/ConnectionManager1.xml"
    const val CDS_CONTROL_PATH = "/upnp/control/ContentDirectory1"
    const val CMS_CONTROL_PATH = "/upnp/control/ConnectionManager1"
    const val UPLOAD_PATH = "/cd/content"

    const val MEDIA_SERVER_TYPE = "urn:schemas-upnp-org:device:MediaServer:1"
    const val CDS_SERVICE_TYPE = "urn:schemas-upnp-org:service:ContentDirectory:1"
    const val CMS_SERVICE_TYPE = "urn:schemas-upnp-org:service:ConnectionManager:1"

    fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    fun deviceDescription(identity: ServerIdentity): String = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:sec="http://www.sec.co.kr/dlna" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <dlna:X_DLNADOC>DMS-1.50</dlna:X_DLNADOC>
    <deviceType>$MEDIA_SERVER_TYPE</deviceType>
    <friendlyName>${escape(identity.friendlyName)}</friendlyName>
    <manufacturer>Samsung Electronics</manufacturer>
    <manufacturerURL>http://www.samsung.com</manufacturerURL>
    <modelDescription>Samsung PC AutoBackup</modelDescription>
    <modelName>WiselinkPro</modelName>
    <modelNumber>1.0</modelNumber>
    <modelURL>http://www.samsung.com</modelURL>
    <serialNumber>20080818WiselinkPro</serialNumber>
    <sec:ProductCap>smi,DCM10,getMediaInfo.sec,getCaptionInfo.sec</sec:ProductCap>
    <UDN>uuid:${identity.udn}</UDN>
    <serviceList>
      <service>
        <serviceType>$CDS_SERVICE_TYPE</serviceType>
        <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
        <controlURL>$CDS_CONTROL_PATH</controlURL>
        <eventSubURL>/upnp/event/ContentDirectory1</eventSubURL>
        <SCPDURL>$CDS_SCPD_PATH</SCPDURL>
      </service>
      <service>
        <serviceType>$CMS_SERVICE_TYPE</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <controlURL>$CMS_CONTROL_PATH</controlURL>
        <eventSubURL>/upnp/event/ConnectionManager1</eventSubURL>
        <SCPDURL>$CMS_SCPD_PATH</SCPDURL>
      </service>
    </serviceList>
  </device>
</root>
"""

    private fun arg(name: String, dir: String, sv: String) =
        "<argument><name>$name</name><direction>$dir</direction><relatedStateVariable>$sv</relatedStateVariable></argument>"

    private fun action(name: String, vararg args: String) =
        "<action><name>$name</name><argumentList>${args.joinToString("")}</argumentList></action>"

    private fun sv(name: String, type: String, sendEvents: Boolean = false) =
        "<stateVariable sendEvents=\"${if (sendEvents) "yes" else "no"}\"><name>$name</name><dataType>$type</dataType></stateVariable>"

    val contentDirectoryScpd: String = """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    ${action("GetSearchCapabilities", arg("SearchCaps", "out", "SearchCapabilities"))}
    ${action("GetSortCapabilities", arg("SortCaps", "out", "SortCapabilities"))}
    ${action("GetSystemUpdateID", arg("Id", "out", "SystemUpdateID"))}
    ${action("Browse",
        arg("ObjectID", "in", "A_ARG_TYPE_ObjectID"), arg("BrowseFlag", "in", "A_ARG_TYPE_BrowseFlag"),
        arg("Filter", "in", "A_ARG_TYPE_Filter"), arg("StartingIndex", "in", "A_ARG_TYPE_Index"),
        arg("RequestedCount", "in", "A_ARG_TYPE_Count"), arg("SortCriteria", "in", "A_ARG_TYPE_SortCriteria"),
        arg("Result", "out", "A_ARG_TYPE_Result"), arg("NumberReturned", "out", "A_ARG_TYPE_Count"),
        arg("TotalMatches", "out", "A_ARG_TYPE_Count"), arg("UpdateID", "out", "A_ARG_TYPE_UpdateID"))}
    ${action("CreateObject",
        arg("ContainerID", "in", "A_ARG_TYPE_ObjectID"), arg("Elements", "in", "A_ARG_TYPE_Result"),
        arg("ObjectID", "out", "A_ARG_TYPE_ObjectID"), arg("Result", "out", "A_ARG_TYPE_Result"))}
    ${action("DestroyObject", arg("ObjectID", "in", "A_ARG_TYPE_ObjectID"))}
    ${action("GetTransferProgress",
        arg("TransferID", "in", "A_ARG_TYPE_TransferID"), arg("TransferStatus", "out", "A_ARG_TYPE_TransferStatus"),
        arg("TransferLength", "out", "A_ARG_TYPE_TransferLength"), arg("TransferTotal", "out", "A_ARG_TYPE_TransferTotal"))}
    ${action("StopTransferResource", arg("TransferID", "in", "A_ARG_TYPE_TransferID"))}
    ${action("X_BACKUP_START")}
    ${action("X_BACKUP_DONE")}
  </actionList>
  <serviceStateTable>
    ${sv("SearchCapabilities", "string")}${sv("SortCapabilities", "string")}
    ${sv("SystemUpdateID", "ui4", true)}${sv("TransferIDs", "string", true)}
    ${sv("A_ARG_TYPE_ObjectID", "string")}${sv("A_ARG_TYPE_Result", "string")}
    ${sv("A_ARG_TYPE_BrowseFlag", "string")}${sv("A_ARG_TYPE_Filter", "string")}
    ${sv("A_ARG_TYPE_SortCriteria", "string")}${sv("A_ARG_TYPE_Index", "ui4")}
    ${sv("A_ARG_TYPE_Count", "ui4")}${sv("A_ARG_TYPE_UpdateID", "ui4")}
    ${sv("A_ARG_TYPE_TransferID", "ui4")}${sv("A_ARG_TYPE_TransferStatus", "string")}
    ${sv("A_ARG_TYPE_TransferLength", "string")}${sv("A_ARG_TYPE_TransferTotal", "string")}
  </serviceStateTable>
</scpd>
"""

    val connectionManagerScpd: String = """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    ${action("GetProtocolInfo", arg("Source", "out", "SourceProtocolInfo"), arg("Sink", "out", "SinkProtocolInfo"))}
    ${action("GetCurrentConnectionIDs", arg("ConnectionIDs", "out", "CurrentConnectionIDs"))}
    ${action("GetCurrentConnectionInfo",
        arg("ConnectionID", "in", "A_ARG_TYPE_ConnectionID"), arg("RcsID", "out", "A_ARG_TYPE_RcsID"),
        arg("AVTransportID", "out", "A_ARG_TYPE_AVTransportID"), arg("ProtocolInfo", "out", "A_ARG_TYPE_ProtocolInfo"),
        arg("PeerConnectionManager", "out", "A_ARG_TYPE_ConnectionManager"), arg("PeerConnectionID", "out", "A_ARG_TYPE_ConnectionID"),
        arg("Direction", "out", "A_ARG_TYPE_Direction"), arg("Status", "out", "A_ARG_TYPE_ConnectionStatus"))}
  </actionList>
  <serviceStateTable>
    ${sv("SourceProtocolInfo", "string", true)}${sv("SinkProtocolInfo", "string", true)}
    ${sv("CurrentConnectionIDs", "string", true)}${sv("A_ARG_TYPE_ConnectionStatus", "string")}
    ${sv("A_ARG_TYPE_ConnectionManager", "string")}${sv("A_ARG_TYPE_Direction", "string")}
    ${sv("A_ARG_TYPE_ProtocolInfo", "string")}${sv("A_ARG_TYPE_ConnectionID", "i4")}
    ${sv("A_ARG_TYPE_AVTransportID", "i4")}${sv("A_ARG_TYPE_RcsID", "i4")}
  </serviceStateTable>
</scpd>
"""

    fun soapResponse(action: String, serviceType: String, body: String): String = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:${action}Response xmlns:u="$serviceType">$body</u:${action}Response>
  </s:Body>
</s:Envelope>"""

    fun soapFault(code: Int, description: String): String = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <s:Fault>
      <faultcode>s:Client</faultcode>
      <faultstring>UPnPError</faultstring>
      <detail>
        <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
          <errorCode>$code</errorCode>
          <errorDescription>${escape(description)}</errorDescription>
        </UPnPError>
      </detail>
    </s:Fault>
  </s:Body>
</s:Envelope>"""

    /** DIDL-Lite returned from CreateObject: tells the camera where to POST the bytes. */
    fun createObjectDidl(
        objectId: String, parentId: String, mimeType: String, dlnaProfile: String,
        size: Long, importUri: String, upnpClass: String,
    ): String = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:dlna="urn:schemas-dlna-org:metadata-1-0/" xmlns:sec="http://www.sec.co.kr/">
  <item id="$objectId" parentID="$parentId" restricted="0" dlna:dlnaManaged="00000004">
    <dc:title></dc:title>
    <res protocolInfo="http-get:*:$mimeType:$dlnaProfile;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=00D00000000000000000000000000000" importUri="${escape(importUri)}" dlna:resumeUpload="0" dlna:uploadedSize="0" size="$size"></res>
    <upnp:class>${escape(upnpClass)}</upnp:class>
  </item>
</DIDL-Lite>"""
}
