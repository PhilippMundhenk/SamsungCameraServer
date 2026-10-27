# Android app

The app turns the phone into the camera's backup target: on the road over the phone's hotspot,
or at home on the same Wi-Fi. Android 8 or newer. Backup only, no other camera features.

## Where photos go

Chosen in the setup wizard (first start) or in Settings:

| Mode | What happens | List mark |
|---|---|---|
| **Phone only** | files are kept in a folder on the phone | ✓ |
| **Phone + home server** | kept on the phone and uploaded to the home server after every session and on "Sync to server" (whenever the server is reachable) | ✓ → ✓✓ |
| **Home server only** | streamed straight to the home server, nothing stays on the phone | ✓✓ |

The folder defaults to the app's own storage (`Android/data/org.mundhenk.cambackup/files/CamBackup`);
"Choose folder…" picks any folder through the system picker, for example one synced by
Synology Drive or Nextcloud. The server modes need the server URL (`http://nas.lan:52235`) and,
if set on the server, its token.

## Pairing the camera

The camera only talks to a server whose identity is on its SD card. Give the server a name
(shown on the camera), then **Export pairing file**: it saves `SAMSUNGAutoBackupDESC.zip`;
unzip it into the SD card root. The UDN is generated once and kept. Put the same UDN into the
home server (`server.udn`) so one card works with both targets. Export again after formatting
the card.

**Changing the UDN** ("New UDN", or editing the field) cuts off every camera paired with the
current one, so the app asks for confirmation and explains that first. Every replaced UDN is
kept under *Previous UDNs* in Settings; tap one to switch back to it (again with a warning),
which makes the cards that still carry it work again.

## Daily use

1. **Start server** on the main screen. A notification shows while it runs (needed to keep
   Wi-Fi and multicast awake).
2. On the camera: Wi-Fi mode → *Auto Backup* (NX cameras: the Smart Link button can be mapped
   to it), connect to the phone's hotspot or the shared Wi-Fi. The camera lists the server by
   its name and uploads everything it has not backed up yet.
3. Every file appears in the list with its state. The Log screen (overflow menu) shows each
   request, which is the first place to look when the camera does not find the phone.
4. Phone + home server: at home, tap **Sync to server** (or just finish a session on the home
   Wi-Fi); ✓ turns into ✓✓ when the server has the file.

Settings are locked while the server runs. "Clear list" in the overflow menu only clears the
list, not the files.

## Notes

* Phone and camera must be on the same network segment (SSDP multicast). The hotspot works on
  stock Android; some vendor ROMs block multicast on the hotspot interface.
* The app asks for the notification permission (Android 13+) to show the running notification;
  nothing else is requested.
* Files are written as `<folder>/<camera date>/<original name>`, duplicates (same date, name
  and size) are discarded, never overwritten.
* The camera sends at most 1,000 files per run, newest first, and remembers what it sent.
  With more new files on the card, start Auto Backup again; the next run continues with the
  older ones.
