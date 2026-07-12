# KubeMind frontend image: static SPA served by nginx, which also reverse-proxies
# /api and /ws to the backend service — mirroring the Vite dev proxy, so cookies
# stay same-origin and the ingress only ever points at this one service.
# Build from the REPO ROOT:
#   docker build -f deploy/docker/frontend.Dockerfile -t kubemind/frontend:0.1.0 .

# ── Build stage ────────────────────────────────────────────────────────────────
FROM node:22-alpine AS build
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ .
RUN npm run build

# ── Runtime stage ──────────────────────────────────────────────────────────────
FROM nginx:1.27-alpine

# The official image envsubsts /etc/nginx/templates/*.template on start — the
# chart injects BACKEND_HOST/BACKEND_PORT so the same image works everywhere.
COPY deploy/docker/nginx.conf.template /etc/nginx/templates/default.conf.template
ENV BACKEND_HOST=kubemind-backend \
    BACKEND_PORT=8080

COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 8080
