<div align="center">

# KubeMind

**A web-based, AI-assisted Kubernetes dashboard — zero-install, team-friendly, self-hosted.**

[![CI](https://github.com/YusufAkyuz/KubeMind/actions/workflows/ci.yml/badge.svg)](https://github.com/YusufAkyuz/KubeMind/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](./LICENSE)

</div>

---

KubeMind is a Kubernetes dashboard you run in the browser instead of installing a desktop
app on every engineer's laptop. It manages multiple clusters from a single deployment, and
treats **AI troubleshooting as a first-class feature**: one click on any broken resource
gives you a plain-language diagnosis, grounded in that resource's live state and a built-in
Kubernetes knowledge base.

Two things make it different from the alternatives:

1. **Web-based / zero-install.** Deploy it once (in-cluster via Helm, or standalone via
   Docker Compose) and the whole team opens a URL. Nothing to install or update per-person.
2. **AI as a first-class feature.** "Explain this" on any resource, AI-drafted manifests,
   and edit-with-AI inside the YAML editor — all **local by default** (Ollama), so nothing
   about your cluster leaves your environment unless you choose a hosted model.

> ⚠️ **KubeMind grants real access to real clusters.** Read the [Security model](#security-model)
> section before deploying. Never expose it to the public internet.

## Table of contents

- [Features](#features)
- [Screenshots](#screenshots)
- [Architecture](#architecture)
- [Quickstart (local development)](#quickstart-local-development)
- [Deployment](#deployment)
- [Security model](#security-model)
- [AI features](#ai-features)
- [Contributing](#contributing)
- [License](#license)

## Features

- **Multi-cluster from one deployment.** Register clusters by kubeconfig (encrypted at rest);
  switch between them in the UI. Hub-and-spoke: install once, manage many.
- **Full read surface.** Nodes, workloads (Pods, Deployments, StatefulSets, DaemonSets,
  Jobs, CronJobs, HPAs), config (ConfigMaps, Secrets), networking (Services, Ingresses),
  storage (PVs, PVCs), events — all live via informers/watches, streamed to the UI over SSE.
- **Guarded write actions.** Scale, restart, delete, create, and edit-YAML — allowlisted by
  kind, ADMIN-gated, and fully audited (success or failure).
- **Access Control (RBAC) management.** Browse and create ServiceAccounts, Roles,
  RoleBindings, ClusterRoles, and ClusterRoleBindings, with Kubernetes-system objects
  filtered out of the way by default.
- **Three interactive terminals.** Pod exec, node shell (`kubectl debug node`-style), and a
  full cluster `kubectl` terminal — each with an explicit risk acknowledgment before it
  connects.
- **Helm integration.** Browse repositories, search charts, install/upgrade, and edit a
  release's values through the UI (backed by the real `helm` CLI).
- **Service port-forwarding** to reach a Service's backing pod straight from the browser.
- **AI troubleshooting.** Per-resource "Explain", cluster-wide insights, AI-drafted and
  AI-edited manifests — all with secret redaction before anything reaches the model.

## Screenshots

<!-- TODO(before public launch): add screenshots/GIFs of the Nodes dashboard, an "Explain
     this" AI diagnosis, and the Helm release view. AI-assisted dashboards sell on visuals. -->

_Coming soon._

## Architecture

📄 [View as a standalone page](https://yusufakyuz.github.io/KubeMind/architecture.html) — same diagram, styled and interactive-in-your-browser (light/dark aware).

```
┌─────────────┐     REST + SSE + WS      ┌──────────────┐   Fabric8 client   ┌────────────┐
│   Frontend  │ ───────────────────────▶ │   Backend    │ ─────────────────▶ │ Kubernetes │
│ React + TS  │ ◀─────────────────────── │ Spring Boot  │ ◀───────────────── │ API server │
│  (nginx)    │                          │   (Java 21)  │                    └────────────┘
└─────────────┘                          └──────┬───────┘
                                                │
                            ┌───────────────────┼────────────────────┐
                            ▼                    ▼                    ▼
                     ┌────────────┐      ┌──────────────┐     ┌──────────────┐
                     │ PostgreSQL │      │  Ollama /     │     │  helm CLI    │
                     │ (+pgvector)│      │  Spring AI    │     │ (subprocess) │
                     └────────────┘      └──────────────┘     └──────────────┘
```

- **Backend** — Java 21, Spring Boot 3.5, Fabric8 Kubernetes client (informers/watches),
  Spring Security (session + CSRF), Spring Data JPA + Flyway, Spring AI. Package-by-feature
  (`auth/`, `cluster/`, `k8s/`, `ai/`, `helm/`, `audit/`).
- **Frontend** — Vite + React + TypeScript + Tailwind, TanStack Query, React Router. Served
  by nginx in production, which also reverse-proxies `/api` and `/ws` to the backend.
- **Data** — PostgreSQL stores **app data only** (users, cluster registrations, AI diagnoses,
  audit log, prefs). The Kubernetes API is the single source of truth for live cluster state;
  it's never mirrored into Postgres.
- **AI** — Spring AI's `ChatClient` in front of Ollama (local, model `qwen2.5-coder:7b` by
  default), provider-agnostic so a hosted model can be swapped in later.

## Quickstart (local development)

**Prerequisites:** JDK 21, Maven, Node 20+, a local Kubernetes cluster
(`kind create cluster` or minikube), plus PostgreSQL and [Ollama](https://ollama.com) if you
want the database and AI features.

```bash
# 1. Pull the local AI model (optional — only needed for AI features)
ollama pull qwen2.5-coder:7b

# 2. Backend — needs Postgres on :5432 and (optionally) Ollama on :11434
cd backend
export KUBEMIND_ADMIN_PASSWORD=change-me       # seeds the initial admin on first run
export KUBEMIND_DB_PASSWORD=...                 # your local Postgres password
export KUBEMIND_ENCRYPTION_KEY=$(openssl rand -base64 32)   # for remote-cluster kubeconfigs
mvn spring-boot:run                             # starts on http://localhost:8080

# 3. Frontend
cd ../frontend
npm install
npm run dev                                     # http://localhost:5173, proxies /api + /ws
```

Sign in as `admin` with the password you set. The backend uses your current
`~/.kube/config` to reach the built-in "local" cluster.

Full conventions, test commands, and the AI eval harness are documented in
[CONTRIBUTING.md](./CONTRIBUTING.md).

## Deployment

KubeMind ships as **two container images** (backend + frontend) and supports **two
deployment modes from the same images**:

### 1. In-cluster (Helm) — hub-and-spoke

Install once onto a management cluster. That cluster becomes the built-in "local" cluster
(via an in-cluster ServiceAccount); every other cluster is registered through the UI with an
encrypted kubeconfig.

```bash
helm install kubemind deploy/helm/kubemind -n kubemind --create-namespace \
  --set auth.adminPassword=... \
  --set postgresql.password=...
```

The chart bundles optional PostgreSQL (pgvector) and Ollama (both toggleable). It binds the
backend ServiceAccount to **cluster-admin** — a deliberate, loudly documented choice; see
[`deploy/helm/kubemind/values.yaml`](./deploy/helm/kubemind/values.yaml) and the
[Security model](#security-model).

### 2. Standalone (Docker Compose) — nothing installed into any cluster

Run the stack on a VM or your own machine; register every cluster (including what would be
"local") as a kubeconfig. Permissions are exactly what each kubeconfig grants — give prod a
read-only ServiceAccount kubeconfig and KubeMind physically cannot write there.

```bash
KUBEMIND_ADMIN_PASSWORD=... KUBEMIND_DB_PASSWORD=... \
  docker compose -f deploy/docker-compose.yml up -d      # UI on :8080
```

Building the images locally:

```bash
docker build -f deploy/docker/backend.Dockerfile  -t kubemind/backend:0.1.0 .
docker build -f deploy/docker/frontend.Dockerfile -t kubemind/frontend:0.1.0 .
```

> Published images and a one-line Helm install from a chart repository are on the roadmap —
> see [the release plan](#release-plan) below.

## Security model

KubeMind is **self-hosted** — you deploy it into your own environment, and cluster
credentials never leave it. A few things you must understand before deploying:

- **Never expose it to the internet.** Assume it sits behind a VPN, or ingress with TLS +
  auth + an IP allowlist.
- **The in-cluster Helm install binds to `cluster-admin`.** This is required by the cluster
  terminal, node shell, and RBAC-creation features. Anyone who can log in as an **ADMIN**
  effectively holds cluster-admin on the install cluster. If that's more power than you want,
  use the standalone Docker Compose mode with least-privilege kubeconfigs instead.
- **Roles.** `ADMIN` can do everything; `USER` is read-only for KubeMind's own write actions.
  Registered clusters are per-user and private to whoever added them; a USER's cluster
  registration stays **pending until an ADMIN approves it**.
- **Two disclosed privilege exceptions.** The **node shell** schedules a privileged,
  host-mounted debug pod; the **cluster terminal** provisions its own cluster-admin
  ServiceAccount for the session. Both require an explicit "I understand the risk" click, and
  the cluster terminal audits only session open/close (not the commands run inside) — the one
  deliberate gap in an otherwise per-action-audited app.
- **Web hardening.** CSRF protection, `httpOnly`/`sameSite` session cookies, an SSRF
  allowlist on cluster API endpoints, and strict input validation are all in place.
- **Secrets.** `Secret` values are hidden by default; revealing one is ADMIN-gated and
  audited. Stored kubeconfigs are encrypted at rest (AES-256-GCM).

Found a vulnerability? Please follow [SECURITY.md](./SECURITY.md) — don't open a public issue.

## AI features

- **Redaction is mandatory and tested.** Before anything is sent to the model — even the
  local one — Secret data, credential-like env vars, tokens, and kubeconfig contents are
  stripped. `Secret` resources never have their `data`/`stringData` serialized into a prompt
  at all, only key names.
- **AI never touches the Kubernetes API.** It only ever produces *text* that a human reviews
  and applies. Every AI surface renders an "AI-generated — verify before acting" disclaimer.
- **Diagnoses are cached** by a resource state hash, so the model is only re-queried when the
  resource actually changed.
- **Local by default, provider-agnostic.** Ships pointed at Ollama; a hosted provider can be
  swapped in behind Spring AI's `ChatClient` without touching callers.

## Release plan

The following are planned and tracked for the public launch:

- Publish the `kubemind/backend` and `kubemind/frontend` images to Docker Hub on tagged
  releases (automated via GitHub Actions).
- Publish the Helm chart to a chart repository so users can `helm repo add` + `helm install`
  without cloning this repo.
- Branch protection on `main` (required CI, required review) — see [CONTRIBUTING.md](./CONTRIBUTING.md).

## Contributing

Contributions are welcome. Please read [CONTRIBUTING.md](./CONTRIBUTING.md) for how to run
KubeMind locally, the project's conventions, testing expectations, and the PR process.

By contributing, you agree that your contributions will be licensed under the Apache-2.0
license.

## License

Licensed under the [Apache License 2.0](./LICENSE).
