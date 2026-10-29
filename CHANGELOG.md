# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Renamed to Samsung Camera Backup (code name `cambackup`); independence note everywhere.
- Android: first-start wizard, settings screen, log screen, transfer list with ✓ (on the
  phone) / ✓✓ (on the home server), new "phone + home server" store-and-forward mode with
  "Sync to server". (#2, #3, #4)
- Branding guide with shared light/dark tokens for the app and the server status page. (#5)
- 95 % line-coverage gates in every module; Espresso suite covering every screen and mode;
  JVM relay end-to-end camera → phone → home server. (#1)
- CI: release APK, multi-arch image on GHCR, GitHub release on `v*` tags. (#7)
- Server: `tools.path` to find exiftool / RAW converters outside `PATH`.
- Android: a **Hotspot** button next to *Start server* opens the phone's hotspot settings
  (Android lets no app switch the hotspot on directly).
- Android: the version name carries the git hash (`0.2.0-abc1234`), shown at the end of
  Settings and as the first log line when the server starts.
- Useful errors for the https:// mistake: the server logs a TLS handshake on its plain port
  as such ("use http:// instead of https://"), the app warns under the URL field while typing,
  adds `http://` when no scheme was typed and names the reason when the server's identity
  cannot be read ("the server does not speak TLS ...", "HTTP 404").
- Fixed: the second Auto Backup session stalled on both targets. After X_BACKUP_START the
  NX1000 browses the last object of its previous session; the server answered with an empty
  result and the camera waited forever. Browse now serves a small content tree (root with an
  "Auto Backup" folder, the parentIDs handed out in CreateObject, objects still waiting for
  their upload) and UPnP error 701 for anything else, which lets the camera continue. The
  session start recognises the camera's `NumOfContents`. (#23)
- Every protocol request is logged (service descriptions, SUBSCRIBE, Browse and the other
  ContentDirectory/ConnectionManager actions, connections closed without a request), so a
  camera that stops after the session start shows what it did next.
- SSDP: networks that come up after the start (the phone's hotspot) are joined within a few
  seconds, NOTIFYs go out per interface with that interface's address, and a search from a
  network the OS has no route for is answered with the address on the same subnet. The session
  start logs the number of files the camera announced, with a hint when it is 0.
- Android: generating a new UDN asks for confirmation and explains that cameras paired with
  the old one stop syncing; replaced UDNs are kept in a history in Settings and can be
  switched back to.

### Added
- Device management (#22): the app shows whether it shares the server's identity and can take
  the server's UDN or hand its own to the server (`GET`/`POST /api/v1/identity`; the server
  rewrites its pairing file and switches its camera side over live, without a restart). The UDN history records where
  each identity came from; the phone has a nickname that the server's new **Devices** list
  shows next to the cameras it has seen. Cameras and phones can be named on the status page
  (`POST /devices/rename`); the name is used in the list, the log and `/api/v1/status`.
- Server: a settings page at `/settings` edits `cambackup.conf` from the browser (environment
  values read-only with a badge and a "copy into the file" button, restart markers, protected
  by `api.token` as HTTP Basic password). The status page now shows the same status card as the
  app and links to the settings instead of listing them.

### Changed
- Server: received files are stored flat by default (`/photos/<name>`); `storage.subfolder`
  chooses `day`, `month`, `year` or any `{yyyy} {MM} {dd}` pattern (the old layout is `day`).
  A second file with the same name is stored with a numbered suffix instead of overwriting.
- Container: RawTherapee is part of the image, so `raw.convert=true` renders JPEGs from RAW
  files out of the box (LibRaw's `dcraw_emu` stays as the fallback, darktable via
  `EXTRA_PACKAGES`). The image grows from about 470 MB to 870 MB for it. The status page no
  longer lists the classic `dcraw` binary.
- Container: received files land in a separate `/photos` volume (`CAMBACKUP_STORAGE_DIR=/photos`
  set by the image); `/data` holds only config, identity and ledger; `/export` is the optional
  export target. Move `data/incoming/*` into `photos/` when upgrading.

### Fixed
- Truncated chunked uploads were accepted as complete.
- Title overlapped the status bar on Android 15. (#2)

## [0.1.0] - 2026-10-26

### Added
- Protocol core (SSDP, UPnP MediaServer stub, uploads), Android app with direct and backend
  modes, self-hosted server with ledger, pipeline (EXIF time shift, RAW preview, rename,
  export, webhook, hooks), status page and pairing-file download, Docker image.
