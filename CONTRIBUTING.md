# Contributing

Bug reports, fixes and small focused features are welcome. Work is tracked in
[GitHub issues](https://github.com/PhilippMundhenk/SamsungCameraServer/issues); open one before
a larger change.

## Setup and checks

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md): JDK 17+, Android SDK for the app module,
Docker for the container smoke test.

```sh
./gradlew :core:check :server:check        # JVM tests + 95 % coverage gates
./gradlew :app:connectedDebugAndroidTest :app:jacocoAndroidTestCoverageVerification   # emulator
tools/container-smoke.sh                   # the real image, pipeline switched on
```

Every module must stay at or above 95 % line coverage; add tests with the code, not after.

## Pull requests

Branch off `main`, open a PR; CI runs the tests, the gates, the emulator suite and the image
build. Images are only published from `main` and tags. Commit subjects reference the issue
(`... (#12)`), bodies say what and why.

## Releases

`git tag vX.Y.Z && git push --tags` builds the image tags `X.Y.Z`, `X.Y`, `latest` and a GitHub
release with the APKs and the server zip. Add the notable changes to `CHANGELOG.md` first.
