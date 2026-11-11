#!/usr/bin/env bash
# Put the backend behind HTTPS so a hosted frontend (Vercel) can reach it securely. Safe to re-run.
#   ./deploy/ec2/enable-https.sh
#
# 1. Opens ports 80 and 443 to everyone (80 is only for the Let's Encrypt challenge and redirects to 443).
#    Port 8080 stays limited to your IP.
# 2. Runs Caddy in Docker on the server as a reverse proxy to localhost:8080, with an automatic Let's Encrypt
#    certificate for <ip-with-dashes>.sslip.io (a free DNS name that resolves to the IP; no domain needed).
# 3. Installs codeway-caddy-host.service, which re-points Caddy at the current public IP on every boot. The server
#    uses its automatic public IP (no Elastic IP), so the URL changes after a stop/start:
#    ./deploy/ec2/launch.sh url prints the current one.
# Prints the HTTPS URL to use as BACKEND_URL.
set -euo pipefail
export AWS_REGION="${AWS_REGION:-us-east-2}" AWS_PAGER=""
NAME=codeway-backend SG_NAME=codeway-sg
CADDY_IMAGE=caddy:2.11.7
KEY_FILE="$HOME/.ssh/codeway.pem"
HERE="$(cd "$(dirname "$0")" && pwd)"
say() { printf '==> %s\n' "$*"; }

IP="$(aws ec2 describe-instances --filters "Name=tag:Name,Values=$NAME" "Name=instance-state-name,Values=running" \
      --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)"
[ "$IP" != None ] || { echo "!! No running $NAME instance with a public IP. Run ./deploy/ec2/launch.sh first."; exit 1; }
HOSTNAME="${IP//./-}.sslip.io"

# 1. Public HTTP/HTTPS
SG="$(aws ec2 describe-security-groups --filters "Name=group-name,Values=$SG_NAME" --query 'SecurityGroups[0].GroupId' --output text)"
for port in 80 443; do
  aws ec2 authorize-security-group-ingress --group-id "$SG" \
    --ip-permissions "IpProtocol=tcp,FromPort=$port,ToPort=$port,IpRanges=[{CidrIp=0.0.0.0/0,Description=public-https}]" \
    >/dev/null 2>&1 || true
done
say "Ports 80 and 443 open to the internet (8080 stays limited to your IP)"

# 2 + 3. Caddy on the server, re-pointed at the current IP on every boot
SSH=(ssh -i "$KEY_FILE" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10)
for _ in $(seq 1 24); do "${SSH[@]}" -q "ec2-user@$IP" true 2>/dev/null && break; sleep 5; done
scp -q -i "$KEY_FILE" -o StrictHostKeyChecking=accept-new "$HERE/caddy-host.sh" "$HERE/codeway-caddy-host.service" "ec2-user@$IP:/tmp/"
"${SSH[@]}" "ec2-user@$IP" "sudo CADDY_IMAGE=$CADDY_IMAGE bash -s" <<'REMOTE'
set -euo pipefail
mkdir -p /opt/caddy
install -m 755 /tmp/caddy-host.sh /opt/caddy/caddy-host.sh
install -m 644 /tmp/codeway-caddy-host.service /etc/systemd/system/codeway-caddy-host.service
rm -f /tmp/caddy-host.sh /tmp/codeway-caddy-host.service
touch /opt/caddy/Caddyfile
if ! docker inspect caddy >/dev/null 2>&1; then
  docker pull -q "$CADDY_IMAGE" >/dev/null
  docker run -d --name caddy --restart unless-stopped --network host --memory 128m \
    -v /opt/caddy/Caddyfile:/etc/caddy/Caddyfile:ro -v caddy_data:/data -v caddy_config:/config \
    "$CADDY_IMAGE" >/dev/null
fi
systemctl daemon-reload
systemctl enable codeway-caddy-host.service >/dev/null 2>&1
bash /opt/caddy/caddy-host.sh
REMOTE

say "Waiting for the certificate"
for _ in $(seq 1 30); do
  curl -fs "https://$HOSTNAME/api/health" >/dev/null 2>&1 && { say "HTTPS works"; break; }
  sleep 4
done
curl -fs "https://$HOSTNAME/api/health" >/dev/null || { echo "!! HTTPS not answering yet. Check: ./deploy/ec2/launch.sh ssh, then docker logs caddy"; exit 1; }

cat <<EOF

    BACKEND_URL=https://$HOSTNAME
    Set this in Vercel (Settings > Environment Variables) and in frontend/.env.local.
EOF
