# Security

## Reporting a vulnerability

Please do not open a public issue for security problems. Use GitHub's private vulnerability
reporting on this repository ("Security" tab, "Report a vulnerability"). You should get a
first response within a week.

## Deployment notes

- The camera protocol is plain HTTP without authentication by design (the camera cannot
  authenticate). Keep the server on your LAN; do not expose port 52235 to the internet.
- Set `api.token` (`CAMBACKUP_API_TOKEN`) so only your phone can upload through the backend
  API. Without it, anyone on the network can push files into the data directory.
- The phone talks to the server over plain HTTP too. For use outside the LAN put a reverse
  proxy with TLS in front of the API and keep the camera path on the LAN.
- `hook.command` and `sysoff.command` run arbitrary shell commands from the configuration
  file; protect the data directory accordingly.
- The Android app stores the backend token in its private preferences; it is never logged.
