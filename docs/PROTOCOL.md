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
  treats as "file exists" is unknown; see [issue #6](https://github.com/PhilippMundhenk/SamsungCameraServer/issues/6)).
* Resumable uploads: the camera asks for them (`dlna:resumeUpload="1"` in its CreateObject
  DIDL) and both targets now answer the same way. A cut-off upload is kept in a spool
  (`<data>/spool` on the server, the app's private storage on the phone); a Browse of the
  object reports `dlna:uploadedSize`, and a POST with `Content-Range: bytes <from>-…`
  continues from there. Whether the NX1000 really sends that header on resume is not
  confirmed yet ([issue #18](https://github.com/PhilippMundhenk/SamsungCameraServer/issues/18));
  a POST without it starts the file over, which is what happened before as well.
* Networking: SSDP is multicast with TTL 2, so the target must be on the camera's L2 segment
  (same Wi-Fi, the phone's hotspot, or host/macvlan networking for the container). Ports:
  52235/tcp, 1900/udp.
