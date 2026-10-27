# Samsung Camera Backup — "PC Auto Backup" target: self-hosted server + Android app

Samsung's 2012–2015 Wi-Fi cameras (NX1000/NX1100, NX2000, NX300, WB-series, …) can push every
new photo/video over Wi-Fi to a *PC Auto Backup* server. Samsung's software is dead; this is a
clean reimplementation of the server side, verified against the NX1000 1.15 firmware strings and
the protocol notes in [ge0rg/samsung-nx-hacks](https://github.com/ge0rg/samsung-nx-hacks) and
[n-i-x/pc-autobackup](https://github.com/n-i-x/pc-autobackup).

> **Not a Samsung product.** Samsung Camera Backup is an independent open-source project with no
> relation to, endorsement by or support from Samsung Electronics. "Samsung" and the camera model
> names appear only to say which cameras this works with. Short/code name: `cambackup`.

Two deployment shapes share one protocol core and one camera identity (UDN):

* **Home server** (`server/`, Docker image `ghcr.io/philippmundhenk/cambackup-server`): always-on
  target on the LAN, backend for the phone, post-processing pipeline (EXIF time shift, RAW → JPEG
  preview, renaming, export into a synced share, webhook / shell hooks), status page with
  pairing-file download.
* **Android app** (`app/`, APK on the [releases page](https://github.com/PhilippMundhenk/SamsungCameraServer/releases)):
  backup target on the go (phone hotspot) or at home. Keeps photos on the phone, forwards them to
  the home server, or both (store on the phone, upload when the server is reachable). The main
  screen lists every transferred photo with ✓ (on the phone) / ✓✓ (on the server).

```
core/    pure Kotlin/JVM, zero dependencies: SSDP responder, UPnP MediaServer stub, CreateObject/
         upload handling, pairing-file generator, camera simulator, tiny HTTP server, CLI,
         BackendSink (phone -> home server HTTP client)
server/  self-hosted server (Kotlin/JVM): config, ledger, pipeline, backend API, web UI, Docker
app/     Android app: setup wizard, main screen with transfer list, settings, log, foreground service
tools/   e2e-jvm.sh (loopback e2e without Gradle), container-smoke.sh (real image + pipeline)
docs/    DESIGN.md (concept, decisions, open questions), BRANDING.md (names, colours, components
         shared by app and web UI), CHAT_TRANSCRIPT.txt (the design chat)
```

Work is tracked in [GitHub issues](https://github.com/PhilippMundhenk/SamsungCameraServer/issues).

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
docker compose up -d          # image ghcr.io/philippmundhenk/cambackup-server:main, host networking
# or without Docker (JDK 17+):
./gradlew :server:installDist && server/build/install/cambackup-server/bin/cambackup-server ./data
```

Open `http://<host>:52235/`: status, recent files, pipeline/tool state, log, and the
**pairing zip** download. Unzip it into the SD card root. Repeat after formatting the card.

The data directory (`/data` in the container) holds everything:

| File | Purpose |
|---|---|
| `cambackup.conf` | configuration, written with comments on first start |
| `SAMSUNGAutoBackupDESC.ini` | the server identity = the pairing file (UDN, name) |
| `received.tsv` | ledger of every file ever received (dedup survives renames/moves) |
| `incoming/<date>/<name>` | uploads (`storage.dir`), unless exported elsewhere |

Every config key can be overridden by an environment variable `CAMBACKUP_<KEY>` (dots →
underscores, upper case), which is how `docker-compose.yml` configures it.

**One pairing file for phone and server:** copy the UDN shown in the Android app into
`server.udn` (or `CAMBACKUP_SERVER_UDN`). Both targets then answer to the same identity; the camera
uses whichever is on the current network.

**Networking:** the camera finds the server via SSDP multicast (TTL 2), so the container must be
on the camera's L2 segment: `network_mode: host`, or a `macvlan` network on the camera VLAN. If
only the phone forwards to the server (backend mode), plain port mapping of 52235/tcp is enough
and `ssdp.enabled=false` keeps the server off the UPnP radar.

### Pipeline (all optional, see `cambackup.conf`)

Per file, in order, on a worker thread: **EXIF time shift** (`exif.timeshift=+1:00`, exiftool) →
**RAW preview** (`raw.convert=true`; darktable-cli, rawtherapee-cli or LibRaw's `dcraw_emu`, the
latter is in the image) → **rename** (`rename.pattern={yyyy}{MM}{dd}_{HH}{mm}{ss}_{base}.{ext}`) →
**export** (`export.dir=/photos`, `export.subfolder={yyyy}-{MM}-{dd}`, e.g. a Synology Drive
share). Per session: **webhook** (JSON summary, e.g. Home Assistant), **hook.command** (shell,
file list in `CAMBACKUP_FILES`), **sysoff.command** when the camera asks to shut the PC down.
A stage whose tool is missing is skipped with a log line; the status page shows which tools exist.
`docker build --build-arg EXTRA_PACKAGES=darktable .` adds a full RAW developer.

### Backend API (used by the app's server modes)

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

1. Install the APK (releases page; every CI run also uploads `cambackup-debug-apk`).
2. The first start opens a three-step **wizard** (Settings → "Run the setup wizard again" repeats it):
   1. *Where photos go*
      * **Phone only** — a folder on the phone (✓). Default is the app's own storage; "Choose
        folder…" picks any folder via the system picker (e.g. one synced by Synology Drive).
      * **Phone + home server** — kept on the phone and uploaded to the server after every
        session and on "Sync to server" (✓ → ✓✓). For the road: shoot into the phone, sync at home.
      * **Home server only** — forwarded straight to the server, nothing stays on the phone (✓✓).
   2. *Destination* — the folder and/or the server URL (`http://nas.lan:52235`) + token.
   3. *Pairing* — the name shown on the camera; the UDN is generated once and kept (same UDN into
      the server's `server.udn` so one SD card works with both). **Export pairing file** saves
      `SAMSUNGAutoBackupDESC.zip`; unzip into the SD card root. Repeat after formatting the card.
3. **Start server** on the main screen. On the camera: Wi-Fi mode → *Auto Backup* (or the Smart
   Link button mapped to it), connect to the phone's hotspot / the same Wi-Fi. The camera lists
   the server by its name and uploads everything not yet backed up. Each file appears in the
   list with its state; the Log screen (toolbar) shows every request.

Phone and camera must be on the same L2 network (SSDP multicast). Hotspot works. Settings are
locked while the server runs.

## Build & test

Every module has a **95 % line-coverage gate** (JaCoCo); `check` fails below it.

* `./gradlew :core:check` — unit tests + loopback end-to-end (server ↔ simulated camera), HTTP
  edge cases, every UPnP action, SSDP on a test port, BackendSink, CLI; then the coverage gate.
* `./gradlew :server:check` — camera session, phone API contract (auth, chunked upload), every
  pipeline stage driven by fake exiftool / dcraw / darktable / rawtherapee binaries
  (`tools.path`), hooks/webhook, identity persistence, and a camera → phone → home server relay
  end-to-end; then the gate.
* `./gradlew :app:assembleDebug` — APK. `./gradlew :app:connectedDebugAndroidTest
  :app:jacocoAndroidTestCoverageVerification` — Espresso UI end-to-end on a device/emulator
  (wizard, settings incl. folder/pairing pickers answered by Espresso Intents and a test SAF
  provider, all three modes against a simulated camera and a fake home server, sync, log) and
  the gate. Report: `app/build/reports/jacoco/jacocoAndroidTestReport/html`.
* `./gradlew :server:installDist` — runnable server in `server/build/install/cambackup-server/`.
* `docker build -t cambackup-server .` — container image (no Android SDK needed).
  `tools/container-smoke.sh` builds it and pushes a JPEG through the phone API with time shift,
  EXIF-based rename, export into a second volume, token auth and dedup switched on.
* `tools/e2e-jvm.sh [--ssdp]` — loopback e2e without Gradle; `--ssdp` also exercises multicast.
* Requires JDK 17+ to run Gradle (any newer JDK works; bytecode targets 17). For the Android
  module, `local.properties` needs `sdk.dir=…` or `ANDROID_HOME` must be set.

### CI and releases

`.github/workflows/ci.yml`: core + server tests with the gates, debug + release APK, server zip,
Espresso suite with its gate on an emulator (API 34), multi-arch image build.

* Every push to `main`: image `ghcr.io/philippmundhenk/cambackup-server:main` (and `:latest`),
  APK + server zip as workflow artifacts.
* Tags `vX.Y.Z`: image `:X.Y.Z`, `:X.Y`, `:latest`, plus a GitHub release with the APKs and the
  server zip attached (`git tag v0.2.0 && git push --tags`).
* The release APK is signed with the repository secrets `ANDROID_KEYSTORE_B64`,
  `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` when they exist;
  otherwise it is debug-signed (installable, but updates require the same signature).

## Status / known gaps

* Not yet tested against a real camera — the simulator is built from the firmware strings and
  pc-autobackup packet notes. First real run: watch the log; everything the camera does is logged.
* "File exists" UPnP error code for `CreateObject` is unknown; the server accepts and discards
  duplicates instead (`BackupServer.fileExistsErrorCode`).
* Resumable uploads (`dlna:resumeUpload`) are announced as unsupported; the camera restarts a file
  after a dropped connection.
* RAW conversion and EXIF stages are exercised only where the tools are installed (the Docker
  image has exiftool and dcraw_emu); the unit tests cover the fallbacks.
