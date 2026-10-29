#!/usr/bin/env bash
# Build the server image and push one JPEG through the phone API with the pipeline switched on
# (EXIF time shift, rename with EXIF tokens, upload volume /photos, export into a third volume
# /export, token auth, dedup).
# Needs docker + curl + python3 with Pillow (to synthesize the JPEG).
#
# Usage: tools/container-smoke.sh [image-tag]     default tag: cambackup-server:smoke
set -euo pipefail
cd "$(dirname "$0")/.."
IMG="${1:-cambackup-server:smoke}"
PORT="${PORT:-18080}"
# Native path (pwd -W on Git Bash) so Windows Python and Docker volume mounts both understand it.
WORK="$(pwd -W 2>/dev/null || pwd)/build/container-smoke"
rm -rf "$WORK"
export MSYS_NO_PATHCONV=1   # Git Bash: do not rewrite /data style container paths
# Cleanup must never fail the run: files written by the container belong to root on Linux, so
# delete them from inside a container when a plain rm is refused.
cleanup() {
  docker rm -f cambackup-smoke >/dev/null 2>&1 || true
  rm -rf "$WORK" 2>/dev/null || docker run --rm -v "$WORK:/w" --entrypoint sh "$IMG" -c 'rm -rf /w/data /w/photos /w/export /w/in' >/dev/null 2>&1 || true
  rm -rf "$WORK" 2>/dev/null || true
}
trap cleanup EXIT

docker build -q -t "$IMG" . >/dev/null
mkdir -p "$WORK/in" "$WORK/data" "$WORK/photos" "$WORK/export"
# Windows ships a "python3" stub that only opens the Store; pick the interpreter that has Pillow.
PY=""; for c in python3 python; do "$c" -c 'import PIL' >/dev/null 2>&1 && { PY="$c"; break; }; done
[ -n "$PY" ] || { echo "need python with Pillow"; exit 1; }
"$PY" - "$WORK/in/SAM_0100.JPG" <<'EOF'
import sys
from PIL import Image
Image.new("RGB", (640, 480), (120, 80, 40)).save(sys.argv[1], quality=85)
EOF
docker run --rm -v "$WORK/in:/w" --entrypoint exiftool "$IMG" -q -overwrite_original \
  "-DateTimeOriginal=2024:05:06 07:08:09" -Model=NX1000 /w/SAM_0100.JPG

docker run -d --name cambackup-smoke -p "$PORT:52235" -v "$WORK/data:/data" -v "$WORK/photos:/photos" -v "$WORK/export:/export" \
  -e CAMBACKUP_SSDP_ENABLED=false -e CAMBACKUP_API_TOKEN=tok -e CAMBACKUP_EXIF_TIMESHIFT=+1:00 \
  -e "CAMBACKUP_RENAME_PATTERN={yyyy}{MM}{dd}_{HH}{mm}{ss}_{camera}_{base}.{ext}" \
  -e CAMBACKUP_EXPORT_DIR=/export -e "CAMBACKUP_EXPORT_SUBFOLDER={yyyy}/{yyyy}-{MM}-{dd}" "$IMG" >/dev/null
for i in $(seq 1 30); do curl -s -m 2 -o /dev/null "http://127.0.0.1:$PORT/health" && break; sleep 1; done

B="http://127.0.0.1:$PORT/api/v1"; A="Authorization: Bearer tok"
code() { curl -s -m 30 -o /dev/null -w '%{http_code}' "$@"; }
check() { [ "$1" = "$2" ] || { echo "FAIL: $3 -> HTTP $1 (expected $2)"; docker logs cambackup-smoke; exit 1; }; echo "ok  $3 -> $1"; }
check "$(code -X POST -H "$A" -d '{"type":"start","camera":"10.9.9.9","sysOff":false}' "$B/events")" 204 "start event"
check "$(code -X PUT -H "$A" -H 'Content-Type: image/jpeg' -H 'X-NX-Camera: 10.9.9.9' --data-binary @"$WORK/in/SAM_0100.JPG" "$B/files/2024-05-06/SAM_0100.JPG")" 201 "upload"
check "$(code -X PUT -H "$A" -H 'Content-Type: image/jpeg' -H 'X-NX-Camera: 10.9.9.9' --data-binary @"$WORK/in/SAM_0100.JPG" "$B/files/2024-05-06/SAM_0100.JPG")" 200 "duplicate upload"
check "$(code -X PUT -H 'Content-Type: image/jpeg' --data-binary @"$WORK/in/SAM_0100.JPG" "$B/files/2024-05-06/SAM_0101.JPG")" 401 "upload without token"
check "$(code -X POST -H "$A" -d '{"type":"done","camera":"10.9.9.9","sysOff":false}' "$B/events")" 204 "done event"

for i in $(seq 1 30); do [ -f "$WORK/export/2024/2024-05-06/20240506_080809_NX1000_SAM_0100.JPG" ] && break; sleep 1; done
OUT="$WORK/export/2024/2024-05-06/20240506_080809_NX1000_SAM_0100.JPG"
[ -f "$OUT" ] || { echo "FAIL: exported file missing"; find "$WORK/photos" "$WORK/export" -type f; docker logs cambackup-smoke; exit 1; }
echo "ok  exported + renamed with shifted EXIF time: ${OUT#$WORK/}"
[ ! -e "$WORK/photos/SAM_0100.JPG" ] || { echo "FAIL: original still in /photos"; exit 1; }
[ ! -e "$WORK/photos/2024-05-06" ] || { echo "FAIL: date folder created although the default layout is flat"; exit 1; }
[ ! -e "$WORK/data/incoming" ] || { echo "FAIL: /data/incoming was created; uploads must land in /photos"; exit 1; }
echo "ok  uploads land in /photos, state stays in /data"
grep -q "SAM_0100.JPG	5" "$WORK/data/received.tsv" && grep -q "20240506_080809_NX1000_SAM_0100.JPG" "$WORK/data/received.tsv" || { echo "FAIL: ledger not updated"; cat "$WORK/data/received.tsv"; exit 1; }
echo "ok  ledger follows the move"
docker logs cambackup-smoke 2>&1 | grep -q "Shifted EXIF dates" && echo "ok  exiftool time shift ran"
# the RAW developer and the fallback are in the image, and the status page knows it
# rawtherapee-cli prints its banner and exits 2 on --version, so look at the output, not the
# exit code (and keep pipefail out of it by capturing first)
RT_OUT=$(docker run --rm --entrypoint rawtherapee-cli "$IMG" --version 2>&1 || true)
echo "$RT_OUT" | grep -q "RawTherapee" || { echo "FAIL: rawtherapee-cli missing in the image: $RT_OUT"; exit 1; }
docker run --rm --entrypoint sh "$IMG" -c 'command -v dcraw_emu >/dev/null' || { echo "FAIL: dcraw_emu missing in the image"; exit 1; }
# the probe runs in the background and RawTherapee's first start can take a while on a slow runner
for i in $(seq 1 150); do curl -s -m 5 "http://127.0.0.1:$PORT/" | grep -q 'rawtherapee-cli &#10003;' && break; sleep 1; done
curl -s -m 5 "http://127.0.0.1:$PORT/" | grep -q 'rawtherapee-cli &#10003;' || { echo "FAIL: status page does not show rawtherapee-cli as available"; curl -s "http://127.0.0.1:$PORT/" | grep -o 'Tools:.*</p>'; exit 1; }
echo "ok  RAW developer (rawtherapee-cli) and dcraw_emu available in the image"
echo "CONTAINER SMOKE PASSED"
