#!/usr/bin/env bash
# Disk safety and hardening for the Oracle server. Safe to re-run. Run as root: sudo bash harden.sh
#
# - Learner run folders live on a size-limited in-memory disk, so a runaway program can't fill the real disk.
# - Logs (journald, Docker) are capped, stale run folders are cleaned hourly, and low disk space is logged.
# - Ubuntu installs security updates and reboots at a quiet hour when a kernel update needs it.
# - rpcbind (not needed) is switched off.
set -euo pipefail
say() { echo "==> $*"; }

say "Run folders: 2 GB in-memory disk at /opt/codeway/tmp"
mkdir -p /opt/codeway/tmp
if ! findmnt -n /opt/codeway/tmp >/dev/null 2>&1; then
  was_active=$(systemctl is-active codeway 2>/dev/null || true)
  [ "$was_active" = active ] && systemctl stop codeway
  find /opt/codeway/tmp -mindepth 1 -delete
  U=$(id -u codeway); G=$(id -g codeway)
  grep -q ' /opt/codeway/tmp ' /etc/fstab || echo "tmpfs /opt/codeway/tmp tmpfs size=2g,mode=0755,uid=$U,gid=$G,nosuid,nodev 0 0" >> /etc/fstab
  systemctl daemon-reload
  mount /opt/codeway/tmp
  [ "$was_active" = active ] && systemctl start codeway
fi

say "Journal capped at 300 MB / 2 weeks"
mkdir -p /etc/systemd/journald.conf.d
cat > /etc/systemd/journald.conf.d/codeway.conf <<'CONF'
[Journal]
SystemMaxUse=300M
RuntimeMaxUse=100M
MaxRetentionSec=2week
CONF
systemctl restart systemd-journald

say "Docker: capped container logs, containers survive daemon restarts"
changed=$(python3 - <<'PY'
import json, os
p = "/etc/docker/daemon.json"
d = json.load(open(p)) if os.path.exists(p) else {}
want = {"log-driver": "json-file", "log-opts": {"max-size": "10m", "max-file": "3"}, "live-restore": True}
new = {**d, **want}
if new != d:
    json.dump(new, open(p, "w"), indent=2)
print("1" if new != d else "0")
PY
)
[ "$changed" = 1 ] && systemctl restart docker
if docker inspect caddy >/dev/null 2>&1 && ! docker inspect -f '{{.HostConfig.LogConfig.Config}}' caddy | grep -q max-size; then
  docker rm -f caddy >/dev/null
  docker run -d --name caddy --restart unless-stopped --network host --memory 256m --log-opt max-size=10m --log-opt max-file=3 \
    -v /opt/caddy/Caddyfile:/etc/caddy/Caddyfile:ro -v caddy_data:/data -v caddy_config:/config caddy:2.11.7 >/dev/null
fi

say "Housekeeping every hour: stale run folders, stale containers, dangling images, disk-space warning"
cat > /opt/codeway/housekeeping.sh <<'SH'
#!/usr/bin/env bash
set -uo pipefail
find /opt/codeway/tmp -mindepth 1 -maxdepth 1 -name 'codeway-run-*' -mmin +60 -print0 | xargs -0 -r rm -rf
docker ps -aq --filter name=cw- --filter status=exited | xargs -r docker rm -f >/dev/null 2>&1
docker image prune -f >/dev/null 2>&1
journalctl --vacuum-size=300M >/dev/null 2>&1
apt-get clean >/dev/null 2>&1
used=$(df --output=pcent / | tail -1 | tr -dc 0-9)
[ "$used" -ge 85 ] && logger -p user.err -t codeway-housekeeping "root disk ${used}% full"
echo "root disk ${used}% used; run folders $(du -sh /opt/codeway/tmp | cut -f1) of 2G"
SH
chmod 755 /opt/codeway/housekeeping.sh
cat > /etc/systemd/system/codeway-housekeeping.service <<'UNIT'
[Unit]
Description=Codeway housekeeping (stale run folders, containers, logs)

[Service]
Type=oneshot
ExecStart=/opt/codeway/housekeeping.sh
UNIT
cat > /etc/systemd/system/codeway-housekeeping.timer <<'UNIT'
[Unit]
Description=Hourly Codeway housekeeping

[Timer]
OnCalendar=hourly
RandomizedDelaySec=300
Persistent=true

[Install]
WantedBy=timers.target
UNIT

say "Security updates: automatic, with a reboot at 21:00 UTC (02:30 IST) only when a kernel update needs one"
cat > /etc/apt/apt.conf.d/52codeway-unattended <<'CONF'
Unattended-Upgrade::Automatic-Reboot "true";
Unattended-Upgrade::Automatic-Reboot-Time "21:00";
CONF
systemctl enable --now unattended-upgrades >/dev/null 2>&1 || true

say "Switch off rpcbind (port 111, not needed)"
systemctl disable --now rpcbind.service rpcbind.socket >/dev/null 2>&1 || true

systemctl daemon-reload
systemctl enable --now codeway-housekeeping.timer >/dev/null 2>&1
say "Hardening done"
