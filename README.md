# KubeMind — Phase 0 Walking Skeleton

Web-based, AI-assisted Kubernetes dashboard. This is the **Phase 0 walking skeleton**:
log in → connect to a local cluster → list namespaces, proving every layer talks
end-to-end (React → Spring Boot → Fabric8 → Kubernetes, plus PostgreSQL + auth).

## Stack

- **Backend:** Java 21, Spring Boot 3.3, Fabric8 Kubernetes client, Spring Security, JPA + Flyway
- **Frontend:** Vite + React + TypeScript + Tailwind v4 + TanStack Query + React Router
- **Infra:** PostgreSQL + Ollama (Docker Compose)

## Prerequisites

- JDK 21, Maven, Node 20+
- Docker
- A local cluster: `kind create cluster` (or minikube)

## 1. Start infrastructure

```bash
docker compose -f deploy/docker-compose.yml up -d
```

(Ollama is included for the Phase 3 AI feature; it isn't used yet.)

## 2. Run the backend

```bash
cd backend
mvn spring-boot:run
```

Starts on http://localhost:8080. It reads your current kubeconfig (`~/.kube/config`)
to reach the cluster. A default user is seeded on first run: **admin / admin**.

## 3. Run the frontend

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173, sign in with **admin / admin**, and you should see your
cluster's namespaces. The Vite dev server proxies `/api` to the backend.

## Security note

This skeleton seeds a default admin and disables CSRF for local simplicity. Before any
non-local deployment, follow the rules in [`CLAUDE.md`](./CLAUDE.md) (OIDC/SSO,
Kubernetes impersonation, CSRF, no internet exposure) and see
[`KubeMind-MVP-Plan.md`](./KubeMind-MVP-Plan.md) §3.7.

---

**TR:** Bu Phase 0 iskeleti; giriş → cluster'a bağlan → namespace'leri listele akışını
uçtan uca kanıtlar. Çalıştırma adımları yukarıda. Üretim öncesi güvenlik kuralları için
`CLAUDE.md`'ye bak.
