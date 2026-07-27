# KubeMind

A web-based, AI-assisted Kubernetes dashboard. This chart deploys the backend
(Spring Boot), frontend (nginx), and — optionally — a pgvector-enabled
PostgreSQL and an Ollama instance, so a bare `helm install` yields a fully
working system.

## ⚠️ Before you install

- **The backend ServiceAccount is bound to `cluster-admin`** (`rbac.clusterAdmin`).
  The Cluster Terminal, Node Shell, and Access Control features all require
  this level of access — see the security note at the top of `values.yaml`
  before deploying.
- **Never expose KubeMind directly to the internet.** Put it behind an
  ingress with TLS and network-level access control (VPN/allowlist), or keep
  ingress disabled and use `kubectl port-forward`.

## Install

```console
helm repo add kubemind https://yusufakyuz.github.io/KubeMind
helm repo update

helm install kubemind kubemind/kubemind \
  --namespace kubemind --create-namespace \
  --set auth.adminPassword=<password> \
  --set auth.encryptionKey=$(openssl rand -base64 32) \
  --set postgresql.password=<password>
```

The first login uses `admin` / the password you set above. `auth.encryptionKey`
is only required if you plan to register remote clusters (the built-in local
cluster works without it).

## Configuration

See `values.yaml` for the full set of options and inline documentation. Key
sections:

| Key | Description |
|---|---|
| `auth.adminPassword` | Required. Seeds the first admin login on startup. |
| `auth.encryptionKey` | Encrypts stored kubeconfigs for registered remote clusters. |
| `postgresql.enabled` | `true` deploys a bundled pgvector-enabled Postgres; `false` requires `postgresql.external`. |
| `ollama.enabled` | `true` deploys a bundled Ollama and pulls the chat/embedding models on first start; `false` requires `ollama.external.baseUrl`. |
| `rbac.clusterAdmin` | Must stay `true` for the current feature set — see the security note in `values.yaml`. |
| `ingress.enabled` | Off by default; enable and configure a TLS-terminating ingress before exposing KubeMind beyond `port-forward`. |
| `replicaCount` | Login sessions are in-memory — more than 1 replica needs sticky sessions on your ingress. |

## Uninstall

```console
helm uninstall kubemind -n kubemind
```

Persistent volumes (Postgres/Ollama storage) are not deleted automatically —
remove them manually if you don't need the data.

## Links

- [Source](https://github.com/YusufAkyuz/KubeMind)
- [Issues](https://github.com/YusufAkyuz/KubeMind/issues)
