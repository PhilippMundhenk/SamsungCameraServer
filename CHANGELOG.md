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
- Android: generating a new UDN asks for confirmation and explains that cameras paired with
  the old one stop syncing; replaced UDNs are kept in a history in Settings and can be
  switched back to.

### Added
- Device management (#22): the app shows whether it shares the server's identity and can take
  the server's UDN or hand its own to the server (`GET`/`POST /api/v1/identity`; the server
  rewrites its pairing file and restarts its camera side live). The UDN history records where
  each identity came from; the phone has a nickname that the server's new **Devices** list
  shows next to the cameras it has seen.
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
