#!/usr/bin/env bash
# Runs at every boot (codeway-caddy-host.service): points Caddy at <public-ip>.sslip.io for the instance's current
# public IP, so HTTPS keeps working after a stop/start gives the server a new address. Caddy gets a certificate for
# the new name by itself. The backend URL to use in the frontend is printed and saved to /opt/caddy/url.
set -euo pipefail
TOKEN=$(curl -fsS -X PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 60")
IP=$(curl -fsS -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/public-ipv4)
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
