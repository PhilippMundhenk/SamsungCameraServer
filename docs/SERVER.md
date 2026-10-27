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
      - ./data:/data              # config, identity, ledger, incoming/
      - ./photos:/photos          # optional export target
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

## The data directory

| File | Purpose |
|---|---|
| `cambackup.conf` | configuration, written with comments on first start |
| `SAMSUNGAutoBackupDESC.ini` | the server identity = the pairing file (UDN, name) |
| `received.tsv` | ledger of every file ever received (dedup survives renames/moves) |
| `incoming/<date>/<name>` | uploads (`storage.dir`), unless exported elsewhere |

Every key of `cambackup.conf` can be overridden by an environment variable `CAMBACKUP_<KEY>`
(dots → underscores, upper case). The file documents every key.

## Options

| Key | Default | Meaning |
|---|---|---|
| `http.port` | `52235` | camera (UPnP), phone (API) and browser all use this port |
| `server.name` | from the ini | name shown on the camera |
| `server.udn` | from the ini / generated | set it to the UDN shown in the app so one card works with both |
| `ssdp.enabled` | `true` | answer camera discovery; `false` for a phone-only backend behind port mapping |
| `storage.dir` | `incoming` | where uploads land |
| `api.token` | empty | bearer token the app must send (empty = anyone on the LAN can upload) |
| `ui.enabled` | `true` | status page and pairing download |
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
shows which tools exist. The image ships exiftool and LibRaw's `dcraw_emu`;
`docker build --build-arg EXTRA_PACKAGES=darktable .` adds a full RAW developer.

## Networking

The camera finds the server via SSDP multicast (TTL 2), so the container must be on the
camera's L2 segment: `network_mode: host`, or a `macvlan` network on the camera VLAN. If only
the phone talks to the server (its server modes), plain port mapping of 52235/tcp is enough and
`ssdp.enabled=false` keeps the server off the UPnP radar. HTTPS: put a reverse proxy in front
for the phone API; the camera path must stay plain HTTP.

## Backend API (used by the app)

```
HEAD {base}/api/v1/files/{date}/{name}                    200 = exists, 404 = not
PUT  {base}/api/v1/files/{date}/{name}   body = raw bytes 201 = stored, 200 = already existed
     headers: Content-Type, Content-Length (or chunked), X-NX-Camera (camera IP), X-NX-Class
POST {base}/api/v1/events  {"type":"start"|"done","camera":"<ip>","sysOff":true|false}  204
GET  {base}/api/v1/status  JSON   ·   GET {base}/api/v1/log   ·   GET {base}/health
```

`{date}` is the camera's `dc:date` (YYYY-MM-DD), `{name}` the original file name. With
`api.token` set, `/api/v1/files` and `/api/v1/events` require `Authorization: Bearer <token>`
(the token field in the app). Files arriving this way go through the same ledger and pipeline
as files from the camera.

Webhook JSON: `{"event":"done","camera","sysOff","stored","skipped","durationSec",
"files":[{"name","path","size","preview"?,"error"?}]}`.
