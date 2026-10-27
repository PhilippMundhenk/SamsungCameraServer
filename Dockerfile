# Self-hosted cambackup server: UPnP backup target for Samsung Wi-Fi cameras + backend for the
# Android app + post-processing pipeline. Build context is the repository root; the Android
# module is not copied, so no Android SDK is needed.
#
#   docker build -t cambackup-server .
#   docker build --build-arg EXTRA_PACKAGES=darktable -t cambackup-server .   # better RAW previews (large)

# The build stage always runs on the builder's own architecture: the JVM output is the same for
# every target platform, so a multi-arch build does not compile Kotlin under QEMU.
FROM --platform=$BUILDPLATFORM gradle:8.14-jdk17 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY core core
COPY server server
# in-process Kotlin compiler: no second JVM (Kotlin daemon) inside the build container
RUN gradle :server:installDist --no-daemon -q -Dkotlin.compiler.execution.strategy=in-process

FROM eclipse-temurin:17-jre-jammy
# exiftool: time shift + rename metadata. rawtherapee: the RAW developer (rawtherapee-cli, headless)
# used for RAW -> JPEG when raw.convert is on. libraw-bin: dcraw_emu as the small fallback.
# EXTRA_PACKAGES=darktable adds darktable-cli, which raw.converter=auto prefers when present.
ARG EXTRA_PACKAGES=""
RUN apt-get update \
 && apt-get install -y --no-install-recommends libimage-exiftool-perl rawtherapee libraw-bin ${EXTRA_PACKAGES} \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/server/build/install/cambackup-server /opt/cambackup
# /data: config, identity (pairing file), ledger - small, back it up.
# /photos: what the camera sends (<date>/<name>) - large, processed further later, lives elsewhere.
# /export (optional): where the pipeline moves finished files (export.dir).
ENV CAMBACKUP_DATA=/data \
    CAMBACKUP_STORAGE_DIR=/photos \
    JAVA_OPTS="-Djava.awt.headless=true -Xmx512m"
VOLUME ["/data", "/photos"]
# 52235/tcp: camera (UPnP), phone (API), browser (status page). 1900/udp: SSDP discovery.
# Both only matter with bridge networking; use host networking (or macvlan) so the camera can find us.
EXPOSE 52235/tcp 1900/udp
HEALTHCHECK --interval=30s --timeout=5s CMD ["sh", "-c", "wget -qO- http://127.0.0.1:${CAMBACKUP_HTTP_PORT:-52235}/health || exit 1"]
ENTRYPOINT ["/opt/cambackup/bin/cambackup-server"]
