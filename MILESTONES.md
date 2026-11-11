# Codeway: AI Adaptive Coding Tutor (Java-first)

Scope for this build: **Java only**, end to end (lessons, challenges, runner, mastery, hints, UI).
Python/JavaScript appear in the UI as "Coming soon" and plug into the same runner interface later.

## Decisions

| Topic | Decision |
|---|---|
| Frontend | Next.js (App Router) + Tailwind, Monaco editor, `next-themes` for light/dark |
| Design | Apple-style: SF system font stack, translucent "glass" materials, large radii, system blue, segmented controls, soft depth, spring-like motion. Light + dark, follows OS, manual toggle |
| Backend | Spring Boot 3.5 (Java 21 target), Spring Data JPA, PostgreSQL |
| Auth | Prototype auth: email + password (BCrypt), signed bearer token |
| AI | OpenAI-compatible chat API. Supports **Azure OpenAI** style (endpoint + deployment + `api-version`) and plain OpenAI. All config from `.env` |
| Runner | `CodeRunner` interface. `LocalJavaRunner` (javac + java in a temp dir, timeout, heap cap) for dev. `Judge0Runner` later for production isolation |
| Mastery | Computed in backend code (Elo-style), never by the LLM |
| Tests | Hidden tests use a tiny `Check` harness injected by the backend, results printed as parseable lines |

> The local runner executes learner code on the host JVM with a timeout. It is fine for a local prototype only. Do not expose it publicly. Production uses Judge0/Piston in Docker.

## Milestones

### M0: Foundation
- [x] Repo layout: `backend/`, `frontend/`, root `.env`, `.env.example`, `.gitignore`
- [x] This document

### M1: Backend skeleton
- [x] Spring Boot project, Postgres connection from `.env`
- [x] Entities: users, concept_mastery, lessons, challenges, attempts, events
- [x] Auth: GitHub sign-in through Supabase Auth; backend verifies Supabase JWTs (ES256, public JWKS) and links users by email
- **Done when:** backend compiles and boots against Postgres.

### M2: Java execution engine
- [x] `CodeRunner` interface, `LocalJavaRunner`
- [x] `Check` test harness, parse per-test results
- [x] Compile errors parsed into `{file, line, message}` for editor markers
- [x] `POST /api/run` (modes: `main`, `tests`)
- **Done when:** a reference solution passes hidden tests and a broken one reports failures and compile markers.

### M3: Seed challenges and attempt flow
- [x] Hardcoded validated Java challenges (Polymorphism, Encapsulation, Inheritance)
- [x] Seeds validated through the runner at startup
- [x] `POST /api/challenges/next`, `POST /api/attempts/{id}/submit`
- **Done when:** full request cycle works with `curl`.

### M4: Telemetry and mastery
- [x] `POST /api/telemetry` event batches
- [x] Performance score from signals (first-run pass, runs, errors, time, hints, churn, confidence)
- [x] Elo-style mastery update, streak, mastered = 3 clean passes at target difficulty
- [x] `GET /api/mastery`
- **Done when:** unit tests cover the scoring rules.

### M5: Frontend shell and design system
- [x] Next.js + Tailwind, theme tokens for light/dark, Apple-style components
- [x] Auth screen, dashboard with mastery rings, theme toggle

### M6: Workspace
- [x] Monaco multi-file editor: file tree, tabs, markers
- [x] Run / Run tests / Submit, console, per-test results panel
- [x] Telemetry capture (first keystroke, edit bursts, delete churn, confidence slider)

### M7: AI generation
- [x] `AiClient` (Azure/OpenAI), lessons cached in DB
- [x] Challenge generation JSON, **reference-solution validation**, bounded retries, bank reuse
- [x] Graceful fallback to seeds when AI is not configured

### M8: Hint ladder and adaptive loop
- [x] 4 escalating hint levels (nudge, concept, partial code, solution with mastery cost)
- [x] Next challenge picked by score, scaffolding by score band
- [x] Concept complete screen

### M9: Polish (follow-up)
- [ ] Onboarding diagnostic, progress dashboard, per-user rate limits
- [ ] Judge0 runner, Python and JavaScript

## Environment variables (`.env`)

| Variable | Purpose |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL |
| `AI_ENDPOINT` | e.g. `https://<resource>.openai.azure.com` or `https://api.openai.com/v1` |
| `AI_API_KEY` | key, server-side only |
| `AI_API_VERSION` | set for Azure (e.g. `2024-10-21`); leave empty for plain OpenAI |
| `AI_MODEL` | Azure deployment name or OpenAI model for lessons/challenges |
| `AI_HINT_MODEL` | cheaper model for hints (defaults to `AI_MODEL`) |
| `SUPABASE_URL` | Supabase project whose access tokens the backend accepts (public value) |
| `RUNNER_MODE` | `local` (default) |
