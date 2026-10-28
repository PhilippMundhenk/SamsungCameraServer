package org.mundhenk.cambackup.server

import org.mundhenk.cambackup.core.UpnpXml
import java.net.URLDecoder

/** One editable key of cambackup.conf, with everything the settings page needs to render it. */
class SettingDef(
    val key: String,
    val section: String,
    val label: String,
    val help: String,
    val type: Type = Type.TEXT,
    val options: List<Pair<String, String>> = emptyList(),   // value to label, for SELECT
    val placeholder: String = "",
    /** Changing it needs a restart of the server (everything else applies to the next file/session). */
    val restart: Boolean = false,
    val secret: Boolean = false,
) {
    enum class Type { TEXT, BOOL, SELECT, NUMBER }
}

/** The catalogue behind the settings page; every key of cambackup.conf appears exactly once. */
object SettingsCatalog {
    val all: List<SettingDef> = listOf(
        SettingDef("server.name", "Camera", "Name shown on the camera", "Overrides the name in the pairing file (SAMSUNGAutoBackupDESC.ini). Export the pairing file again after changing it.", placeholder = "[PC]Home", restart = true),
        SettingDef("server.udn", "Camera", "UDN", "The identity the camera matches. Set it to the UDN shown in the Android app so one SD card works for phone and server. Changing it means re-pairing every card.", placeholder = "4a682b0b-0361-dbae-6155-xxxxxxxxxxxx", restart = true),
        SettingDef("ssdp.enabled", "Camera", "Answer camera discovery (SSDP)", "Off when only the phone talks to this server (port mapping instead of host networking).", SettingDef.Type.BOOL, restart = true),
        SettingDef("http.port", "Camera", "HTTP port", "Camera, phone and browser all use this port.", SettingDef.Type.NUMBER, placeholder = "52235", restart = true),

        SettingDef("storage.dir", "Storage", "Storage directory", "Where uploads land. /photos in the container.", placeholder = "incoming", restart = true),
        SettingDef("storage.subfolder", "Storage", "Folder layout", "Folders under the storage directory, from the camera's date.", SettingDef.Type.SELECT,
            listOf("flat" to "flat: no folders", "day" to "day: 2026-10-27", "month" to "month: 2026/10", "year" to "year: 2026")),
        SettingDef("api.token", "Storage", "Token for the phone and this page", "The Android app sends it as the backend token. Also protects this settings page (any user name, token as password). Empty = anyone on the LAN.", secret = true),

        SettingDef("exif.timeshift", "Pipeline", "EXIF time shift", "exiftool syntax: +1:00 (one hour later), -0:30, +0:0:1 0:0:0 (one day). Applied to every image before anything else.", placeholder = "+1:00"),
        SettingDef("raw.convert", "Pipeline", "Render JPEG from RAW", "Writes <name>.jpg next to every .SRW.", SettingDef.Type.BOOL),
        SettingDef("raw.converter", "Pipeline", "RAW converter", "auto takes the first one installed: darktable, RawTherapee, dcraw_emu.", SettingDef.Type.SELECT,
            listOf("auto" to "auto", "darktable" to "darktable", "rawtherapee" to "RawTherapee", "dcraw" to "dcraw_emu / dcraw", "none" to "none")),
        SettingDef("raw.onlyifnojpeg", "Pipeline", "Skip RAW when the camera sent the JPEG", "RAW+JPEG pairs keep the camera's JPEG.", SettingDef.Type.BOOL),
        SettingDef("raw.quality", "Pipeline", "JPEG quality", "1-100 for rendered previews.", SettingDef.Type.NUMBER, placeholder = "92"),
        SettingDef("rename.pattern", "Pipeline", "Rename pattern", "Tokens: {yyyy} {MM} {dd} {HH} {mm} {ss} from EXIF (falls back to the camera date), {base} {ext} {name}, {camera}. Empty = keep names.", placeholder = "{yyyy}{MM}{dd}_{HH}{mm}{ss}_{base}.{ext}"),
        SettingDef("export.dir", "Pipeline", "Export directory", "Finished files are moved here, e.g. a share another tool watches. /export in the container. Empty = files stay in storage.", placeholder = "/export"),
        SettingDef("export.subfolder", "Pipeline", "Export folder layout", "Same tokens as the rename pattern; '/' nests.", placeholder = "{yyyy}-{MM}-{dd}"),
        SettingDef("exif.tool", "Pipeline", "exiftool binary", "Name or path.", placeholder = "exiftool"),
        SettingDef("tools.path", "Pipeline", "Extra tool directories", "Searched before PATH, separated by ':' (Linux) or ';' (Windows)."),

        SettingDef("webhook.url", "Hooks", "Webhook URL", "Receives a JSON summary after every session (e.g. a Home Assistant webhook).", placeholder = "http://homeassistant.lan:8123/api/webhook/cambackup"),
        SettingDef("webhook.token", "Hooks", "Webhook bearer token", "Sent as Authorization: Bearer.", secret = true),
        SettingDef("hook.command", "Hooks", "Command after every session", "Runs in the server's shell with CAMBACKUP_EVENT, CAMBACKUP_CAMERA, CAMBACKUP_SYSOFF, CAMBACKUP_STORED, CAMBACKUP_SKIPPED and CAMBACKUP_FILES (one path per line)."),
        SettingDef("sysoff.command", "Hooks", "Command when the camera asks to shut down", "The camera's \"Shut down PC after backup\" option."),

        SettingDef("ui.enabled", "Web UI", "Status page and pairing download", "", SettingDef.Type.BOOL, restart = true),
        SettingDef("ui.settings", "Web UI", "This settings page", "Switching it off here needs a config file edit to get it back.", SettingDef.Type.BOOL, restart = true),
    )

    val sections: List<String> = all.map { it.section }.distinct()
    fun byKey(key: String): SettingDef? = all.firstOrNull { it.key == key }

    /** application/x-www-form-urlencoded body -> key/value pairs (last wins). */
    fun parseForm(body: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (pair in body.split('&')) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            val k = URLDecoder.decode(if (i >= 0) pair.substring(0, i) else pair, "UTF-8")
            val v = URLDecoder.decode(if (i >= 0) pair.substring(i + 1) else "", "UTF-8")
            map[k] = v
        }
        return map
    }

    /**
     * Turns a submitted form into config updates: unknown keys are dropped, keys that were not
     * sent stay untouched (the page sends a hidden "false" for every checkbox, so an unticked box
     * arrives as "false"), numbers are validated. Returns the updates and the error messages.
     */
    fun updatesFrom(form: Map<String, String>): Pair<Map<String, String>, List<String>> {
        val updates = LinkedHashMap<String, String>()
        val errors = ArrayList<String>()
        for (def in all) {
            val raw = form[def.key] ?: continue
            when (def.type) {
                SettingDef.Type.BOOL -> updates[def.key] = if (raw == "true" || raw == "on") "true" else "false"
                SettingDef.Type.NUMBER -> {
                    val v = raw?.trim().orEmpty()
                    if (v.isEmpty()) updates[def.key] = ""
                    else if (v.toIntOrNull() == null) errors += "${def.label}: '$v' is not a number"
                    else updates[def.key] = v
                }
                else -> updates[def.key] = raw.trim()
            }
        }
        return updates to errors
    }
}

/** The settings page: one form over the whole catalogue, environment values read-only with a badge. */
object SettingsPage {
    private fun esc(s: String) = UpnpXml.escape(s)

    fun render(config: Config, notice: String? = null, errors: List<String> = emptyList(), scheme: String? = null): String {
        val sb = StringBuilder()
        sb.append(Html.head("Settings", scheme = scheme))
        sb.append(Html.header("settings"))
        sb.append("<main><h1>Settings</h1>")
        sb.append("<div style=\"color:var(--muted);font-size:.9em\">Edits <code>${esc(config.file.path)}</code>. Values from the environment (your compose file) are shown read-only with a badge; the environment always wins. Settings marked <span class=\"badge restart\">restart</span> need a restart of the server, everything else applies to the next file or session.</div>")
        if (config.apiToken == null) sb.append("<p class=\"notice warn\">No token is set: anyone on the network can change these settings and run commands on this server. Set one below.</p>")
        if (notice != null) sb.append("<p class=\"notice\">${esc(notice)}</p>")
        for (e in errors) sb.append("<p class=\"notice bad\">${esc(e)}</p>")
        val envKeys = SettingsCatalog.all.filter { config.envValue(it.key) != null }
        if (envKeys.isNotEmpty()) {
            sb.append("<form method=\"post\" action=\"/settings/adopt-env\" style=\"margin:12px 0\">")
            sb.append("<button class=\"btn secondary\" type=\"submit\">Copy the ${envKeys.size} environment value${if (envKeys.size == 1) "" else "s"} into the file</button>")
            sb.append("<span style=\"color:var(--muted);font-size:.85em;margin-left:8px\">Afterwards the entries can be removed from the compose file; the file carries on.</span></form>")
        }
        sb.append("<form method=\"post\" action=\"/settings\">")
        for (section in SettingsCatalog.sections) {
            sb.append("<h2>${esc(section)}</h2><table class=\"settings\">")
            for (def in SettingsCatalog.all.filter { it.section == section }) sb.append(row(config, def))
            sb.append("</table>")
        }
        sb.append("<p><button class=\"btn\" type=\"submit\">Save</button> <a class=\"btn secondary\" href=\"/\">Back to status</a></p></form>")
        sb.append(Html.footer())
        return sb.toString()
    }

    private fun row(config: Config, def: SettingDef): String {
        val env = config.envValue(def.key)
        val fileValue = config.fileValue(def.key) ?: ""
        val effective = config[def.key] ?: ""
        val sb = StringBuilder("<tr><td class=\"k\"><label for=\"${esc(def.key)}\">${esc(def.label)}</label><br><code>${esc(def.key)}</code>")
        if (def.restart) sb.append(" <span class=\"badge restart\">restart</span>")
        sb.append("</td><td>")
        if (env != null) {
            sb.append("<span class=\"badge env\">environment</span> <code>${esc(if (def.secret) "••••••" else env)}</code>")
            if (fileValue.isNotEmpty() && fileValue != env) sb.append("<div style=\"color:var(--muted);font-size:.85em\">file has <code>${esc(if (def.secret) "••••••" else fileValue)}</code>, ignored</div>")
        } else {
            when (def.type) {
                SettingDef.Type.BOOL -> sb.append("<input type=\"hidden\" name=\"${esc(def.key)}\" value=\"false\"><input type=\"checkbox\" id=\"${esc(def.key)}\" name=\"${esc(def.key)}\" value=\"true\"${if (effective.equals("true", true) || effective == "1" || effective.equals("yes", true)) " checked" else ""}>")
                SettingDef.Type.SELECT -> {
                    val known = def.options.any { it.first.equals(fileValue, true) } || fileValue.isEmpty()
                    sb.append("<select id=\"${esc(def.key)}\" name=\"${esc(def.key)}\">")
                    for ((v, label) in def.options) sb.append("<option value=\"${esc(v)}\"${if (v.equals(fileValue, true) || (fileValue.isEmpty() && v == def.options.first().first)) " selected" else ""}>${esc(label)}</option>")
                    if (!known) sb.append("<option value=\"${esc(fileValue)}\" selected>${esc(fileValue)} (custom)</option>")
                    sb.append("</select>")
                }
                SettingDef.Type.NUMBER -> sb.append("<input type=\"number\" id=\"${esc(def.key)}\" name=\"${esc(def.key)}\" value=\"${esc(fileValue)}\" placeholder=\"${esc(def.placeholder)}\">")
                SettingDef.Type.TEXT -> sb.append("<input type=\"${if (def.secret) "password" else "text"}\" id=\"${esc(def.key)}\" name=\"${esc(def.key)}\" value=\"${esc(fileValue)}\" placeholder=\"${esc(def.placeholder)}\" spellcheck=\"false\">")
            }
        }
        if (def.help.isNotEmpty()) sb.append("<div class=\"help\">${esc(def.help)}</div>")
        sb.append("</td></tr>")
        return sb.toString()
    }
}

/** Shared page chrome for the status and settings pages (tokens: docs/BRANDING.md). */
object Html {
    /** [scheme] = "light" | "dark" forces the colour scheme (query parameter `?scheme=`), null follows the system. */
    fun head(title: String, refreshSeconds: Int = 0, scheme: String? = null): String = """<!doctype html><html lang="en"${if (scheme == "light" || scheme == "dark") " data-scheme=\"$scheme\"" else ""}><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
${if (refreshSeconds > 0) "<meta http-equiv=\"refresh\" content=\"$refreshSeconds\">" else ""}<title>Samsung Camera Backup - $title</title>
<style>
/* tokens: docs/BRANDING.md (same values as the Android app); data-scheme on <html> overrides the system */
:root{--bg:#fff;--fg:#1a1a1a;--muted:#666;--line:#e3e3e3;--accent:#2a6fdb;--on-accent:#fff;--accent-soft:#d6e4fa;--ok:#2e7d32;--bad:#c62828;--card:#f7f7f7}
@media(prefers-color-scheme:dark){:root:not([data-scheme=light]){--bg:#121212;--fg:#eee;--muted:#aaa;--line:#333;--accent:#8ab4f8;--on-accent:#0b2a5b;--accent-soft:#1b3a6b;--ok:#81c995;--bad:#f28b82;--card:#1e1e1e}}
:root[data-scheme=dark]{--bg:#121212;--fg:#eee;--muted:#aaa;--line:#333;--accent:#8ab4f8;--on-accent:#0b2a5b;--accent-soft:#1b3a6b;--ok:#81c995;--bad:#f28b82;--card:#1e1e1e}
body{font:15px/1.5 system-ui,sans-serif;margin:0;background:var(--bg);color:var(--fg)}
header{background:var(--accent);color:var(--on-accent);padding:14px 16px;font-size:1.25em;font-weight:500;display:flex;align-items:center;gap:12px}
header span{opacity:.8;font-weight:400;font-size:.8em}header nav{margin-left:auto;font-size:.75em;font-weight:400}header nav a{color:var(--on-accent);text-decoration:none;margin-left:14px;opacity:.85}header nav a.current{opacity:1;text-decoration:underline}
main{max-width:1000px;margin:auto;padding:16px}
h1{font-size:1.4em;margin:.2em 0}h2{font-size:1.05em;margin:1.4em 0 .4em;color:var(--muted);text-transform:uppercase;letter-spacing:.04em}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px}
.tile{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:10px 12px}
.tile b{display:block;font-size:1.4em}.tile span{color:var(--muted);font-size:.85em}
table{border-collapse:collapse;width:100%}td,th{text-align:left;padding:4px 8px;border-bottom:1px solid var(--line);font-size:.9em;vertical-align:top}
code,pre{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.85em}pre{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:10px;overflow:auto;max-height:420px;white-space:pre-wrap}
a{color:var(--accent)}.ok{color:var(--ok)}.bad{color:var(--bad)}
.btn{display:inline-block;padding:8px 14px;border:1px solid var(--accent);border-radius:8px;text-decoration:none;margin:4px 8px 4px 0;background:var(--accent);color:var(--on-accent);font:inherit;cursor:pointer}
.btn.secondary{background:transparent;color:var(--accent)}
.check{color:var(--accent);font-weight:500;white-space:nowrap}.dot{display:inline-block;width:12px;height:12px;border-radius:50%;background:var(--ok);margin-right:8px;vertical-align:middle}
.badge{display:inline-block;font-size:.72em;padding:1px 7px;border-radius:10px;background:var(--accent-soft);color:var(--fg);vertical-align:middle;text-transform:uppercase;letter-spacing:.03em}
.badge.restart{background:transparent;border:1px solid var(--line);color:var(--muted)}
.notice{background:var(--accent-soft);border-radius:8px;padding:10px 12px}.notice.warn{border-left:4px solid var(--bad)}.notice.bad{color:var(--bad)}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:16px;margin:16px 0}
.card .state{font-size:1.4em;font-weight:500}.card .hint{color:var(--muted);margin-top:4px}.card .dest{color:var(--muted);font-size:.9em;margin-top:12px}
table.files td{padding:8px}table.files td.check{width:40px;font-size:1.15em}
form.rename{display:flex;gap:6px;align-items:center;margin-top:6px}form.rename input[type=text]{max-width:260px;padding:5px 8px}form.rename .btn{margin:0;padding:5px 10px}
table.files .name{font-size:1.05em}
table.settings td.k{width:34%}table.settings input[type=text],table.settings input[type=password],table.settings input[type=number],table.settings select{width:100%;box-sizing:border-box;padding:7px 9px;border:1px solid var(--line);border-radius:6px;background:var(--bg);color:var(--fg);font:inherit;font-size:.95em}
.help{color:var(--muted);font-size:.85em;margin-top:3px}
</style></head><body>"""

    fun header(current: String): String =
        "<header>Samsung Camera Backup<span>home server</span><nav><a href=\"/\"${if (current == "status") " class=\"current\"" else ""}>Status</a><a href=\"/settings\"${if (current == "settings") " class=\"current\"" else ""}>Settings</a><a href=\"/api/v1/status\">JSON</a></nav></header>"

    fun footer(): String = "<p style=\"color:var(--muted);font-size:.85em\">Samsung Camera Backup is an independent project, not affiliated with Samsung.</p></main></body></html>"
}
