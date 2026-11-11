#!/usr/bin/env bash
# One-time (and safe to re-run) server setup for Amazon Linux 2023. Run as root: sudo bash setup.sh
set -euo pipefail
KIT="$(cd "$(dirname "$0")" && pwd)"

echo "==> Swap (2 GB) so a 1 GB instance survives the JVM + compiler"
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
  grep -q '/swapfile' /etc/fstab || echo '/swapfile swap swap defaults 0 0' >> /etc/fstab
fi

echo "==> Java 21 and Docker"
dnf install -y -q java-21-amazon-corretto-headless docker
systemctl enable --now docker

echo "==> Service user and folders"
id codeway &>/dev/null || useradd --system --home-dir /opt/codeway --shell /sbin/nologin codeway
usermod -aG docker codeway            # lets the backend start sandbox containers
mkdir -p /opt/codeway/tmp
chown -R codeway:codeway /opt/codeway

echo "==> Sandbox image for learner code"
bash "$KIT/sandbox/ensure.sh"

echo "==> Config and systemd unit"
if [ ! -f /opt/codeway/codeway.env ]; then
  install -o codeway -g codeway -m 600 "$KIT/codeway.env.example" /opt/codeway/codeway.env
  echo "   Created /opt/codeway/codeway.env from the example: fill it in before starting."
fi
install -m 644 "$KIT/codeway.service" /etc/systemd/system/codeway.service
systemctl daemon-reload
systemctl enable codeway >/dev/null
echo "==> Setup done"
