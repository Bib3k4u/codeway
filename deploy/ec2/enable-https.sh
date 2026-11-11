#!/usr/bin/env bash
# Put the backend behind HTTPS so a hosted frontend (Vercel) can reach it securely. Safe to re-run.
#   ./deploy/ec2/enable-https.sh
#
# 1. Gives the server a fixed Elastic IP (free while attached to a running instance on the free tier).
# 2. Opens ports 80 and 443 to everyone (80 is only for the Let's Encrypt challenge and redirects to 443).
#    Port 8080 stays limited to your IP.
# 3. Runs Caddy in Docker on the server as a reverse proxy to localhost:8080, with an automatic Let's Encrypt
#    certificate for <ip-with-dashes>.sslip.io (a free DNS name that resolves to the IP; no domain needed).
# Prints the HTTPS URL to use as BACKEND_URL.
set -euo pipefail
export AWS_REGION="${AWS_REGION:-us-east-2}" AWS_PAGER=""
NAME=codeway-backend SG_NAME=codeway-sg EIP_TAG=codeway-backend-ip
CADDY_IMAGE=caddy:2.11.7
KEY_FILE="$HOME/.ssh/codeway.pem"
say() { printf '==> %s\n' "$*"; }

ID="$(aws ec2 describe-instances --filters "Name=tag:Name,Values=$NAME" "Name=instance-state-name,Values=running" \
      --query 'Reservations[0].Instances[0].InstanceId' --output text)"
[ "$ID" != None ] || { echo "!! No running $NAME instance. Run ./deploy/ec2/launch.sh first."; exit 1; }

# 1. Elastic IP
ALLOC="$(aws ec2 describe-addresses --filters "Name=tag:Name,Values=$EIP_TAG" --query 'Addresses[0].AllocationId' --output text)"
if [ "$ALLOC" = None ]; then
  ALLOC="$(aws ec2 allocate-address --domain vpc \
           --tag-specifications "ResourceType=elastic-ip,Tags=[{Key=Name,Value=$EIP_TAG}]" --query AllocationId --output text)"
  say "Allocated Elastic IP"
fi
aws ec2 associate-address --allocation-id "$ALLOC" --instance-id "$ID" --allow-reassociation >/dev/null
IP="$(aws ec2 describe-addresses --allocation-ids "$ALLOC" --query 'Addresses[0].PublicIp' --output text)"
HOSTNAME="${IP//./-}.sslip.io"
say "Fixed IP $IP attached to $ID"

# 2. Public HTTP/HTTPS
SG="$(aws ec2 describe-security-groups --filters "Name=group-name,Values=$SG_NAME" --query 'SecurityGroups[0].GroupId' --output text)"
for port in 80 443; do
  aws ec2 authorize-security-group-ingress --group-id "$SG" \
    --ip-permissions "IpProtocol=tcp,FromPort=$port,ToPort=$port,IpRanges=[{CidrIp=0.0.0.0/0,Description=public-https}]" \
    >/dev/null 2>&1 || true
done
say "Ports 80 and 443 open to the internet (8080 stays limited to your IP)"

# 3. Caddy on the server
SSH=(ssh -i "$KEY_FILE" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10)
for _ in $(seq 1 24); do "${SSH[@]}" -q "ec2-user@$IP" true 2>/dev/null && break; sleep 5; done
"${SSH[@]}" "ec2-user@$IP" "sudo HOSTNAME_FQDN=$HOSTNAME CADDY_IMAGE=$CADDY_IMAGE bash -s" <<'REMOTE'
set -euo pipefail
mkdir -p /opt/caddy
cat > /opt/caddy/Caddyfile <<EOF
$HOSTNAME_FQDN {
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
EOF
docker pull -q "$CADDY_IMAGE" >/dev/null
docker rm -f caddy >/dev/null 2>&1 || true
docker run -d --name caddy --restart unless-stopped --network host --memory 128m \
  -v /opt/caddy/Caddyfile:/etc/caddy/Caddyfile:ro -v caddy_data:/data -v caddy_config:/config \
  "$CADDY_IMAGE" >/dev/null
echo "   caddy started"
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
