# How the camera side works (reverse engineered)

Everything here was derived from the NX1000 firmware 1.15 strings, the NX1100 manual and the
protocol notes in [ge0rg/samsung-nx-hacks](https://github.com/ge0rg/samsung-nx-hacks) and
[n-i-x/pc-autobackup](https://github.com/n-i-x/pc-autobackup) (used as references only, no code
copied). The full write-up with firmware symbols is in [DESIGN.md](DESIGN.md), section 2.

## Pairing file on the SD card

The camera reads `dlna_web_root/SAMSUNGAutoBackupDESC.ini` from the card
(`AutoBackup::GetPCInfo` in the firmware). Keys, in this order, CRLF line endings:

```
MacAddr=02:4E:58:42:4B:50                        only used for the Wake-on-LAN packet
UDN=uuid:4a682b0b-0361-dbae-6155-xxxxxxxxxxxx    the one value the camera matches
friendlyName=[PC]AutoBackup                      shown on the camera
WOLSupport=1
ServerFlag=1
```

* Nothing else is parsed. The UDN is free to choose; the `4a682b0b-0361-dbae-6155-` prefix is
  what Samsung's tool used and is kept for safety.
* The app and the home server both export this file (zip with the file at its card path, unzip
  into the card root). An in-camera format deletes it, so export again afterwards.
* Several targets may advertise the **same UDN**: the camera takes whichever answers on the
  network it is on. That is how one card works with the phone (hotspot) and the home server.

## What the camera remembers

`config/DMC/MSCP/mscp.db` on the card (SQLite, `ObjectTable.IsBackup`) marks every file that
was backed up. The camera only offers files with `IsBackup != 1`; formatting the card resets it.
Consequence: a file delivered to the phone is never offered to the home server again, which is
why the app has the "phone + home server" mode (upload later) and why both targets dedup by
date + name + size on their own.

### Per file: the `BackupID` and the GetObject step (firmware 1.15)

`ObjectTable` also has a `BackupID` column. The firmware's per-file state machine
(`E_NAPI_PCBACKUP_SEND_ONE` → `SEND_ONE_GET_OBJECT` → `SEND_ONE_FILE_UPLOAD`) works like this:

1. A file with `IsBackup = 1` is skipped ("Already Uploaded File").
2. A file **without** a `BackupID` gets `CreateObject` (ContainerID `DLNA.ORG_AnyContainer`);
   the ObjectID the server answers with is stored as the file's `BackupID`.
3. A file **with** a `BackupID` (a CreateObject happened but the upload never finished, e.g. the
   session hit the 1,000-file cap right after the CreateObject) first gets
   `Browse(ObjectID = BackupID, BrowseMetadata)` — the firmware's "GetObject". Two answers work:
   * the item, with `importUri`, `dlna:uploadedSize` and `dlna:resumeUpload`: the camera
     continues the upload from `uploadedSize` ("SendReady Upload path[…] Startoffset[…]");
   * UPnP error **701 No such object**: the camera does a fresh `CreateObject`
     ("No Such Exist → CreateObject Retry").
   An HTTP 200 with an empty `Result` is neither and the camera waits forever on its transfer
   screen (seen on the NX1000; fixed in 0.2.0).
4. After a successful upload `IsBackup` is set. An upload stopped on the camera arrives short
   (fewer bytes than `res@size`); the server must drop the fragment, the camera keeps the
   `BackupID` and goes through step 3 for that file next time.

Consequence for several targets: **no synchronisation between the phone and the home server is
needed.** The only state that crosses sessions is per file on the card (`IsBackup`,
`BackupID`); a target that does not know a `BackupID` answers 701 and the camera simply
re-creates the object there. One file is at most re-sent once (the one that was in flight when
the previous session ended), and both targets discard duplicates by date + name + size.

## Session size: 1,000 files, newest first

The manual (NX1100, p. 118) states "You can send up to 1,000 files" per Auto Backup run. The
firmware builds the list with `select FullPath from ObjectTable where class != 0 and IsBackup
!= 1 order by FullPath DESC`, so a session takes the **newest** files first and announces the
count in `X_BACKUP_START` (`NumOfContents`). Every uploaded file is marked `IsBackup = 1`, so running
Auto Backup again continues with the remaining, older files; nothing is lost, it just takes
several runs for a card with more than a thousand new files.

## A backup session

The camera is always the client:

```
SSDP M-SEARCH (ST MediaServer:1)      → we answer with LOCATION + USN uuid:<UDN>
GET  /DMS/SamsungDmsDesc.xml          → UPnP device description, <UDN> must match
POST ContentDirectory  X_BACKUP_START {UDN, NumOfContents, SystemOff}
POST ContentDirectory  Browse         BrowseMetadata of the last object of the previous session
                                        ← UPnP error 701 (gone), or the item if it still waits for its upload
per file:
  POST ContentDirectory  CreateObject   DIDL with dc:title, dc:date, upnp:class, res@size
                                        ← DIDL with res@importUri
  POST <importUri>                      raw bytes, Content-Length = size  ← 200
POST ContentDirectory  X_BACKUP_DONE  {UDN, SystemOff}
```

* `SystemOff` is the camera's "shut down the PC after the backup" option; the home server can run
  a command on it (`sysoff.command`).
* `Browse`: on its second session the NX1000 first asks about the last object id of the previous
  one (`UP_2021-06-07_7606e6afaf`, the oldest file the 1,000-file cap had let through) and does
  not continue on an empty result. The server keeps a tiny tree: root `0` with one folder `UP`
  ("Auto Backup"), every `parentID` handed out in CreateObject (`UP_<date>`) is that folder, an
  object still waiting for its upload is returned as the item it was created as, anything else
  is UPnP error 701 "No such object". `GetProtocolInfo`, `GetTransferProgress`, `DestroyObject`,
  `StopTransferResource` are answered generically.
* Duplicates: the server accepts and discards them (the exact UPnP error code the firmware
  treats as "file exists" is unknown; see the open questions in [DESIGN.md](DESIGN.md)).
* Resumable uploads: the camera asks for them (`dlna:resumeUpload="1"` in its CreateObject
  DIDL) and both targets now answer the same way. A cut-off upload is kept in a spool
  (`<data>/spool` on the server, the app's private storage on the phone); a Browse of the
  object reports `dlna:uploadedSize`, and a POST with `Content-Range: bytes <from>-…`
  continues from there. Whether the NX1000 really sends that header on resume is not
  confirmed yet: in the one interrupted session seen, it created the file again and sent it in
  full. A POST without the header starts the file over, which the server handles.
* Networking: SSDP is multicast with TTL 2, so the target must be on the camera's L2 segment
  (same Wi-Fi, the phone's hotspot, or host/macvlan networking for the container). Ports:
  52235/tcp, 1900/udp.

## Remote Viewfinder

The cameras' other Wi-Fi mode: live view on the phone, shutter from the phone. Here the camera is
the **server** and the access point; the phone is the client. Reimplemented from published notes
and from what Samsung's own Android apps do. Both apps were decompiled for reference only, and no
code was copied:
* *Remote Viewfinder* 2.2.11 (`com.samsung.app`), the app for the 2012 models;
* *SMART CAMERA App* 1.4.0 (`com.samsungimaging.connectionmanager`), its successor.

The published notes:
[ge0rg/samsung-nx-hacks wiki](https://github.com/ge0rg/samsung-nx-hacks/wiki/Remote-Viewfinder)
(plus `xml/Remote-ViewFinder/smp_2_.xml` and `smp_3_.xml`, captured from an NX3000), the comments on
[op-co.de "Hacking the NX300"](https://op-co.de/blog/posts/hacking_the_nx300/) (NX300, NX300M,
NX3000, and NX1000 firmware 1.15), and
[RuiJCS/samsung-s150f-reverse-engineer](https://github.com/RuiJCS/samsung-s150f-reverse-engineer).

```
camera: Wi-Fi -> Remote Viewfinder   opens AP "AP_SSC_<MODEL>_0-xx:xx:xx" (NX1000 fw 1.10: no AP_SSC_
                                     prefix), is DHCP server + gateway 192.168.102.1 (.107 on some NX)
phone joins, then (one request per connection, HTTP/1.0, Connection: close, one at a time):
HEAD :7788/mode/control              optional knock (SMART CAMERA App, every model); MVERSION = newer model
GET  :7676/smp_2_   User-Agent: SEC_RVF_<id> (+ Access-Method: manual)   device description
                    first time: the camera asks "allow?"; 401 = refused, 503 = another phone is connected
GET  :7676/smp_3_   ContentDirectory SCPD: the actions this model offers       (no pairing header)
POST :7676/smp_4_   SOAPACTION: "urn:schemas-upnp-org:service:ContentDirectory:1#GetInfomation"
                    <- capabilities (Flash/Supports, ZoomInfo, AVAILSHOTS, StreamQuality, Version) and
                       StreamUrl/QualityHighUrl  http://<camera>:7679/livestream.avi (640x480)
                                 QualityLowUrl   http://<camera>:7679/qvga_livestream.avi
                       or, on the 2012 firmware, GETIPRESULT = the stream URL
GET  :7679/livestream.avi             <- 206, endless RIFF/AVI, frames in 00dc/00db chunks
first frame: MULTIAF, then ReleaseSelfTimer(RELEASETIMER=0)                -> "connected"
shutter:     MULTIAF (or AF)          <- AFSTATUS: AFFAIL/0 = no focus, else the AF frames ("1x3,")
             Shot                     <- AFSHOTRESULT = URL of the new JPEG, AVAILSHOTS; the stream ends
GET  <AFSHOTRESULT>_SM                the small copy (no Version / Version < 1.0, e.g. NX1000)
GET  <AFSHOTRESULT>                   the original (Version >= 1.0)
GET  :7679/livestream.avi             live view again
POST /smp_4_ ZoomIN / ZoomOUT         <- CURRENTZOOM     only where the SCPD declares them
```

* **Identity.** The pairing User-Agent goes on the description request only. Both apps send nothing
  of the kind on the SCPD, the SOAP calls, the stream or the downloads; on the NX1000 a repeated
  pairing header is reported to bring the "allow?" prompt back.
  * It is `SEC_RVF_` plus a stable id: the 2012 app uses the phone number, else the Wi-Fi MAC
    without colons. The camera remembers ids it allowed.
  * This app uses a random locally administered MAC (`02:…`), generated once per installation.
  * NX3000 / NX mini want `SEC_RVF_ML_`. The client takes that when `/mode/control` answers with
    `MVERSION`, or after a 401 to the plain prefix.
* **Only declared actions, every declared argument.** The SCPD differs per model, and an
  undeclared action or argument hangs some cameras. `GetInfomation` with `GPSINFO`, for example,
  is a fault on the 2013 compacts but required on the NX3000. Samsung's UPnP stack sends every
  declared input argument and leaves unset ones empty; so does `RvfClient`.
  * The 2012 app connects only if the SCPD has GetInfomation, SetResolution, ZoomIN, ZoomOUT,
    MULTIAF, Shot, ShotWithGPS, SetLED and SetFlash. That is the NX1000 profile of the simulator.
  * The NX1000 / NX1100 declare zoom but zoom only with the lens ring; Samsung's apps hide zoom
    there, and so does this one.
* **Order and timing (NX1000).**
  * Strictly one command at a time.
  * Open the live stream right after `GetInfomation`: until it is read the camera's buttons are
    dead, and MULTIAF / Shot only work while it is being read.
  * The camera drops its Wi-Fi after some idle time.
* **Shutter.** MULTIAF (AF on the Galaxy cameras), then Shot. On NX models a failed focus
  (`AFFAIL`) cancels the shot: `ReleaseSelfTimer(0)` instead, like the camera itself does. UPnP
  error 803 on Shot means "capturing failed, try again"; `AVAILSHOTS` 0 means the card is full.
* **Live stream.**
  * The camera writes `0` for every RIFF/LIST length, so a reader must enter LISTs instead of
    skipping them and trust only leaf chunk sizes.
  * Stills mode is H.264 (Annex B, SPS/PPS in band, 29.97 fps from `strh` 1001/30000); movie mode
    on some models is MJPEG.
  * Only one reader at a time. The camera **ends the stream for every Shot**; Samsung's apps fetch
    the picture and then open the stream again, and so does this one (retrying for up to a minute,
    since a dropped stream can leave the port dead that long).
* **Pictures.** `Shot` stores the picture on the card as usual, so Auto Backup still picks it up.
  * Where `Shot` declares `REQUESTIMAGE`, `1` asks the camera to offer it for download.
  * Cameras without `<Version>` ≥ 1.0 in GetInfomation, the NX1000 among them, only hand out
    `<AFSHOTRESULT>_SM`, a small copy; that is all Samsung's apps fetch from them. This explains
    the old report that "the download did not work" on the NX1000.
  * The app saves that copy as `<name>_preview.JPG`; the original arrives with the next Auto Backup.
* **Keep-alive and end.**
  * Neither app sends HeartBeat or any periodic call, and neither says goodbye to these models:
    closing the stream (and leaving the Wi-Fi) ends the session.
  * `HeartBeat(Duration=30)` is sent only where the SCPD declares it (NX3000). GENA events
    (`/smp_5_`) are unreliable and not used.
* **Which Samsung app served which camera.**
  * The 2012 app pairs only with SSIDs **without** `AP_SSC_`, i.e. the old firmware naming (NX1000
    1.10). The SMART CAMERA App refuses SSIDs that *start with* a 2012 model name, so it takes
    `AP_SSC_NX1000_…` (firmware 1.15).
  * Neither app installs or runs on a current phone: the 2012 app targets API 8 and both bring
    32-bit-only video players.

Open: whether the 2012 compacts (WB150F, WB850F, ST200F, DV300F, EX2F) behave exactly like the
NX1000 (the 2012 app treats them the same), and the NX1000 firmware 1.10 stream format (the 2012
app also accepts `.mp4` stream URLs; this app reads AVI only).
