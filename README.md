# Codeway

Adaptive Java OOP tutor. See [MILESTONES.md](MILESTONES.md) for the plan and status.

## Run locally
1. Fill in `.env` (copy from `.env.example`). `DB_URL` may be a JDBC URL or a `postgresql://user:pass@host/db` string.
2. Backend: `cd backend && mvn spring-boot:run` (API on :8080). No Postgres yet? `SPRING_PROFILES_ACTIVE=h2 mvn spring-boot:run`.
3. Frontend: `cd frontend && npm install && npm run dev` (UI on :3000, proxies `/api` to the backend).
4. Tests: `cd backend && mvn test`.

The local Java runner is for development only; it is not a security sandbox. Swap in Judge0/Piston before exposing this publicly.
