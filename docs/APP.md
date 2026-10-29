# Android app

The app turns the phone into the camera's backup target: on the road over the phone's hotspot,
or at home on the same Wi-Fi. Android 8 or newer. Backup only, no other camera features.

## Where photos go

Chosen in the setup wizard (first start) or in Settings:

| Mode | What happens | List mark |
|---|---|---|
| **Phone only** | files are kept in a folder on the phone | ✓ |
| **Phone + home server** | kept on the phone and uploaded to the home server on their own, whenever it is reachable (also with the app closed); **Sync now** forces an attempt | ✓ → ✓✓ |
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

**Sharing the identity with the home server.** Phone and server are meant to advertise the
same UDN, so one SD card works at home and on the road. In the server modes, Settings (and the
wizard's pairing step) show whether the two match and offer both directions: *Use the server's
identity* takes the server's UDN and name onto the phone; *Give the server this identity* makes
the server switch to the phone's (it rewrites its pairing file and switches its camera side over live,
no container restart needed). Start with whichever device you paired first. Under the UDN the
app shows where it came from; the phone's nickname ("Name of this phone") is what the server's
device list shows.

**Changing the UDN** ("New UDN", or editing the field) cuts off every camera paired with the
current one, so the app asks for confirmation and explains that first. Every replaced UDN is
kept under *Previous UDNs* in Settings; tap one to switch back to it (again with a warning),
which makes the cards that still carry it work again.

## Daily use

1. **Start server** on the main screen. A notification shows while it runs (needed to keep
   Wi-Fi and multicast awake). Away from home, tap **Hotspot** next to it first: Android lets
   no app switch the hotspot on itself, so the button opens the phone's hotspot settings where
   you flip the switch; then connect the camera to the hotspot.
2. On the camera: Wi-Fi mode → *Auto Backup* (NX cameras: the Smart Link button can be mapped
   to it), connect to the phone's hotspot or the shared Wi-Fi. The camera lists the server by
   its name and uploads everything it has not backed up yet.
3. Every file appears in the list with its state. The Log screen (overflow menu) shows each
   request, which is the first place to look when the camera does not find the phone.
4. Phone + home server: the upload to the home server happens on its own. After a session,
   and whenever the phone has a network again, the app tries to reach the server and uploads
   what is waiting, also while the app is closed (a background job every 15 minutes as the
   safety net), until ✓ has turned into ✓✓ for every file. **Sync now** forces an attempt;
   *Upload automatically* in Settings switches the automatic part off.
5. Tap a file in the list to open it in the phone's gallery or video player; JPEGs show a
   preview in the list.

Settings are locked while the server runs. "Clear list" in the overflow menu only clears the
list, not the files.

## Which build is installed?

Settings ends with `Samsung Camera Backup 0.2.0-<git hash>`, and the same line is the first
thing the log shows when the server starts. Compare the hash with the commit the APK was built
from before chasing a bug that is already fixed.

## Notes

* Phone and camera must be on the same network segment (SSDP multicast). The hotspot works on
  stock Android; some vendor ROMs block multicast on the hotspot interface. Switching the
  hotspot on after the server started is fine: the app joins the new network within a few
  seconds (log: `SSDP now also listening on …`).
* The camera shows its transfer screen but sends nothing: look at the `Backup session started
  by …` line in the log. The camera announces how many files it will send; `0 files announced`
  means every file on the card is already flagged as backed up. The camera keeps that flag per
  file, not per target, so what the home server already received under the same UDN is not
  offered to the phone again (and the other way round).
* The app asks for the notification permission (Android 13+) to show the running notification;
  nothing else is requested.
* Files are written as `<folder>/<camera date>/<original name>`, duplicates (same date, name
  and size) are discarded, never overwritten.
* The camera sends at most 1,000 files per run, newest first, and remembers what it sent.
  With more new files on the card, start Auto Backup again; the next run continues with the
  older ones.
* Stopping Auto Backup on the camera in the middle of a file leaves nothing behind on the
  phone: the fragment is rejected (log: `short upload of …`), the camera has not flagged the
  file, and it sends it again next time. An older build may have recorded such a fragment as
  complete; the sync removes it with a log line and the camera re-sends it as well.
* Several targets, no bookkeeping: phone and home server do not have to be synchronised with
  each other. The camera keeps the only state that matters, per file on the card, and a
  target that is asked about a file it never saw simply says so.
