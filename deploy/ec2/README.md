# Deploy the backend to EC2 (test server)

A single small instance running the Spring Boot backend as a systemd service. Learner code runs in a
throwaway Docker container per run: no network, read-only filesystem, non-root user, memory/CPU/process
limits, 10 s time limit. This is set up for testing with a few people, not for public traffic.

## Quick path: everything from the terminal

```bash
aws login                      # once; or aws configure with an access key
./deploy/ec2/launch.sh         # creates key pair, security group (your IP only) and a t2.micro, then deploys
./deploy/ec2/smoke-test.sh http://$(./deploy/ec2/launch.sh ip):8080
```

`launch.sh` also has `ip`, `myip` (re-allow your IP after your network changes), `ssh`, `stop`, `start` and
`destroy`. It uses `us-east-2` (the Neon region) unless you set `AWS_REGION`; set `INSTANCE_TYPE=t3.small` for a
bigger box. Steps 1-3 below are the manual equivalent.

## 1. Launch the instance (AWS console)

| Setting | Value |
|---|---|
| AMI | **Amazon Linux 2023** (x86_64 or arm64 both work) |
| Type | `t3.micro` (1 GB) works for 1-2 testers; `t3.small` (2 GB) is noticeably smoother |
| Storage | 20 GB gp3 (the sandbox image is about 450 MB) |
| Key pair | create or pick one, keep the `.pem` |
| Security group | **SSH 22** from *My IP*; **TCP 8080** from *My IP* (add testers' IPs later) |

Don't open 8080 to `0.0.0.0/0`: traffic is plain HTTP, so passwords and tokens are not encrypted. Add HTTPS
(for example Caddy or an ALB) before sharing more widely.

Neon accepts connections from anywhere by default. If you enabled an IP allow list there, add the instance's public IP.

## 2. Fill in the server config

```bash
cp deploy/ec2/codeway.env.example deploy/ec2/codeway.env   # git-ignored
openssl rand -hex 32                                       # paste as AUTH_SECRET
```

Fill in `DB_URL` (your Neon string), the `AI_*` values from your local `.env`, and `AUTH_SECRET`.
On a `t3.micro` keep `RUNNER_MAX_CONCURRENT=1`; on a `t3.small` use `2`.

## 3. Deploy

```bash
chmod 400 ~/.ssh/<key>.pem
./deploy/ec2/deploy.sh ec2-user@<public-ip> ~/.ssh/<key>.pem
```

This builds the jar, installs Java 21, Docker, 2 GB swap and the sandbox image on the first run, uploads your
`codeway.env`, and starts the `codeway` service. Re-run the same command to ship updates.

Startup takes about 30 s on a micro instance. The seed challenges are validated through the sandbox at boot,
so these log lines mean the sandbox works:

```
[seed] Shape Parade validated
[seed] Safe Vault validated
[seed] Payroll Hierarchy validated
```

## 4. Check it

```bash
./deploy/ec2/smoke-test.sh http://<public-ip>:8080
```

Expect `compiled=True` with some tests failing (it runs the unsolved starter code). The first run is slower
while the container image warms up; later runs take about 2-4 s on a micro instance.

## 5. Point the frontend at it

The frontend proxies `/api` to `BACKEND_URL` from the Next.js server, so no CORS changes are needed:

```bash
cd frontend
BACKEND_URL=http://<public-ip>:8080 npm run dev
```

## Day-to-day

| Task | Command (on the server) |
|---|---|
| Logs | `sudo journalctl -u codeway -f` |
| Restart | `sudo systemctl restart codeway` |
| Edit config | `sudo nano /opt/codeway/codeway.env`, then restart |
| Status | `systemctl status codeway` |

## What protects the server

- **Sandbox per run:** `--network none --read-only --cap-drop ALL --security-opt no-new-privileges --user 65534`,
  `--memory 512m`, `--pids-limit 128`, 1 CPU; only the run's own temp folder is mounted.
- **Concurrency cap:** at most `RUNNER_MAX_CONCURRENT` runs at once; others wait up to 45 s.
- **Rate limits per user:** 30 runs, 12 submits, 10 hints, 8 new challenges and 20 lessons per minute; 10 sign-in attempts per minute per IP.
- **Prod profile:** refuses to start with a weak `AUTH_SECRET`, and hides internal error details from responses.

Known limits for a test server: the `codeway` user is in the `docker` group (equivalent to root on this box),
rate limits are in memory (reset on restart), and the schema is managed by Hibernate `ddl-auto=update`.
