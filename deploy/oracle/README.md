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

## AI usage limits

- **Hints:** 5 per learner per rolling hour (`HINTS_PER_HOUR`), counted in the database so restarts don't reset it. The sixth
  is refused before any AI call, with the minutes until the next one opens. The browser can't log hint events itself.
- **Lessons:** written by the AI at most once per concept and language, then served to everyone from the database. Other
  languages are translated from an existing lesson. Concurrent first views share one generation; a failed generation isn't
  retried for 30 minutes; if today's AI allowance is used up nothing is saved, so the real lesson can still be generated later.
- **Per-user and app-wide daily AI caps** (`AI_DAILY_USER_LIMIT`, `AI_DAILY_GLOBAL_LIMIT`) still apply to everything.

## Sandbox and disk safety

- **Sandbox:** every run is a throwaway container: no network, read-only system files, no Linux capabilities, non-root,
  no privilege gain, limits on memory (512 MB), CPU, processes (128) and the size of any file a program writes (32 MB),
  and, with `RUNNER_RUNTIME=runsc`, running on **gVisor** instead of the server's kernel (~1.5x slower, much harder to escape).
- **Run folders** live on a 2 GB in-memory disk (`/opt/codeway/tmp`), so a program that prints or writes nonstop can't fill
  the real disk. Tested: infinite printing stops at 32 MB, a file-writing loop is killed at ~470 MB, real disk unchanged.
- `harden.sh` also caps the journal (300 MB) and Docker logs, cleans stale run folders and containers hourly, logs a warning
  above 85% disk, applies security updates automatically (reboot at 21:00 UTC only if a kernel update needs it), and
  switches off rpcbind. All of it runs from `setup.sh`; re-run it by hand with `sudo bash deploy/oracle/harden.sh` (copied to the server) if needed.
- Billing: the boot volume is a fixed 50 GB (200 GB is free), so a full disk can't raise the bill; it can only stop the service.
- Turn gVisor off (back to plain Docker): clear `RUNNER_RUNTIME` in `/opt/codeway/codeway.env`, `sudo systemctl restart codeway`.
- Not covered: learner code and the hidden tests run in the same process, so a determined learner could read the tests or fake
  passing lines. That only affects their own mastery.

## Staying free

- Keep the server at 2 OCPUs / 12 GB or less (the Always Free A1 limit since June 2026), and boot volumes within 200 GB.
- On a Free Tier account Oracle may reclaim Always Free servers that sit mostly idle for 7 days. Upgrading the account
  to Pay As You Go (console: Billing) avoids that; Always Free resources stay free.
- If `launch.sh` reports no ARM capacity, it already tried all three availability domains: retry later.
