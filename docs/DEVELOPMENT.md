# Development

```
core/    pure Kotlin/JVM, zero dependencies: SSDP responder, UPnP MediaServer stub, CreateObject/
         upload handling, pairing-file generator, camera simulator, tiny HTTP server, CLI,
         BackendSink (phone -> home server HTTP client)
server/  self-hosted server (Kotlin/JVM): config, ledger, pipeline, backend API, web UI, Docker
app/     Android app: setup wizard, main screen with transfer list, settings, log, foreground service
tools/   e2e-jvm.sh (loopback e2e without Gradle), container-smoke.sh (real image + pipeline)
docs/    DESIGN.md (concept, decisions, open questions), BRANDING.md, PROTOCOL.md, SERVER.md, APP.md
```

Requires JDK 17+ to run Gradle (any newer JDK works; bytecode targets 17). For the Android
module, `local.properties` needs `sdk.dir=…` or `ANDROID_HOME` must be set. Work is tracked in
[GitHub issues](https://github.com/PhilippMundhenk/SamsungCameraServer/issues).

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
  (wizard, settings incl. folder/pairing pickers answered by Espresso Intents and a debug-only
  SAF provider, all three modes against a simulated camera and a fake home server, sync, log)
  and the gate. Report: `app/build/reports/jacoco/jacocoAndroidTestReport/html`.
* `./gradlew :server:installDist` — runnable server in `server/build/install/cambackup-server/`.
* `docker build -t cambackup-server .` — container image (no Android SDK needed).
  `tools/container-smoke.sh` builds it and pushes a JPEG through the phone API with time shift,
  EXIF-based rename, export into a second volume, token auth and dedup switched on.
* `tools/e2e-jvm.sh [--ssdp]` — loopback e2e without Gradle; `--ssdp` also exercises multicast.

Measured on 2026-10-27: core 96.9 %, server 98.3 %, app 97.4 % lines.

## CI and releases

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
  pc-autobackup packet notes ([issue #6](https://github.com/PhilippMundhenk/SamsungCameraServer/issues/6)).
  First real run: watch the app log or the server status page; everything the camera does is logged.
* "File exists" UPnP error code for `CreateObject` is unknown; the server accepts and discards
  duplicates instead (`BackupServer.fileExistsErrorCode`).
* Resumable uploads (`dlna:resumeUpload`) are announced as unsupported; the camera restarts a file
  after a dropped connection.
* RAW conversion and EXIF stages run only where the tools are installed (the Docker image has
  exiftool, RawTherapee and dcraw_emu); the tests cover every stage with fake tools.
