# Screenshots

Taken on an Android emulator (API 37) and against a local home server fed by the camera
simulator; the file names are the simulator's.

## App: main screen

The status card shows whether the server runs, where photos go and the one action that
matters. Below it every transferred file: ✓ on this phone, ✓✓ on the home server. Here the
first file has already been uploaded, the rest are syncing.

![Main screen](screenshots/app-main.png)

## App: setup wizard

Three steps on first start: where photos go, folder or server, pairing file.

![Wizard](screenshots/app-wizard.png)

## App: settings

Everything from the wizard, plus the identity the camera is paired with. Locked while the
server runs.

![Settings](screenshots/app-settings.png)

## Home server status page

The same status card as the app, totals, the transferred files (all ✓✓ by definition), tool
availability and the log; the pairing zip is one click away.

![Status page](screenshots/web-status.png)

## Home server settings page

Every option of `cambackup.conf` with an explanation. Values from the compose file carry an
`environment` badge and are read-only; "restart" marks what needs a restart.

![Settings page](screenshots/web-settings.png)
