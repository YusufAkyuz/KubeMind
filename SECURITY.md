# Security Policy

KubeMind grants real access to real Kubernetes clusters. We take its security seriously and
appreciate responsible disclosure.

## Reporting a vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**

Instead, report privately via one of:

- GitHub's [private vulnerability reporting](https://github.com/YusufAkyuz/KubeMind/security/advisories/new)
  (**Security** tab → **Report a vulnerability**), or
- Email the maintainer directly (see the profile on the repository).

Please include:

- A description of the issue and its impact.
- Steps to reproduce (a proof-of-concept if you have one).
- The affected version / commit.

We'll acknowledge your report, work with you on a fix, and credit you in the release notes
unless you'd prefer to stay anonymous.

## Scope

In scope: the backend, frontend, Helm chart, and Docker images in this repository.

Out of scope: issues that require an already-compromised admin account, misconfigurations
that contradict the documented deployment guidance (e.g. exposing KubeMind to the public
internet), and vulnerabilities in third-party dependencies that should be reported upstream.

## Threat model & trust boundaries

KubeMind is **self-hosted**. It authenticates human users, and acts against Kubernetes API
servers using credentials that stay in your environment — the project's authors never hold
them. Understanding what is and isn't protected matters before you deploy.

### What KubeMind protects

- **Stored kubeconfigs are encrypted at rest** (AES-256-GCM, key from
  `KUBEMIND_ENCRYPTION_KEY`) and are never logged or sent to the AI.
- **Web hardening:** CSRF protection, `httpOnly` + `sameSite` session cookies, an SSRF
  allowlist on cluster API-server endpoints, and strict input validation.
- **Write actions are allowlisted, ADMIN-gated, and audited** — every scale/restart/delete/
  create/edit, success or failure, is written to the audit log.
- **Secret values are hidden by default;** revealing one is ADMIN-gated and audited.
- **AI redaction:** Secret data, credential-like env vars, tokens, and kubeconfig contents
  are stripped before anything reaches the model — even the local one. `Secret` resources
  never have their `data`/`stringData` serialized into a prompt at all.

### Roles and cluster ownership

- `ADMIN` can perform every write action and open all three terminals.
- `USER` is read-only for KubeMind's own write actions.
- Registered clusters are **per-user and private to whoever added them.** A USER's cluster
  registration stays **pending until an ADMIN approves it**; once approved it is private to
  its owner. The built-in "local" cluster (the one KubeMind itself runs against) is the sole
  shared cluster.

### Deliberate, disclosed trade-offs

These are **intentional** design decisions, documented so they aren't mistaken for bugs:

1. **The in-cluster Helm install binds the backend ServiceAccount to `cluster-admin`.**
   It's required by the cluster terminal, node shell, and RBAC-creation features. Anyone who
   can authenticate as an **ADMIN** effectively holds cluster-admin on the install cluster.
   If that's more trust than you want, use the **standalone Docker Compose** mode with
   least-privilege kubeconfigs — nothing is installed into any cluster and permissions are
   exactly what each kubeconfig grants.

2. **Node shell** schedules a privileged, host-mounted debug pod on the target node (full
   host filesystem/process access, `chroot`'d). It requires an explicit risk acknowledgment
   and is audited (`NODE_EXEC`); the debug pod is deleted when the session ends.

3. **Cluster terminal** provisions its own ServiceAccount bound to `cluster-admin` for the
   session (torn down on close). It requires an explicit risk acknowledgment and audits only
   **session open/close** — **not the individual commands run inside.** This is the one
   deliberate gap in an otherwise per-action-audited app.

4. **Port-forward** proxies arbitrary HTTP to a Service's backing pod. It's ADMIN-gated and
   audits session open/close (not the proxied traffic, which is opaque), and is CSRF-exempt
   on that path because the proxied traffic is arbitrary third-party HTTP.

### Operator responsibilities

KubeMind assumes the operator provides the outer security perimeter:

- **Never expose KubeMind to the public internet.** Put it behind a VPN, or ingress with TLS
  + authentication + an IP allowlist.
- Set a strong `KUBEMIND_ADMIN_PASSWORD` and a randomly generated `KUBEMIND_ENCRYPTION_KEY`.
- Prefer least-privilege (read-only) ServiceAccount kubeconfigs for clusters you don't need
  to write to.

## Roadmap items that harden this further

- OIDC/SSO for user authentication.
- Kubernetes user impersonation (`Impersonate-User`/`Impersonate-Group`) so cluster RBAC —
  not KubeMind's app-level role — becomes the single source of authorization, and the K8s
  audit log shows the real human.
