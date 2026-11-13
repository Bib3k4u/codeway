# Backend on Oracle Cloud Always Free

One ARM server (VM.Standard.A1.Flex, **2 OCPUs, 12 GB**, Ubuntu 24.04, 50 GB disk) in **US Midwest (Chicago)**,
next to the Neon database in Ohio. It stays within Oracle's Always Free limits, so the server costs $0.

Same app as on AWS: Spring Boot as a systemd service, learner code in a throwaway Docker sandbox per run
(Java, Python, Node, g++), Caddy for HTTPS at `https://<ip-with-dashes>.sslip.io`.

## What's different from AWS

| | AWS (`deploy/ec2`) | Oracle (`deploy/oracle`) |
|---|---|---|
| Create the server | `deploy/ec2/launch.sh` | `deploy/oracle/launch.sh` |
| OS | Amazon Linux 2023 | Ubuntu 24.04 (setup opens ports 80/443 in Oracle's host firewall too) |
| Auto-deploy | GitHub Actions SSHes in (OIDC + temporary firewall rule) | **Pull**: Actions publishes the release `backend-latest`; the server checks every 2 minutes, verifies the checksum, installs, health-checks, and rolls back if unhealthy. Nothing inbound is needed. |
| Public IP | changes on stop/start | survives stop/start (released only when the server is terminated) |
| Database | Neon | Neon, or Postgres on the server with `--postgres` |

## Cutover (when you say go)

```bash
oci session authenticate --region us-chicago-1 --profile-name codeway   # sign in (browser), lasts 60 min
./deploy/oracle/launch.sh              # network + server + setup + deploy; prints the new BACKEND_URL
# or, to also move the database onto the server with nightly backups:
./deploy/oracle/launch.sh --postgres
```

Then:

1. Check the new backend: `curl https://<ip-with-dashes>.sslip.io/api/health` and try a few challenges.
2. In Vercel, set `BACKEND_URL` to the new URL and redeploy. Update `frontend/.env.local` too.
3. Switch auto-deploy: in `.github/workflows/publish-backend.yml` uncomment the `push` trigger, delete
   `.github/workflows/deploy-backend.yml`, and push. Every backend push now reaches Oracle within ~2 minutes.
4. When the site works on Oracle, delete the AWS server: `./deploy/ec2/launch.sh destroy` (also removes its firewall
   group and key pair). Optionally remove the GitHub deploy role in AWS IAM (`codeway-github-deploy`).

`deploy/oracle/codeway.env` (git-ignored) holds the server config; it was created from the AWS one.

## Day-to-day

| Task | Command |
|---|---|
| Backend URL | `./deploy/oracle/launch.sh url` |
| Shell on the server | `./deploy/oracle/launch.sh ssh` |
| Your IP changed (SSH blocked) | `./deploy/oracle/launch.sh myip` |
| Logs | `sudo journalctl -u codeway -f` (on the server) |
| Auto-update log | `sudo journalctl -u codeway-update -n 50` |
| Deploy right now | `sudo systemctl start codeway-update` (after the publish workflow ran) |
| Edit config | `sudo nano /opt/codeway/codeway.env`, then `sudo systemctl restart codeway` |
| Stop / start | `./deploy/oracle/launch.sh stop` / `start` |

## Self-hosted Postgres (`--postgres`)

- Postgres 17 in Docker, reachable only from the server itself; password generated on the server (`/opt/codeway/db.env`).
- `postgres.sh migrate` copies everything from Neon, then points the backend at the local database. The Neon settings
  are saved, and `sudo bash /opt/codeway/postgres.sh use-neon` switches back.
- Nightly backup at 02:30 (`pg_dump`), 7 kept on the server, each also uploaded to the private Object Storage bucket
  `codeway-backups` through a write-only upload URL (`deploy/oracle/backup.env`, git-ignored; expires in a year).
- Backup now: `sudo bash /opt/codeway/postgres.sh backup`. Restore: `sudo bash /opt/codeway/postgres.sh restore /opt/backups/<file>.dump`.

## Staying free

- Keep the server at 2 OCPUs / 12 GB or less (the Always Free A1 limit since June 2026), and boot volumes within 200 GB.
- On a Free Tier account Oracle may reclaim Always Free servers that sit mostly idle for 7 days. Upgrading the account
  to Pay As You Go (console: Billing) avoids that; Always Free resources stay free.
- If `launch.sh` reports no ARM capacity, it already tried all three availability domains: retry later.
