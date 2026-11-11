#!/usr/bin/env bash
# Build the backend locally and deploy it to the EC2 instance. Safe to re-run for every update.
#   ./deploy/ec2/deploy.sh ec2-user@<public-ip> ~/.ssh/<key>.pem
set -euo pipefail
HOST="${1:?usage: deploy.sh ec2-user@<public-ip> [path/to/key.pem]}"
KEY="${2:-}"
SSH=(ssh -o StrictHostKeyChecking=accept-new); SCP=(scp -o StrictHostKeyChecking=accept-new)
if [ -n "$KEY" ]; then SSH+=(-i "$KEY"); SCP+=(-i "$KEY"); fi
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

echo "==> Building backend jar"
(cd "$ROOT/backend" && mvn -q -DskipTests package)
JAR="$(ls "$ROOT"/backend/target/codeway-backend-*.jar | head -1)"

echo "==> Uploading"
"${SSH[@]}" "$HOST" 'mkdir -p /tmp/codeway-deploy'
"${SCP[@]}" -q "$ROOT"/deploy/ec2/{setup.sh,codeway.service,codeway.env.example} "$HOST":/tmp/codeway-deploy/
"${SCP[@]}" -q "$JAR" "$HOST":/tmp/codeway-deploy/codeway-backend.jar
if [ -f "$ROOT/deploy/ec2/codeway.env" ]; then
  echo "    (also uploading deploy/ec2/codeway.env)"
  "${SCP[@]}" -q "$ROOT/deploy/ec2/codeway.env" "$HOST":/tmp/codeway-deploy/codeway.env
fi

echo "==> Installing on the server"
"${SSH[@]}" "$HOST" 'sudo bash -s' <<'REMOTE'
set -euo pipefail
bash /tmp/codeway-deploy/setup.sh
if [ -f /tmp/codeway-deploy/codeway.env ]; then
  install -o codeway -g codeway -m 600 /tmp/codeway-deploy/codeway.env /opt/codeway/codeway.env
fi
install -o codeway -g codeway -m 644 /tmp/codeway-deploy/codeway-backend.jar /opt/codeway/codeway-backend.jar
rm -rf /tmp/codeway-deploy
if ! grep -qE '^DB_URL=.+' /opt/codeway/codeway.env; then
  echo "!! /opt/codeway/codeway.env has no DB_URL yet. Fill it in (sudo nano /opt/codeway/codeway.env), then: sudo systemctl restart codeway"
  exit 0
fi
systemctl restart codeway
echo "==> Waiting for the backend to come up"
for i in $(seq 1 60); do curl -fs localhost:8080/api/health >/dev/null && { echo "   healthy"; break; }; sleep 2; done
journalctl -u codeway -n 200 --no-pager | grep -E '\[seed\]|Started Codeway|ERROR' | tail -8 || true
REMOTE
echo "==> Done. Next: ./deploy/ec2/smoke-test.sh http://<public-ip>:8080"
