# Home server

The home server is the always-on target on your LAN: the camera backs up to it directly, the
Android app forwards or syncs to it, and it post-processes what arrives. One container, one port.

## Run it

```yaml
# docker-compose.yml
services:
  cambackup:
    image: ghcr.io/philippmundhenk/cambackup-server:latest
    container_name: cambackup
    network_mode: host            # the camera finds the server via multicast
    restart: unless-stopped
    volumes:
      - ./data:/data              # config, identity, ledger (small, back it up)
      - ./photos:/photos          # received files, /photos/<date>/<name> (large)
      # - /volume1/photo/export:/export   # optional export target (export.dir)
    environment:
      CAMBACKUP_SERVER_NAME: "[PC]Home"
      CAMBACKUP_API_TOKEN: change-me
```

```sh
docker compose up -d
```

Then open `http://<host>:52235/`: status, recent files, pipeline and tool state, log, and the
**pairing zip** download. Unzip it into the SD card root. Repeat after formatting the card.

Without Docker (JDK 17+): `./gradlew :server:installDist` and run
`server/build/install/cambackup-server/bin/cambackup-server ./data`.

## Directories

Two volumes with different lifecycles: the small state directory you back up, and the photos
you process further and move elsewhere.

| Volume | Contents |
|---|---|
| `/data` | `cambackup.conf` (configuration, written with comments on first start), `SAMSUNGAutoBackupDESC.ini` (the server identity = the pairing file), `received.tsv` (ledger of every file ever received; dedup survives renames and moves) |
| `/photos` | what the camera sends (`storage.dir`, set to `/photos` by the image): flat by default, `/photos/<name>`; `storage.subfolder` adds year / month / day folders |
| `/export` (optional) | where the pipeline moves finished files when `export.dir=/export` is set, under `export.subfolder` |

Outside Docker, `storage.dir` defaults to `incoming` inside the data directory; relative paths
resolve against the data directory. Every key of `cambackup.conf` can be overridden by an
environment variable `CAMBACKUP_<KEY>` (dots → underscores, upper case); the image sets
`CAMBACKUP_STORAGE_DIR=/photos`, which wins over the file. The file documents every key.

Upgrading from a version that stored uploads under `/data/incoming`: move the date folders
into the `photos` volume (`mv data/incoming/* photos/`). The ledger keeps the old paths in its
"now at" column, but deduplication keys on date + name + size, so nothing is received twice.

## Settings page

`http://<host>:52235/settings` edits `cambackup.conf` from the browser: every option below with
a short explanation, grouped into Camera, Storage, Pipeline, Hooks and Web UI. Rules:

* **The environment always wins.** A value set in the compose file is shown read-only with an
  `environment` badge. "Copy the environment values into the file" writes them into
  `cambackup.conf`; afterwards the entries can be removed from the compose file.
* Settings marked **restart** (port, identity, SSDP, storage directory, the UI switches) take
  effect after a restart; everything else applies to the next file or session.
* With `api.token` set, the page asks for a password (any user name, the token). Without a
  token it is open to the LAN, like the status page, and says so. `ui.settings=false` removes
  the page.

## Options

| Key | Default | Meaning |
|---|---|---|
| `http.port` | `52235` | camera (UPnP), phone (API) and browser all use this port |
| `server.name` | from the ini | name shown on the camera |
| `server.udn` | from the ini / generated | set it to the UDN shown in the app so one card works with both |
| `ssdp.enabled` | `true` | answer camera discovery; `false` for a phone-only backend behind port mapping |
| `storage.dir` | `incoming` (`/photos` in the image) | where uploads land |
| `storage.subfolder` | `flat` | folders under `storage.dir` from the camera's date: `flat` (none), `day` (`{yyyy}-{MM}-{dd}`), `month` (`{yyyy}/{MM}`), `year` (`{yyyy}`), or any pattern with `{yyyy} {MM} {dd}` and `/`. A second file with the same name gets `_1`, `_2`, … |
| `api.token` | empty | bearer token the app must send (empty = anyone on the LAN can upload) |
| `ui.enabled` | `true` | status page and pairing download |
| `ui.settings` | `true` | the settings page at `/settings` |
| `ui.previews` | `true` | thumbnails and open-file links on the status page (`/thumb/…`, `/file/…`); the page is open to the LAN |
| `exif.timeshift` | off | shift all EXIF dates, exiftool syntax: `+1:00`, `-0:30`, `+0:0:1 0:0:0` |
| `raw.convert` | `false` | render a JPEG next to every `.SRW` (`raw.converter` = auto / darktable / rawtherapee / dcraw / none; `raw.onlyifnojpeg`, `raw.quality`) |
| `rename.pattern` | off | e.g. `{yyyy}{MM}{dd}_{HH}{mm}{ss}_{camera}_{base}.{ext}` (EXIF via exiftool, falls back to the camera date) |
| `export.dir`, `export.subfolder` | off, `{yyyy}-{MM}-{dd}` | move finished files into a share (e.g. Synology Drive) |
| `webhook.url`, `webhook.token` | off | POST a JSON session summary (e.g. Home Assistant) |
| `hook.command` | off | shell command after every session (`CAMBACKUP_FILES`, `CAMBACKUP_STORED`, …) |
| `sysoff.command` | off | runs when the camera asks to shut the PC down |
| `tools.path` | empty | extra directories searched for exiftool / converters before `PATH` |

The pipeline runs per file on a worker thread, in order: EXIF time shift → RAW preview →
rename → export. A stage whose tool is missing is skipped with a log line; the status page
shows which tools exist. The image ships exiftool, RawTherapee (`rawtherapee-cli`, used for
RAW → JPEG when `raw.convert=true`) and LibRaw's `dcraw_emu` as the small fallback;
`docker build --build-arg EXTRA_PACKAGES=darktable .` adds darktable, which `raw.converter=auto`
prefers when present.

## Per-camera settings

Every camera the server has seen gets its own block at the bottom of the settings page: the
folder layout, every pipeline stage (time shift, RAW rendering, renaming, export) and the hooks
can be set per camera; empty fields inherit the general value. In the file the keys are
`camera.<id>.<key>` with the camera's address, dots as underscores:

```
exif.timeshift=
camera.192_168_1_23.exif.timeshift=+1:00
camera.192_168_1_23.rename.pattern={yyyy}{MM}{dd}_{HH}{mm}{ss}_kitchen_{base}.{ext}
camera.192_168_1_24.storage.subfolder=year
```

The same works from the environment (`CAMBACKUP_CAMERA_192_168_1_23_EXIF_TIMESHIFT=+1:00`).
Cameras are told apart by their IP address, so give each one a fixed address in your router;
the name given on the status page is only a label. Files that arrived through the phone carry
the camera's address too (`X-NX-Camera`), so the overrides apply to them as well.

## Networking

The camera finds the server via SSDP multicast (TTL 2), so the container must be on the
camera's L2 segment: `network_mode: host`, or a `macvlan` network on the camera VLAN. If only
the phone talks to the server (its server modes), plain port mapping of 52235/tcp is enough and
`ssdp.enabled=false` keeps the server off the UPnP radar. HTTPS: put a reverse proxy in front
for the phone API; the camera path must stay plain HTTP.

## When the camera does not connect

`TLS handshake from <ip>: this port speaks plain HTTP` in the log means someone (the app's
server URL, a browser) used `https://` on port 52235; the server itself only speaks plain
`http://`. Put a TLS reverse proxy in front if you need https.

The first thing to check is the `Backup session started by <ip>: N file(s) announced` line:
the camera says how many files it will send. `0 files announced` means every file on its card
is already flagged as backed up (the flag is per file, not per target: what the phone received
under the same UDN is not offered to the server again, and vice versa).

`handler error for PUT …: short upload of <file>: X of Y bytes` is the phone (or a camera
upload) that stopped mid-file; nothing is kept and the sender tries again. Stopping Auto
Backup on the camera produces exactly this once.

The camera shows the server's name as soon as the pairing file is on the card; that proves
nothing about the network. It then searches with SSDP (`ST: urn:schemas-upnp-org:device:MediaServer:1`)
and fetches `/DMS/SamsungDmsDesc.xml`. Watch the status page log:

* `SSDP reply to <camera ip> (ST=urn:schemas-upnp-org:device:MediaServer:1)` followed by
  `Camera <ip> fetched device description (Samsung User-Agent DLNADOC/1.50)`: discovery works;
  the session follows (`Backup session started`, `Ready for`, `Stored ... MB/s`).
* Replies only to Windows PCs (`Microsoft-Windows/10.0 UPnP/1.0`, `DAFUPnP`, `FDSSDP`) or Home
  Assistant: the camera's search never arrived. Camera and server must be on the same network
  segment (Wi-Fi SSID on the same VLAN as the container's host interface; `network_mode: host`
  or `macvlan`). A search for something we do not answer is logged as `SSDP search from <ip>
  for '<ST>' ignored`.
* Test from another machine on the camera's network:
  `docker run --rm --network host ghcr.io/philippmundhenk/cambackup-server:latest discover`
  prints who answers, and `... simulate <server ip>` runs a complete fake camera session.
* Transfers are slow: the `Stored` log line shows the rate. Measured with an NX1000: about
  0.65 MB/s per file and 0.57 MB/s over a whole session (1,000 files, 3.8 GB in 1 h 52 min),
  i.e. 4.5–5 Mbit/s end to end; that is the camera's Wi-Fi, not the server.

## Backend API (used by the app)

```
HEAD {base}/api/v1/files/{date}/{name}                    200 = exists, 404 = not
PUT  {base}/api/v1/files/{date}/{name}   body = raw bytes 201 = stored, 200 = already existed
     headers: Content-Type, Content-Length (or chunked), X-NX-Camera (camera IP), X-NX-Class
POST {base}/api/v1/events  {"type":"start"|"done","camera":"<ip>","sysOff":true|false}  204
GET  {base}/api/v1/status  JSON   ·   GET {base}/api/v1/log   ·   GET {base}/health
GET  {base}/api/v1/identity                               {"udn","name","mac"} (public, what the app imports)
POST {base}/api/v1/identity  {"udn","name"}               server adopts the identity: config file, pairing file,
                                                          UPnP side switched over live (token required)
```

Every request from the app carries `X-NX-Device: <phone nickname>`; the status page lists the
devices it has seen (cameras by address, phones by nickname, with sessions, files and last
activity) under **Devices**, persisted in `devices.tsv`. Every device can be given a name there
("NX1000", "Living room camera"): type it into the field under the device and press *Name*.
Cameras are told apart by their IP address, so give the camera a fixed address in your router
if you want the name to stick. The name shows up in the device list, the *Last:* line, the log
(`Session from NX1000 (192.168.1.20): ...`) and `GET /api/v1/status` (`devices[].name`). The
form is protected like the settings page (`api.token` as HTTP Basic password); an empty name
removes it. `POST /devices/rename` with `kind=CAMERA|PHONE&id=<address or nickname>&nickname=<name>`
does the same from a script.

`{date}` is the camera's `dc:date` (YYYY-MM-DD), `{name}` the original file name. With
`api.token` set, `/api/v1/files` and `/api/v1/events` require `Authorization: Bearer <token>`
(the token field in the app). Files arriving this way go through the same ledger and pipeline
as files from the camera.

Webhook JSON: `{"event":"done","camera","sysOff","stored","skipped","durationSec",
"files":[{"name","path","size","preview"?,"error"?}]}`.
