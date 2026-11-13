#!/usr/bin/env bash
# Build the backend and install it on the Oracle server (first-time setup included). Safe to re-run.
#   ./deploy/oracle/deploy.sh ubuntu@<ip> ~/.ssh/codeway-oci [--postgres]
# Day-to-day updates don't need this: pushes to master are picked up by the server's updater (see README).
set -euo pipefail
HOST="${1:?usage: deploy.sh ubuntu@<ip> <key> [--postgres]}"; KEY="${2:?key}"; PG="${3:-}"
SSH=(ssh -i "$KEY" -o StrictHostKeyChecking=accept-new); SCP=(scp -i "$KEY" -o StrictHostKeyChecking=accept-new -q)
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

echo "==> Building backend jar"
(cd "$ROOT/backend" && mvn -q -DskipTests package)
JAR="$(ls "$ROOT"/backend/target/codeway-backend-*.jar | head -1)"

echo "==> Uploading"
"${SSH[@]}" "$HOST" 'rm -rf /tmp/codeway-deploy && mkdir -p /tmp/codeway-deploy'
"${SCP[@]}" -r "$ROOT"/deploy/oracle/{setup.sh,harden.sh,gvisor.sh,postgres.sh,codeway.service,caddy-host.sh,codeway-caddy-host.service,codeway.env.example,updater} \
  "$ROOT/deploy/ec2/sandbox" "$HOST":/tmp/codeway-deploy/
"${SCP[@]}" "$JAR" "$HOST":/tmp/codeway-deploy/codeway-backend.jar
"${SCP[@]}" "$ROOT/deploy/oracle/codeway.env" "$HOST":/tmp/codeway-deploy/codeway.env
[ -f "$ROOT/deploy/oracle/backup.env" ] && "${SCP[@]}" "$ROOT/deploy/oracle/backup.env" "$HOST":/tmp/codeway-deploy/backup.env

echo "==> Installing on the server"
"${SSH[@]}" "$HOST" "sudo PG='$PG' bash -s" <<'REMOTE'
set -euo pipefail
K=/tmp/codeway-deploy
bash $K/setup.sh
# keep a server-side DB switch (postgres.sh migrate) if it was already made
if [ -f /opt/codeway/codeway.env ] && grep -q '^DB_URL=jdbc:postgresql://127.0.0.1' /opt/codeway/codeway.env; then
  grep -E '^(DB_URL|DB_USER|DB_PASSWORD)=' /opt/codeway/codeway.env > $K/db.keep
  install -o codeway -g codeway -m 600 $K/codeway.env /opt/codeway/codeway.env
  while IFS= read -r l; do k=${l%%=*}; sed -i "s#^$k=.*#$(printf '%s' "$l" | sed 's/[#&]/\\&/g')#" /opt/codeway/codeway.env; done < $K/db.keep
else
  install -o codeway -g codeway -m 600 $K/codeway.env /opt/codeway/codeway.env
fi
[ -f $K/backup.env ] && install -m 600 $K/backup.env /opt/codeway/backup.env
install -o codeway -g codeway -m 644 $K/codeway-backend.jar /opt/codeway/codeway-backend.jar
systemctl restart codeway
echo "==> Waiting for the backend"
for i in $(seq 1 90); do curl -fs localhost:8080/api/health >/dev/null && { echo "   healthy"; break; }; sleep 2; done
journalctl -u codeway -n 200 --no-pager | grep -E '\[seed\]|Started Codeway|ERROR' | tail -6 || true
if [ "$PG" = "--postgres" ]; then bash $K/postgres.sh setup && bash $K/postgres.sh migrate; fi
rm -rf $K
REMOTE
echo "==> Done"
