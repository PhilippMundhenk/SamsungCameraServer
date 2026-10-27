#!/usr/bin/env bash
# Compile the :core module with a standalone Kotlin compiler and run the
# loopback end-to-end test (server + simulated camera). No Gradle, no network.
#
# Usage: tools/e2e-jvm.sh [--ssdp]
#   --ssdp  also test SSDP multicast discovery (needs a multicast-capable NIC)
#
# Kotlin compiler: set KOTLIN_LIB to a dir containing kotlin-compiler-embeddable
# and kotlin-stdlib jars (a Gradle distribution's lib/ dir works).
set -euo pipefail
cd "$(dirname "$0")/.."
KOTLIN_LIB="${KOTLIN_LIB:-/opt/gradle/lib}"
OUT="${OUT:-build/e2e-jvm}"
rm -rf "$OUT"; mkdir -p "$OUT"
STDLIB="$(ls "$KOTLIN_LIB"/kotlin-stdlib-*.jar | head -1)"
java -cp "$KOTLIN_LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -cp "$STDLIB" -d "$OUT" $(find core/src/main/kotlin -name '*.kt') 2>&1 | grep -v -E '^Picked up|^warning:' || true
test -f "$OUT/org/mundhenk/cambackup/core/MainKt.class" || { echo "compile failed"; exit 1; }
java -cp "$OUT:$STDLIB" org.mundhenk.cambackup.core.MainKt e2e "$@"
