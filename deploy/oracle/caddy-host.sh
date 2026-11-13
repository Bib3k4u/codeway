#!/usr/bin/env bash
# Runs at every boot (codeway-caddy-host.service): points Caddy at <public-ip>.sslip.io. Oracle's instance metadata
# doesn't include the public IP, so it is looked up from outside. The URL is saved to /opt/caddy/url.
set -euo pipefail
IP=""
for src in https://checkip.amazonaws.com https://ifconfig.me https://api.ipify.org; do
  IP=$(curl -fsS --max-time 5 "$src" | tr -d '[:space:]') && [[ "$IP" =~ ^[0-9]+(\.[0-9]+){3}$ ]] && break || IP=""
done
[ -n "$IP" ] || { echo "Could not find the public IP" >&2; exit 1; }
HOST="${IP//./-}.sslip.io"
mkdir -p /opt/caddy
NEW=$(cat <<CADDY
$HOST {
	encode gzip
	request_body {
		max_size 2MB
	}
	reverse_proxy localhost:8080 {
		transport http {
			response_header_timeout 300s
		}
	}
}
CADDY
)
if [ "$(cat /opt/caddy/Caddyfile 2>/dev/null)" != "$NEW" ]; then
  printf '%s\n' "$NEW" > /opt/caddy/Caddyfile
  docker restart caddy >/dev/null 2>&1 || true
  echo "Caddy now serves https://$HOST"
fi
echo "https://$HOST" > /opt/caddy/url
