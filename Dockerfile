# Self-hosted nxbackup server: UPnP backup target for Samsung Wi-Fi cameras + backend for the
# Android app + post-processing pipeline. Build context is the repository root; the Android
# module is not copied, so no Android SDK is needed.
#
#   docker build -t nxbackup-server .
#   docker build --build-arg EXTRA_PACKAGES=darktable -t nxbackup-server .   # better RAW previews (large)

FROM gradle:8.14-jdk17 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY core core
COPY server server
# in-process Kotlin compiler: no second JVM (Kotlin daemon) inside the build container
RUN gradle :server:installDist --no-daemon -q -Dkotlin.compiler.execution.strategy=in-process

FROM eclipse-temurin:17-jre-jammy
# exiftool: time shift + rename metadata. libraw-bin: dcraw_emu for RAW -> JPEG previews
# (lightweight; set EXTRA_PACKAGES=darktable or rawtherapee for a full raw developer).
ARG EXTRA_PACKAGES=""
RUN apt-get update \
 && apt-get install -y --no-install-recommends libimage-exiftool-perl libraw-bin ${EXTRA_PACKAGES} \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/server/build/install/nxbackup-server /opt/nxbackup
ENV NXBACKUP_DATA=/data \
    JAVA_OPTS="-Djava.awt.headless=true -Xmx512m"
VOLUME ["/data"]
# 52235/tcp: camera (UPnP), phone (API), browser (status page). 1900/udp: SSDP discovery.
# Both only matter with bridge networking; use host networking (or macvlan) so the camera can find us.
EXPOSE 52235/tcp 1900/udp
HEALTHCHECK --interval=30s --timeout=5s CMD ["sh", "-c", "wget -qO- http://127.0.0.1:${NXBACKUP_HTTP_PORT:-52235}/health || exit 1"]
ENTRYPOINT ["/opt/nxbackup/bin/nxbackup-server"]
