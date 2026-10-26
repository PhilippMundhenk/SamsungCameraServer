# nxbackup — design and continuation notes

Status date: 2026-10-26 (evening: server module added, Android app compiled and UI-tested on an
emulator). Author: Philipp Mundhenk with Claude. Working name "nxbackup" (not checked for
trademark/domain collisions — rename before publishing). Repository:
https://github.com/PhilippMundhenk/SamsungCameraServer

## 1. Goal

Get full-resolution photos and videos off Samsung Wi-Fi cameras (first target: NX1000,
firmware 1.15) over the camera's built-in Wi-Fi, now that Samsung's apps and cloud services
are gone. Two deployment shapes share one protocol core:

* **Android app** (this repo, built first): backup target on the phone. Used on the go via the
  phone's hotspot, or at home on the same Wi-Fi. Backup only — no remote viewfinder, no
  MobileLink, no social features.
* **Home server** (`server/`, built second): always-on container on the homelab (Proxmox /
  Synology), same protocol, plus the post-processing pipeline (RAW conversion, time offset,
  renaming, Home Assistant hook, drop into the Synology Drive share that photosort watches),
  the backend API for the phone, and a status page with the pairing-file download.

Both advertise the **same server identity (UDN)**, so one pairing file on the SD card works
for both; the camera talks to whichever answers on the network it is currently on.

## 2. What the camera does (reverse-engineered facts)

Sources: NX1000 firmware 1.15 strings (`nx1000.bin`), n-i-x/pc-autobackup (GPLv2, used as a
protocol reference only — no code copied), ge0rg/samsung-nx-hacks wiki.

### 2.1 Pairing file — `dlna_web_root/SAMSUNGAutoBackupDESC.ini` on the SD card
Parsed by `AutoBackup::GetPCInfo`. Exactly these keys, CRLF line endings:

```
MacAddr=02:4E:58:42:4B:50          required; only used for the Wake-on-LAN packet
UDN=uuid:4a682b0b-0361-dbae-6155-xxxxxxxxxxxx   required; THE match key
friendlyName=[PC]AutoBackup        required; shown on the camera
WOLSupport=1                       constant
ServerFlag=1                       constant
```
* No other keys exist in the firmware. No camera serial involved. UDN is free; the
  `4a682b0b-0361-dbae-6155-` prefix is what Samsung's tool used and is kept as insurance.
* Path casing differs per camera family only because Samsung's Windows tool wrote it that way;
  FAT is case-insensitive. The firmware's own string is
  `/ext/sd0/dlna_web_root/SAMSUNGAutoBackupDESC.ini`.
* An in-camera format deletes it → re-export from the app.

### 2.2 Dedup state — `config/DMC/MSCP/mscp.db` on the SD card
SQLite. `ObjectTable(class, date, parentID, FullPath, Valid, BackupID, IsBackup)`.
Backup candidates: `select FullPath from ObjectTable where class != 0 and IsBackup != 1`.
The camera marks `IsBackup` after a successful upload. Per card, reset by format. Consequence:
a file delivered to the phone will never be offered to the home server → sync-back required
(Synology Drive on the phone, or app backend mode).

### 2.3 Session (camera is always the client)
```
SSDP M-SEARCH (ST MediaServer:1)  → server replies with LOCATION + USN uuid:<UDN>
GET  /DMS/SamsungDmsDesc.xml      → UPnP device description, <UDN> must match
POST /upnp/control/ContentDirectory1  SOAPACTION X_BACKUP_START {UDN, FileCnt, SysOff}
per file:
  POST …ContentDirectory1  CreateObject {ContainerID=DLNA.ORG_AnyContainer, Elements=DIDL}
       DIDL carries dc:title (SAM_0001.JPG), dc:date (YYYY-MM-DD), upnp:class,
       res@protocolInfo (*:*:image/jpeg:…), res@size
       ← response DIDL with res@importUri
  POST <importUri>  (raw bytes, Content-Length = size)  ← 200
POST …ContentDirectory1  X_BACKUP_DONE {UDN, SysOff}
```
Other actions the firmware can issue (all answered generically): Browse, DestroyObject,
GetTransferProgress, StopTransferResource, ConnectionManager GetProtocolInfo.
Firmware details worth remembering:
* `SysOff` = camera's "shut down PC after backup" option; we expose it as a sink callback.
* "File Exist!!! CreateObject Return %d": the camera treats some UPnP error from CreateObject
  as "already backed up" and moves on. The exact code is unknown → `fileExistsErrorCode`
  option (default off: accept and discard duplicates instead). Capture a real session to
  find it (candidates: 701, 712, 720).
* Resume support exists (`ResumeUpload`, `UploadedSize`, start offset). We advertise
  `dlna:resumeUpload="0"`; could be implemented later for flaky hotspot links.
* Camera UPnP ports (from its UPnPConfig.xml): web 5215, UPnP 5216, HTTP 7676; SSDP TTL 2.
* Camera uploads full originals (JPEG, SRW, MP4). Only the cloud/email paths downscale.

## 3. Architecture

```
core/  (Kotlin, JVM, zero dependencies — runs on Android and on servers/CI)
  ServerIdentity   udn/name/mac + generator (Samsung prefix)
  PairingFile      render / parse / zip of the SD-card file
  UpnpXml          device description, SCPDs, SOAP envelopes, CreateObject DIDL
  HttpServer       tiny HTTP/1.1 server; streams request bodies; Connection: close
  SsdpResponder    multicast listener, M-SEARCH replies, periodic NOTIFY alive/byebye
  BackupServer     routing + SOAP action handling; stats; pending-object table
  BackupSink       interface: onBackupStart / exists / store / onBackupDone
  FileSystemSink   <root>/<date>/<name>, dedup by name+size, .part + rename
  CameraSimulator  acts like the camera (used by JVM e2e, Espresso e2e, manual tests)
  Main             CLI: serve <dir> | pairing <name> | e2e [--ssdp]
server/ (Kotlin/JVM, depends only on core; Docker image eclipse-temurin 17 + exiftool + libraw)
  Config           <data>/nxbackup.conf (properties, commented defaults written on first start)
                   + env overrides NXBACKUP_<KEY>
  Main             NxBackupServer.start(dataDir): wires everything; identity lives in
                   <data>/SAMSUNGAutoBackupDESC.ini (the pairing file itself), overridable by
                   server.udn / server.name so phone and server share one UDN
  Ledger           <data>/received.tsv — every file ever received (date, name, size, current
                   path); dedup source of truth, survives renames and exports
  ServerSink       BackupSink for camera + phone uploads: .part write, ledger, per-camera
                   Session, hands files to the pipeline
  Pipeline         single worker thread; per file: exiftool time shift -> RAW preview
                   (darktable-cli | rawtherapee-cli | dcraw_emu + built-in PPM->JPEG) ->
                   rename pattern -> export/move; per session: webhook, hook.command,
                   sysoff.command
  Api              fallback HttpHandler on the same port: /api/v1/files (HEAD/PUT),
                   /api/v1/events, /api/v1/status, /api/v1/log, /health, status page at /,
                   /pairing.zip; bearer token (api.token) protects the phone endpoints
  Tools            runs external programs, probes availability once (shown on status page)
app/   (Android, minSdk 26, Views + Material 3, ViewBinding)
  MainActivity     mode, folder picker (SAF), backend URL, identity, pairing export, log
  BackupService    foreground service (dataSync); MulticastLock + WifiLock + WakeLock
  Sinks            SafSink (DocumentFile tree), BackendSink (HTTP PUT), default app folder
  Prefs            SharedPreferences; UDN generated once and persisted
  LogBus           in-process log ring buffer feeding the UI
```

### Design decisions
* **Pure-JVM core, no frameworks.** The server must run on Android (no Netty/Twisted
  friendliness), in a Docker container, and in CI without downloads. Plain sockets keep it
  portable and make the camera's non-compliant HTTP easy to tolerate.
* **Thread-per-connection, Connection: close.** The camera opens few connections; simplicity
  over throughput. Upload bodies are streamed (never buffered in memory) — SRW files are 20–40 MB.
* **Dedup in the sink, not the camera.** The camera already tracks state, but card formats,
  phone/home splits and retries make server-side name+size dedup cheap insurance.
  Duplicates are drained and discarded, never overwritten.
* **Identity in preferences, generated once.** Changing the UDN invalidates every pairing file
  already on cards, so the UI makes regeneration an explicit button and locks identity while
  the server runs.
* **Pairing export as a zip** containing the file at its card path — unzip-to-root is
  foolproof on any OS and on phones with a card reader.
* **Default folder = app external files dir.** Works without any permission or picker, lets
  the Espresso test run unattended. SAF tree for user-chosen folders (e.g. a Synology Drive
  synced folder).
* **Backend mode streams, doesn't store.** Phone keeps nothing; the home server dedups.
  Simple HTTP PUT so any backend language works. Optional bearer token (`api.token` on the
  server, "Backend token" in the app); without it the API is open — LAN only.
* **Server reuses core verbatim.** The server is `BackupServer` + a fallback `HttpHandler` on the
  same port, so camera protocol, phone API and web UI share one listener (one port to open, one
  Docker mapping). SSDP can be switched off for a phone-only backend behind port mapping.
* **Ledger instead of directory scan.** The pipeline renames and moves files, so "already have
  it" is answered from `received.tsv` keyed by camera date + name (+ size). Append-only, last
  line wins; a human can read and edit it.
* **Identity file = pairing file.** `<data>/SAMSUNGAutoBackupDESC.ini` is both the persisted
  identity and what the UI serves as download; no second source of truth.
* **Pipeline on one worker thread, stages are optional.** Uploads are never blocked by RAW
  conversion; every stage is skipped with a log line when unset or when its tool is missing.
  dcraw_emu output (PPM) is converted to JPEG with ImageIO, so the image has no ImageMagick.
* **Foreground service with locks.** SSDP needs `MulticastLock` on Android; Wi-Fi power
  saving would otherwise drop multicast and stall uploads mid-session.
* **Espresso test drives the real UI** and uses `CameraSimulator` as the counterpart, so the
  UI, service, locks and sink are exercised together on an emulator.

## 4. App features and options

| Option | Where | Notes |
|---|---|---|
| Mode: direct / backend | main screen | locked while server runs |
| Folder (direct) | "Choose folder…" (SAF) | default: `Android/data/org.mundhenk.nxbackup/files/NXBackup` |
| Backend URL (backend) | text field | base URL, e.g. `http://nas.lan:52235` |
| Backend token (backend) | password field | optional; must equal the server's `api.token` |
| Server name | text field | shown on camera; default `[PC]AutoBackup` |
| UDN | text field + "New UDN" | must keep Samsung prefix; regenerating requires re-pairing |
| Export pairing file | button | `SAMSUNGAutoBackupDESC.zip` via system file picker |
| Start / Stop | button / notification action | foreground service |
| Log | text view | last 400 lines, also in logcat tag `NXBackup` |

Hard-coded for now (make configurable if needed): HTTP port 52235, `fileExistsErrorCode`
(off), SSDP notify interval 30 s, date-subfolder layout.

## 5. Backend API contract (phone → home server)

```
HEAD {base}/api/v1/files/{date}/{name}                  200 exists | 404
PUT  {base}/api/v1/files/{date}/{name}   raw bytes      201 stored | 200 already existed
     headers: Content-Type, Content-Length, X-NX-Camera, X-NX-Class
POST {base}/api/v1/events  {"type":"start"|"done","camera":"<ip>","sysOff":bool}
GET  {base}/api/v1/status | /api/v1/log | /health
```
Implemented in `server/Api.kt`; `Authorization: Bearer <api.token>` when configured. Chunked
PUT bodies are accepted (the app uses them when the camera sent no size).

## 6. Home server (implemented in `server/`)

* Kotlin/JVM on the tested `core`; no framework. `docker-compose.yml` with `network_mode: host`;
  `macvlan` on the camera VLAN is the alternative (SSDP TTL 2). Image published by CI to
  `ghcr.io/<owner>/nxbackup-server`.
* Config keys (all in `nxbackup.conf`, env `NXBACKUP_<KEY>`): `http.port`, `server.name`,
  `server.udn`, `ssdp.enabled`, `storage.dir`, `api.token`, `ui.enabled`, `exif.timeshift`,
  `exif.tool`, `raw.convert`, `raw.converter` (auto|darktable|rawtherapee|dcraw|none),
  `raw.onlyifnojpeg`, `raw.quality`, `rename.pattern`, `export.dir`, `export.subfolder`,
  `webhook.url`, `webhook.token`, `hook.command`, `sysoff.command`.
* Rename / export tokens: `{yyyy} {MM} {dd} {HH} {mm} {ss}` from EXIF DateTimeOriginal (after the
  time shift) via exiftool, falling back to the camera's `dc:date` + 00:00:00; `{base} {ext}
  {name} {camera}`. Collisions get `_1`, `_2`, …; nothing is ever overwritten. RAW+JPEG pairs end
  up with the same base name because both derive from the same original name.
* Webhook JSON: `{"event":"done","camera","sysOff","stored","skipped","durationSec",
  "files":[{"name","path","size","preview"?,"error"?}]}`.
* Not done / ideas: per-camera time offsets (needs a stable camera id — the model from EXIF
  could key it), `mscp.db` reset tool, editing the config from the web UI, resumable uploads,
  HTTPS (put a reverse proxy in front for the phone API; the camera path must stay plain HTTP).

## 7. Open questions / first real-camera checklist

1. Does the camera show a one-time "connect to this PC?" prompt? (unknown, harmless)
2. Exact UPnP error code the camera treats as "file exists".
3. Does the camera call `Browse` before `X_BACKUP_START` and accept our empty result?
4. Does the camera require the device description's `SERVER:` / `modelName` values, or is the
   UDN enough? (We mirror pc-autobackup's values to be safe.)
5. Multiple saved Wi-Fi networks on the camera — confirm the remembered-network list.
6. Hotspot: Android's hotspot interface must deliver multicast to the app — verify on the
   target phone (known to work for DLNA servers, but vendor ROMs differ).
7. Behaviour after a dropped upload: camera retries the file, or marks it done? Decides whether
   resume support is worth implementing.
Run the first session with the app log (or the server status page) visible; every camera
request is logged. Checked against the NX1100 manual (p. 117 f.): the camera stores the PC
information itself (our pairing file), backs up only new files, can shut the PC down afterwards
(`SysOff`), turns itself off ~30 s after the transfer, and needs the PC firewall open — i.e.
52235/tcp and 1900/udp.

## 8. Build / test / CI

* `tools/e2e-jvm.sh [--ssdp]` — compiles `core` with a standalone Kotlin compiler and runs the
  loopback end-to-end (used in the original session where Gradle had no network).
* `./gradlew :core:test` — JUnit + the same e2e. `./gradlew :server:test` — six server tests:
  camera session + dedup + restart, phone API contract incl. 401 and chunked PUT, pipeline
  rename/export/webhook, identity overrides, PPM→JPEG, JSON helpers.
* `./gradlew :app:assembleDebug` — APK; `:app:connectedDebugAndroidTest` — Espresso e2e.
  Both verified locally on 2026-10-26 (JBR 21, AGP 8.7.3, emulator API 37). Espresso 3.7.0 /
  androidx.test 1.7.0 are required on API 36+ (3.6.1 crashes in InputManager). On a slow
  software-rendered emulator the service stop can take >15 s to be delivered, hence the 45 s
  waits in the test.
* Gradle: no JDK toolchain pin; the build runs on any JDK 17+ and emits Java 17 bytecode.
  `settings.gradle.kts` includes `:app` only when the directory exists and
  `NXBACKUP_SERVER_ONLY` is unset, so the Docker build context (core + server) needs no SDK.
* `.github/workflows/android.yml` — core + server tests, APK artifact `nxbackup-debug-apk`,
  server zip artifact `nxbackup-server`, emulator UI tests (API 34), Docker image to GHCR.
* `tools/container-smoke.sh` — builds the image and runs the phone API against the real container
  with exiftool + export across two volumes (this is what found the cross-filesystem move bug;
  JUnit cannot reproduce separate mounts).

## 9. Related, intentionally out of scope

Email/SNS revival (ge0rg/samsung-nx-emailservice + DNS override on OPNsense), MobileLink,
Remote Viewfinder, TV Link. All documented in `docs/CHAT_TRANSCRIPT.txt`.
