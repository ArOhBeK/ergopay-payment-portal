#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID:-$(id -u)} -ne 0 ]]; then
  echo "This script must be run as root (try prefixing with sudo)." >&2
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required to talk to the DuckDNS API." >&2
  exit 1
fi

SUBDOMAIN=""
TOKEN=""
INTERVAL_MINUTES=5

usage() {
  cat <<USAGE
Usage: $0 --subdomain your-subdomain --token YOUR_TOKEN [--interval 5]

Options:
  --subdomain   DuckDNS subdomain to update (required).
  --token       DuckDNS API token (required).
  --interval    How often (in minutes) to update the record. Default: 5.
  --help        Show this message.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --subdomain)
      SUBDOMAIN="$2"
      shift 2
      ;;
    --token)
      TOKEN="$2"
      shift 2
      ;;
    --interval)
      INTERVAL_MINUTES="$2"
      shift 2
      ;;
    --help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ -z "$SUBDOMAIN" || -z "$TOKEN" ]]; then
  echo "Both --subdomain and --token are required." >&2
  usage >&2
  exit 1
fi

INSTALL_DIR="/opt/duckdns"
SCRIPT_PATH="${INSTALL_DIR}/update.sh"
SERVICE_PATH="/etc/systemd/system/duckdns-update.service"
TIMER_PATH="/etc/systemd/system/duckdns-update.timer"

install -d -m 755 "$INSTALL_DIR"

cat <<SCRIPT >"$SCRIPT_PATH"
#!/usr/bin/env bash
set -euo pipefail
curl -fsS "https://www.duckdns.org/update?domains=${SUBDOMAIN}&token=${TOKEN}&ip="
SCRIPT
chmod 700 "$SCRIPT_PATH"

cat <<SERVICE >"$SERVICE_PATH"
[Unit]
Description=DuckDNS updater for ${SUBDOMAIN}
Wants=network-online.target
After=network-online.target

[Service]
Type=oneshot
ExecStart=${SCRIPT_PATH}
SERVICE

cat <<TIMER >"$TIMER_PATH"
[Unit]
Description=Run DuckDNS updater every ${INTERVAL_MINUTES} minutes

[Timer]
OnBootSec=1min
OnUnitActiveSec=${INTERVAL_MINUTES}min
Unit=duckdns-update.service

[Install]
WantedBy=timers.target
TIMER

systemctl daemon-reload
systemctl enable --now duckdns-update.timer

systemctl start duckdns-update.service || true

status_output=$(systemctl status duckdns-update.timer --no-pager || true)
echo "DuckDNS timer installed. Current status:"
echo "$status_output"
