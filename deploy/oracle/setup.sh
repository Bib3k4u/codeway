#!/usr/bin/env bash
# One-time (and safe to re-run) setup of the Codeway backend on Oracle Cloud, Ubuntu 24.04 ARM. Run as root:
#   sudo bash setup.sh            (from the folder deploy.sh uploaded: setup.sh, codeway.service, caddy-host.sh, updater/, sandbox/)
set -euo pipefail
KIT="$(cd "$(dirname "$0")" && pwd)"
export DEBIAN_FRONTEND=noninteractive

echo "==> Packages: Java 21, Docker, firewall persistence"
apt-get update -qq
apt-get install -y -qq openjdk-21-jre-headless docker.io curl python3 iptables-persistent >/dev/null
systemctl enable --now docker >/dev/null

echo "==> Host firewall: open 80 and 443"
# Oracle's Ubuntu images ship iptables rules that REJECT everything except SSH, on top of the cloud security list.
for port in 80 443; do
  iptables -C INPUT -p tcp --dport "$port" -m state --state NEW -j ACCEPT 2>/dev/null \
    || iptables -I INPUT 5 -p tcp --dport "$port" -m state --state NEW -j ACCEPT
done
netfilter-persistent save >/dev/null

echo "==> Swap (2 GB) as headroom for compiler bursts"
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
  grep -q '/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "==> Service user and folders"
id codeway &>/dev/null || useradd --system --home-dir /opt/codeway --shell /usr/sbin/nologin codeway
usermod -aG docker codeway            # lets the backend start sandbox containers
mkdir -p /opt/codeway/tmp /opt/codeway/releases
chown -R codeway:codeway /opt/codeway

echo "==> Sandbox image for learner code"
bash "$KIT/sandbox/ensure.sh"

echo "==> Config and systemd unit"
if [ ! -f /opt/codeway/codeway.env ]; then
  install -o codeway -g codeway -m 600 "$KIT/codeway.env.example" /opt/codeway/codeway.env
  echo "   Created /opt/codeway/codeway.env from the example: fill it in before starting."
fi
install -m 644 "$KIT/codeway.service" /etc/systemd/system/codeway.service

echo "==> HTTPS: Caddy, re-pointed at the current public IP on every boot"
mkdir -p /opt/caddy
install -m 755 "$KIT/caddy-host.sh" /opt/caddy/caddy-host.sh
install -m 644 "$KIT/codeway-caddy-host.service" /etc/systemd/system/codeway-caddy-host.service
touch /opt/caddy/Caddyfile
if ! docker inspect caddy >/dev/null 2>&1; then
  docker pull -q caddy:2.11.7 >/dev/null
  docker run -d --name caddy --restart unless-stopped --network host --memory 256m \
    -v /opt/caddy/Caddyfile:/etc/caddy/Caddyfile:ro -v caddy_data:/data -v caddy_config:/config caddy:2.11.7 >/dev/null
fi

echo "==> Auto-update from GitHub releases (checks every 2 minutes)"
install -m 755 "$KIT/updater/codeway-update.sh" /opt/codeway/codeway-update.sh
install -m 644 "$KIT/updater/codeway-update.service" /etc/systemd/system/codeway-update.service
install -m 644 "$KIT/updater/codeway-update.timer" /etc/systemd/system/codeway-update.timer

systemctl daemon-reload
systemctl enable codeway codeway-caddy-host.service codeway-update.timer >/dev/null 2>&1
systemctl start codeway-update.timer
bash /opt/caddy/caddy-host.sh
echo "==> Setup done"
