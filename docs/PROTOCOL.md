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

## A backup session

The camera is always the client:

```
SSDP M-SEARCH (ST MediaServer:1)      → we answer with LOCATION + USN uuid:<UDN>
GET  /DMS/SamsungDmsDesc.xml          → UPnP device description, <UDN> must match
POST ContentDirectory  X_BACKUP_START {UDN, FileCnt, SysOff}
per file:
  POST ContentDirectory  CreateObject   DIDL with dc:title, dc:date, upnp:class, res@size
                                        ← DIDL with res@importUri
  POST <importUri>                      raw bytes, Content-Length = size  ← 200
POST ContentDirectory  X_BACKUP_DONE  {UDN, SysOff}
```

* `SysOff` is the camera's "shut down the PC after the backup" option; the home server can run
  a command on it (`sysoff.command`).
* The camera also issues `Browse`, `GetProtocolInfo`, `GetTransferProgress`, `DestroyObject`,
  `StopTransferResource`; all are answered generically.
* Duplicates: the server accepts and discards them (the exact UPnP error code the firmware
  treats as "file exists" is unknown; see [issue #6](https://github.com/PhilippMundhenk/SamsungCameraServer/issues/6)).
* Resumable uploads exist in the firmware (`dlna:resumeUpload`) but are announced as
  unsupported; after a dropped connection the camera restarts the file.
* Networking: SSDP is multicast with TTL 2, so the target must be on the camera's L2 segment
  (same Wi-Fi, the phone's hotspot, or host/macvlan networking for the container). Ports:
  52235/tcp, 1900/udp.
