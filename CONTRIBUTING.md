# Contributing to KubeMind

Thanks for your interest in contributing! This guide covers everything you need to get a
local environment running, the conventions we follow, and how changes get merged.

By contributing you agree that your contributions are licensed under the project's
[Apache-2.0 license](./LICENSE).

## Table of contents

- [Ground rules](#ground-rules)
- [Local setup](#local-setup)
- [Running the app](#running-the-app)
- [Running the tests](#running-the-tests)
- [Project conventions](#project-conventions)
- [Kubernetes safety rules](#kubernetes-safety-rules)
- [AI feature rules](#ai-feature-rules)
- [Pull request process](#pull-request-process)
- [Reporting bugs & security issues](#reporting-bugs--security-issues)

## Ground rules

- **Be excellent to each other.** Assume good faith; keep discussion technical and kind.
- **Production-ready, but MVP-simple.** Code should handle real failure paths with real error
  handling — but no gold-plating and no speculative abstractions. When in doubt, choose the
  simpler design.
- **Small, reviewable diffs.** A change is "done" when it handles realistic failure paths, no
  secrets appear in logs/prompts/commits, tests exist and pass, DB changes ship as Flyway
  migrations, and the diff is small enough to review.
- **Language.** English for code, comments, commit messages, and PRs. (Issues in Turkish are
  welcome — the maintainer is bilingual.)

## Local setup

**Prerequisites:**

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 21 | Backend is Java 21 (Spring Boot 3.5) |
| Maven | 3.9+ | Backend build |
| Node | 20+ | Frontend build (Vite 5) |
| PostgreSQL | 15/16 | App data; pgvector extension for AI |
| Ollama | latest | Local AI model — optional, only for AI features |
| A cluster | — | `kind create cluster` or minikube. **Never a real/prod cluster.** |

```bash
git clone https://github.com/YusufAkyuz/KubeMind.git
cd KubeMind

# Local AI model (optional)
ollama pull qwen2.5-coder:7b
```

The repo layout:

```
/backend    Spring Boot app (REST + SSE + WS, K8s service, AI service, security)
/frontend   Vite + React + TS app
/deploy     Dockerfiles (docker/) + Helm chart (helm/kubemind/) + docker-compose.yml
```

## Running the app

```bash
# Backend — needs Postgres on :5432, and Ollama on :11434 for AI
cd backend
export KUBEMIND_ADMIN_PASSWORD=change-me
export KUBEMIND_DB_PASSWORD=...
export KUBEMIND_ENCRYPTION_KEY=$(openssl rand -base64 32)
mvn spring-boot:run          # http://localhost:8080

# Frontend
cd ../frontend
npm install
npm run dev                  # http://localhost:5173 (proxies /api + /ws to the backend)
```

Sign in as `admin` with the password you set. The backend reads your `~/.kube/config` to
reach the built-in "local" cluster.

## Running the tests

**Every PR must pass CI** — which runs exactly these:

```bash
# Backend unit tests (no DB or cluster needed — uses the Fabric8 mock server)
cd backend && mvn test

# Frontend: type-check + build + unit tests
cd frontend && npm run build && npm test
```

There is also an **AI eval harness** that scores the "Explain" prompt/model against ~33
broken-resource fixtures. It needs a running Ollama and is **not** part of normal `mvn test`.
Run it before and after any prompt or model change and compare scores:

```bash
cd backend
mvn test -Dtest=EvalHarness -Deval=true                    # full run (~10 min)
mvn test -Dtest=EvalHarness -Deval=true -Deval.filter=oom  # subset by scenario id
```

Fixtures live in `backend/src/test/resources/eval/scenarios.yaml`.

## Project conventions

### API

- REST for reads and actions, **SSE** for live streams, **WebSocket** for the three
  interactive terminals.
- Kubernetes-shaped URLs, e.g. `/api/clusters/{id}/namespaces/{ns}/pods`. Every
  cluster-scoped route carries a `clusterId` — the app is multi-cluster by design.
- **Never swallow exceptions.** Map Kubernetes failures to clear HTTP responses: cluster
  unreachable → 502/503, RBAC forbidden → 403, not found → 404. The frontend must render
  these states, not spin forever.

### Backend (Java)

- Constructor injection. DTOs at the boundary — never leak Fabric8 model objects to the API.
- `@Transactional` only where needed.
- **Package-by-feature** (`auth/`, `cluster/`, `k8s/`, `ai/`, `helm/`, `audit/`), not
  package-by-layer.
- Every write action is `@PreAuthorize("hasRole('ADMIN')")` and audited via `AuditService`.

### Frontend (React/TS)

- Strict TypeScript. **TanStack Query** for all server state.
- Small components; Tailwind utilities only (no ad-hoc CSS files).
- **No emoji in the UI** — SVG icons only (see `components/Icons.tsx`).

### Database

- **Flyway migrations for every schema change** (`backend/src/main/resources/db/migration/`,
  currently up to `V10`). Never rely on `ddl-auto: update` — the app runs `validate`.
- PostgreSQL stores **app data only**. Never mirror live Kubernetes state into it; read it
  live via informers / generic client lookups.

## Kubernetes safety rules

These are non-negotiable:

- **Test only against kind/minikube — never a real/prod cluster.** Do not run destructive
  operations against any cluster you didn't create for testing.
- **Write actions are allowlisted, confirmed, and audited.** Scale/restart/delete/create/
  edit-YAML all go through an allowlist of kinds (`ResourceCreationService.ALLOWED_KINDS`,
  `ResourceEditService.EDITABLE_KINDS`). Adding a new writable kind is a security-relevant
  change — call it out explicitly in the PR.
- **Least privilege, with disclosed exceptions.** The node shell (privileged host-mounted
  debug pod) and the cluster terminal (session-scoped cluster-admin ServiceAccount) are
  deliberate, documented exceptions. Don't extend either's reach without saying so.

## AI feature rules

- **Redact before prompting.** The context collector must strip Secret data, credential-like
  env vars, tokens, and kubeconfig contents before anything reaches the model — even the
  local one. Redaction lives in `ai/Redactor.java` with unit tests; any change to it gets
  extra review. `Secret` resources never have `data`/`stringData` serialized into a prompt.
- **AI explains, drafts, and edits text — it never calls the Kubernetes API.** It only
  produces text a human reviews and applies. Render an "AI-generated — verify before acting"
  disclaimer wherever output is shown.
- Keep the model behind Spring AI's `ChatClient` so a hosted provider can be swapped in
  without touching callers.

## Pull request process

1. **Branch** off `main` (`feat/…`, `fix/…`, `docs/…`). Don't commit directly to `main`.
2. **Keep it focused.** One logical change per PR. Refactors separate from behavior changes.
3. **Write/adjust tests** for the change. Backend and frontend suites must stay green.
4. **Commit messages** in imperative mood (`feat: add cluster approval workflow`,
   `fix: scope cluster name uniqueness per owner`). Conventional-commit prefixes preferred.
5. **Open the PR** against `main`. CI (backend tests + frontend build/tests) must pass —
   `main` is protected and requires it.
6. **Describe the security impact** if the change touches auth, write allowlists, the AI
   redaction path, or the terminals' privilege model.

A maintainer will review; expect a couple of rounds of feedback on non-trivial changes.

## Reporting bugs & security issues

- **Bugs / features:** open a GitHub issue with clear reproduction steps and, for bugs, what
  you expected vs. what happened.
- **Security vulnerabilities:** **do not** open a public issue. Follow the process in
  [SECURITY.md](./SECURITY.md).
