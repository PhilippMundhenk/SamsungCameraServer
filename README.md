# nxbackup — Samsung camera "PC Auto Backup" target: self-hosted server + Android app

Samsung's 2012–2015 Wi-Fi cameras (NX1000/NX1100, NX2000, NX300, WB-series, …) can push every
new photo/video over Wi-Fi to a *PC Auto Backup* server. Samsung's software is dead; this is a
clean reimplementation of the server side, verified against the NX1000 1.15 firmware strings and
the protocol notes in [ge0rg/samsung-nx-hacks](https://github.com/ge0rg/samsung-nx-hacks) and
[n-i-x/pc-autobackup](https://github.com/n-i-x/pc-autobackup).

Two deployment shapes share one protocol core and one camera identity (UDN):

* **Home server** (`server/`, Docker image): always-on target on the LAN, backend for the phone,
  post-processing pipeline (EXIF time shift, RAW → JPEG preview, renaming, export into a synced
  share, webhook / shell hooks), status page with pairing-file download.
* **Android app** (`app/`): backup target on the go (phone hotspot) or at home. Saves to a folder
  on the phone or forwards every file to the home server. Backup only, no other camera features.

```
core/    pure Kotlin/JVM, zero dependencies: SSDP responder, UPnP MediaServer stub, CreateObject/
         upload handling, pairing-file generator, camera simulator, tiny HTTP server, CLI
server/  self-hosted server (Kotlin/JVM): config, ledger, pipeline, backend API, web UI, Docker
app/     Android app: foreground service, direct / backend mode, pairing export, log view
tools/   e2e-jvm.sh — compile core with a standalone kotlinc and run the loopback e2e test
docs/    DESIGN.md (concept, decisions, open questions), CHAT_TRANSCRIPT.txt (the design chat)
```

## How the camera side works (reverse engineered)

* **Pairing:** the camera reads `dlna_web_root/SAMSUNGAutoBackupDESC.ini` from the SD card
  (`AutoBackup::GetPCInfo` in the firmware). Keys, in order, CRLF-terminated:
  `MacAddr=`, `UDN=uuid:…`, `friendlyName=`, `WOLSupport=1`, `ServerFlag=1`. Nothing else is parsed.
  Only the UDN is matched against the SSDP device list; the MAC is just for Wake-on-LAN.
  The UDN is free to choose; we keep Samsung's `4a682b0b-0361-dbae-6155-` prefix for safety.
* **Dedup:** the camera keeps `config/DMC/MSCP/mscp.db` (SQLite, `ObjectTable.IsBackup`) on the
  card and only offers files with `IsBackup != 1`. Per card; formatting resets it. The server
  additionally dedups by date + name + size, so re-sent files are discarded, never overwritten.
* **Session:** SSDP `M-SEARCH` → `GET /DMS/SamsungDmsDesc.xml` → SOAP `X_BACKUP_START`
  → per file `CreateObject` (DIDL with title/date/size) → `POST` bytes to the `importUri`
  → SOAP `X_BACKUP_DONE` (carries `SysOff`, the "shut down PC after backup" flag).
* The same UDN may be advertised by several servers (phone hotspot and home server); the camera
  takes whichever answers on the network it is on.

## Home server

### Run it

```sh
docker compose up -d          # image ghcr.io/philippmundhenk/nxbackup-server:main, host networking
# or without Docker (JDK 17+):
./gradlew :server:installDist && server/build/install/nxbackup-server/bin/nxbackup-server ./data
```

Open `http://<host>:52235/`: status, recent files, pipeline/tool state, log, and the
**pairing zip** download. Unzip it into the SD card root. Repeat after formatting the card.

The data directory (`/data` in the container) holds everything:

| File | Purpose |
|---|---|
| `nxbackup.conf` | configuration, written with comments on first start |
| `SAMSUNGAutoBackupDESC.ini` | the server identity = the pairing file (UDN, name) |
| `received.tsv` | ledger of every file ever received (dedup survives renames/moves) |
| `incoming/<date>/<name>` | uploads (`storage.dir`), unless exported elsewhere |

Every config key can be overridden by an environment variable `NXBACKUP_<KEY>` (dots →
underscores, upper case), which is how `docker-compose.yml` configures it.

**One pairing file for phone and server:** copy the UDN shown in the Android app into
`server.udn` (or `NXBACKUP_SERVER_UDN`). Both targets then answer to the same identity; the camera
uses whichever is on the current network.

**Networking:** the camera finds the server via SSDP multicast (TTL 2), so the container must be
on the camera's L2 segment: `network_mode: host`, or a `macvlan` network on the camera VLAN. If
only the phone forwards to the server (backend mode), plain port mapping of 52235/tcp is enough
and `ssdp.enabled=false` keeps the server off the UPnP radar.

### Pipeline (all optional, see `nxbackup.conf`)

Per file, in order, on a worker thread: **EXIF time shift** (`exif.timeshift=+1:00`, exiftool) →
**RAW preview** (`raw.convert=true`; darktable-cli, rawtherapee-cli or LibRaw's `dcraw_emu`, the
latter is in the image) → **rename** (`rename.pattern={yyyy}{MM}{dd}_{HH}{mm}{ss}_{base}.{ext}`) →
**export** (`export.dir=/photos`, `export.subfolder={yyyy}-{MM}-{dd}`, e.g. a Synology Drive
share). Per session: **webhook** (JSON summary, e.g. Home Assistant), **hook.command** (shell,
file list in `NXBACKUP_FILES`), **sysoff.command** when the camera asks to shut the PC down.
A stage whose tool is missing is skipped with a log line; the status page shows which tools exist.
`docker build --build-arg EXTRA_PACKAGES=darktable .` adds a full RAW developer.

### Backend API (used by the app's backend mode)

```
HEAD {base}/api/v1/files/{date}/{name}                    200 = exists, 404 = not
PUT  {base}/api/v1/files/{date}/{name}   body = raw bytes 201 = stored, 200 = already existed
     headers: Content-Type, Content-Length (or chunked), X-NX-Camera (camera IP), X-NX-Class
POST {base}/api/v1/events  {"type":"start"|"done","camera":"<ip>","sysOff":true|false}  204
GET  {base}/api/v1/status  JSON   ·   GET {base}/api/v1/log   ·   GET {base}/health
```
`{date}` is the camera's `dc:date` (YYYY-MM-DD), `{name}` the original file name (SAM_0001.JPG).
With `api.token` set, `/api/v1/files` and `/api/v1/events` require `Authorization: Bearer <token>`
(the "Backend token" field in the app). Files arriving this way go through the same ledger and
pipeline as files from the camera.

## Android app

1. Install the debug APK (built by the GitHub Actions workflow, artifact `nxbackup-debug-apk`).
2. Pick a mode:
   * **Save to folder** — default is the app's own storage; "Choose folder…" picks any folder via
     the system picker (e.g. one synced by Synology Drive).
   * **Forward to backend** — enter the server base URL (`http://nas.lan:52235`) and, if set on
     the server, the token.
3. Optionally set the name shown on the camera; the UDN is generated once and kept. To share the
   pairing file with the home server, put this UDN into the server's `server.udn`.
4. **Export pairing file** → saves `SAMSUNGAutoBackupDESC.zip`; unzip into the SD card root.
   Do this again after formatting the card.
5. **Start server**. On the camera: Wi-Fi mode → *Auto Backup* (or the Smart Link button mapped
   to it), connect to the phone's hotspot / the same Wi-Fi. The camera lists the server by its
   name and uploads everything not yet backed up.

Phone and camera must be on the same L2 network (SSDP multicast). Hotspot works.

## Build & test

* `./gradlew :core:test` — unit tests + loopback end-to-end (server ↔ simulated camera).
* `./gradlew :server:test` — camera session, phone API contract (auth, chunked upload),
  pipeline (rename/export/webhook), identity persistence, PPM→JPEG.
* `./gradlew :app:assembleDebug` — APK. `./gradlew :app:connectedDebugAndroidTest` — Espresso UI
  e2e on a device/emulator: starts the service through the UI, backs up from a simulated camera,
  verifies bytes, log, dedup, stop.
* `./gradlew :server:installDist` — runnable server in `server/build/install/nxbackup-server/`.
* `docker build -t nxbackup-server .` — container image (no Android SDK needed).
  `tools/container-smoke.sh` builds it and pushes a JPEG through the phone API with time shift,
  EXIF-based rename, export into a second volume, token auth and dedup switched on.
* `tools/e2e-jvm.sh [--ssdp]` — loopback e2e without Gradle; `--ssdp` also exercises multicast.
* Requires JDK 17+ to run Gradle (any newer JDK works; bytecode targets 17). For the Android
  module, `local.properties` needs `sdk.dir=…` or `ANDROID_HOME` must be set.

CI (`.github/workflows/android.yml`) runs core + server tests, builds the APK and the server
zip, runs the Espresso test on an emulator, and publishes the image to
`ghcr.io/<owner>/nxbackup-server` on every push.

## Status / known gaps

* Not yet tested against a real camera — the simulator is built from the firmware strings and
  pc-autobackup packet notes. First real run: watch the log; everything the camera does is logged.
* "File exists" UPnP error code for `CreateObject` is unknown; the server accepts and discards
  duplicates instead (`BackupServer.fileExistsErrorCode`).
* Resumable uploads (`dlna:resumeUpload`) are announced as unsupported; the camera restarts a file
  after a dropped connection.
* RAW conversion and EXIF stages are exercised only where the tools are installed (the Docker
  image has exiftool and dcraw_emu); the unit tests cover the fallbacks.
