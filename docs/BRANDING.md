# Samsung Camera Backup — branding guide

One product, two surfaces: the **Android app** and the **home server's web status page**. They must
look like the same thing. This file is the single source of the names, colours, type and the few
components both use. Code references: `app/src/main/res/values/colors.xml` (+ `values-night`),
`app/src/main/res/values/themes.xml`, `server/.../Api.kt` (`StatusPage`).

> Samsung Camera Backup is an independent open-source project. It is not affiliated with,
> endorsed by or supported by Samsung. "Samsung" and the camera model names are used only to
> describe which cameras the software works with.

## 1. Name

| Where | Name |
|---|---|
| Product (app title, web header, docs) | **Samsung Camera Backup** |
| Short / code name (package, image, CLI, config) | `cambackup` — `org.mundhenk.cambackup`, `cambackup-server`, `cambackup.conf`, env `CAMBACKUP_*` |
| Repository | `PhilippMundhenk/SamsungCameraServer` |

Casing: "Samsung Camera Backup" in prose and titles, `cambackup` in identifiers, never "CamBackup"
in user-facing text (only as a Kotlin/style prefix).

## 2. Colours

Light and dark variants; every surface picks the pair for the system theme. The accent is the
"shutter blue" already used on the web page; it is the only brand colour.

| Token | Light | Dark | Used for |
|---|---|---|---|
| `primary` | `#2A6FDB` | `#8AB4F8` | app bar, primary button, links, ✓✓ |
| `on_primary` | `#FFFFFF` | `#0B2A5B` | text/icons on primary |
| `primary_container` | `#D6E4FA` | `#1B3A6B` | notices, selected chips |
| `surface` | `#FFFFFF` | `#121212` | page/screen background |
| `surface_container` (card) | `#F7F7F7` | `#1E1E1E` | status card, tiles, log box |
| `on_surface` | `#1A1A1A` | `#EEEEEE` | body text |
| `on_surface_variant` (muted) | `#666666` | `#AAAAAA` | secondary text, ✓ |
| `outline` | `#E3E3E3` | `#333333` | borders, table rules |
| `ok` | `#2E7D32` | `#81C995` | running indicator, tool available |
| `error` | `#C62828` | `#F28B82` | failures, tool missing |

Web CSS variables map 1:1: `--accent`=primary, `--bg`=surface, `--card`=surface_container,
`--fg`=on_surface, `--muted`=on_surface_variant, `--line`=outline, `--ok`, `--bad`=error.

## 3. Typography

* Android: Material 3 type scale with the system font (Roboto). Screen titles `titleLarge`,
  section headers `titleSmall` in muted colour, all caps, 0.04 letter spacing. Body
  `bodyMedium`, secondary `bodySmall` muted. Log and UDNs in `monospace`, 11–12 sp.
* Web: `system-ui, sans-serif` at 15 px / 1.5 line height; `h1` 1.4 em, `h2` 1.05 em muted all
  caps (same treatment as the Android section headers). Code/paths/log in `ui-monospace`.

## 4. Components

* **Brand bar** — primary background, `on_primary` text, product name left. Android:
  `Widget.CamBackup.Toolbar`; web: `<header>`.
* **Status card / tiles** — `surface_container` fill, 1 px `outline` border, 12 px (Android) /
  8 px (web) radius, 16 px padding. The big number or state first, the label under it in muted.
* **Running indicator** — 12 px dot: `ok` when the server runs, muted when stopped.
* **Buttons** — one filled primary button per screen for the main action (Start/Stop server,
  Next). Everything else is an outlined or text button in primary.
* **Transfer states** — plain text marks, not icons, so they render identically everywhere:
  * `✓` in muted = the file is on the phone
  * `✓✓` in primary = the file is on the home server
  The web page shows `✓✓` for every file because everything listed there is on the server.
* **Lists** — 56 dp rows: state mark (40 dp column), file name in body size, second line
  "date · size · time" muted, third line location muted and middle-ellipsised.
* **Notices** — `primary_container` background, `on_primary_container` text, 12 px padding
  (e.g. "Stop the server to change these settings").

## 5. Icon

Launcher and notification icon: white camera body with a blue lens on a `primary` background,
small upload arrow top right (`app/src/main/res/drawable/ic_launcher_foreground.xml`). The web
page uses no logo, only the wordmark in the brand bar.

## 6. Tone

Short sentences, no exclamation marks, plain words ("on this phone", "on the server"). Errors
say what to do next ("Enter the server URL first"). Camera-side instructions always name the
menu path the camera shows: *Wi-Fi → Auto Backup*.
