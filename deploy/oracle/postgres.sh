#!/usr/bin/env bash
# Optional: run Postgres on the server instead of Neon, with nightly backups. Run as root on the server.
#   sudo bash postgres.sh setup      start Postgres 17 (Docker, localhost only), nightly backup timer
#   sudo bash postgres.sh migrate    copy all data from the Neon database in codeway.env, then point the backend here
#   sudo bash postgres.sh backup     take a backup now
#   sudo bash postgres.sh restore F  restore backup file F (replaces the current data)
#   sudo bash postgres.sh use-neon   switch the backend back to the Neon settings saved during migrate
#
# Backups: pg_dump (custom format) to /opt/backups at 02:30 server time, 7 kept locally, and each one uploaded to
# Oracle Object Storage when /opt/codeway/backup.env has BACKUP_PAR_URL (a write-only pre-authenticated bucket URL).
set -euo pipefail
ENV=/opt/codeway/codeway.env
DBENV=/opt/codeway/db.env
IMAGE=postgres:17
say() { echo "==> $*"; }

setup() {
  mkdir -p /opt/codeway/pgdata /opt/backups
  if [ ! -f "$DBENV" ]; then
    echo "POSTGRES_PASSWORD=$(openssl rand -hex 24)" > "$DBENV"; chmod 600 "$DBENV"
  fi
  if ! docker inspect codeway-db >/dev/null 2>&1; then
    docker pull -q "$IMAGE" >/dev/null
    docker run -d --name codeway-db --restart unless-stopped --env-file "$DBENV" -e POSTGRES_USER=codeway -e POSTGRES_DB=codeway \
      -v /opt/codeway/pgdata:/var/lib/postgresql/data -p 127.0.0.1:5432:5432 --memory 2g "$IMAGE" >/dev/null
    say "Postgres started (127.0.0.1:5432 only)"
  fi
  for _ in $(seq 1 30); do docker exec codeway-db pg_isready -U codeway -q && break; sleep 1; done
  install -m 755 "$0" /opt/codeway/postgres.sh
  cat > /etc/systemd/system/codeway-backup.service <<'UNIT'
[Unit]
Description=Back up the Codeway database
Requires=docker.service
After=docker.service

[Service]
Type=oneshot
ExecStart=/bin/bash /opt/codeway/postgres.sh backup
UNIT
  cat > /etc/systemd/system/codeway-backup.timer <<'UNIT'
[Unit]
Description=Nightly Codeway database backup

[Timer]
OnCalendar=*-*-* 02:30:00
Persistent=true

[Install]
WantedBy=timers.target
UNIT
  systemctl daemon-reload
  systemctl enable --now codeway-backup.timer >/dev/null 2>&1
  say "Nightly backups scheduled (02:30)"
}

local_url() { echo "jdbc:postgresql://127.0.0.1:5432/codeway"; }

# The Neon connection from codeway.env as a libpq URI, for pg_dump (handles jdbc:, postgresql:// and separate user/password).
neon_uri() {
  python3 - "$ENV" <<'PY'
import sys, urllib.parse
env = {}
for line in open(sys.argv[1]):
    if "=" in line and not line.startswith("#"):
        k, v = line.rstrip("\n").split("=", 1); env[k] = v
url = env.get("DB_URL", "").removeprefix("jdbc:")
p = urllib.parse.urlsplit(url)
user = urllib.parse.unquote(p.username or env.get("DB_USER", ""))
pw = urllib.parse.unquote(p.password or env.get("DB_PASSWORD", ""))
host = p.hostname + (f":{p.port}" if p.port else "")
q = dict(urllib.parse.parse_qsl(p.query)); q.setdefault("sslmode", "require")
print(f"postgresql://{urllib.parse.quote(user)}:{urllib.parse.quote(pw)}@{host}{p.path}?{urllib.parse.urlencode(q)}")
PY
}

migrate() {
  docker inspect codeway-db >/dev/null 2>&1 || setup
  grep -q '^DB_URL=.*neon' "$ENV" || { echo "codeway.env doesn't point at Neon; nothing to migrate"; exit 1; }
  say "Copying data from Neon"
  docker run --rm --network host "$IMAGE" pg_dump --no-owner --no-privileges -Fc "$(neon_uri)" > /opt/backups/neon-before-migration.dump
  docker exec -i codeway-db pg_restore -U codeway -d codeway --no-owner --clean --if-exists < /opt/backups/neon-before-migration.dump || true
  say "Rows per table here:"
  docker exec codeway-db psql -U codeway -d codeway -tAc "select relname||': '||n_live_tup from pg_stat_user_tables order by relname"
  grep -E '^(DB_URL|DB_USER|DB_PASSWORD)=' "$ENV" > /opt/codeway/neon.env.saved; chmod 600 /opt/codeway/neon.env.saved
  pw=$(grep '^POSTGRES_PASSWORD=' "$DBENV" | cut -d= -f2)
  sed -i -e "s#^DB_URL=.*#DB_URL=$(local_url)#" -e "s#^DB_USER=.*#DB_USER=codeway#" -e "s#^DB_PASSWORD=.*#DB_PASSWORD=$pw#" "$ENV"
  systemctl restart codeway
  for _ in $(seq 1 60); do curl -fs localhost:8080/api/health >/dev/null && { say "Backend now uses the local database (Neon settings saved in neon.env.saved)"; return; }; sleep 2; done
  echo "!! Backend not healthy on the local database; switching back to Neon"; use_neon; exit 1
}

use_neon() {
  [ -f /opt/codeway/neon.env.saved ] || { echo "No saved Neon settings"; exit 1; }
  while IFS= read -r line; do k=${line%%=*}; sed -i "s#^$k=.*#$(printf '%s' "$line" | sed 's/[#&]/\\&/g')#" "$ENV"; done < /opt/codeway/neon.env.saved
  systemctl restart codeway; say "Backend points at Neon again"
}

backup() {
  mkdir -p /opt/backups
  f="/opt/backups/codeway-$(date -u +%Y-%m-%dT%H%M).dump"
  docker exec codeway-db pg_dump -U codeway -d codeway -Fc > "$f"
  say "Backup $f ($(du -h "$f" | cut -f1))"
  if [ -f /opt/codeway/backup.env ]; then
    . /opt/codeway/backup.env
    [ -n "${BACKUP_PAR_URL:-}" ] && curl -fsS --max-time 300 -T "$f" "${BACKUP_PAR_URL}$(basename "$f")" >/dev/null && say "Uploaded to Object Storage"
  fi
  ls -1t /opt/backups/codeway-*.dump 2>/dev/null | tail -n +8 | xargs -r rm -f
}

restore() {
  [ -f "${1:-}" ] || { echo "usage: postgres.sh restore /opt/backups/<file>.dump"; exit 1; }
  systemctl stop codeway
  docker exec -i codeway-db pg_restore -U codeway -d codeway --no-owner --clean --if-exists < "$1" || true
  systemctl start codeway; say "Restored $1"
}

case "${1:-}" in
  setup) setup ;; migrate) migrate ;; backup) backup ;; restore) restore "${2:-}" ;; use-neon) use_neon ;;
  *) sed -n '2,10p' "$0"; exit 1 ;;
esac
