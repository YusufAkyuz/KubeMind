/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// The dev server proxies /api to the Spring Boot backend so cookies are same-origin.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // changeOrigin: false everywhere, on purpose — matching what
    // deploy/docker/nginx.conf.template does in the built app (every location
    // block there sets proxy_set_header Host $host, preserving the original
    // Host rather than rewriting it to the backend's). Spring computes
    // absolute URLs it hands back to the browser (OIDC's redirect_uri, and
    // {baseUrl} in the post-logout redirect) from that Host header. Vite's
    // default rewrites it to match the proxy target instead, so those URLs
    // pointed at the bare backend origin (localhost:8080) — which serves no
    // SPA — rather than back through this dev server. First caught on the
    // OIDC authorization redirect; recurred on logout via plain /api once
    // /api was left on Vite's default, which is why it's no longer an
    // exception here.
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
      // WebSocket upgrade for the interactive pod terminal.
      '/ws': { target: 'ws://localhost:8080', ws: true, changeOrigin: false },
      // OIDC login: the browser is redirected here directly, not fetched via
      // axios, so it needs its own entries alongside /api rather than falling
      // under it.
      '/oauth2': { target: 'http://localhost:8080', changeOrigin: false },
      '/login/oauth2': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    globals: true,
    css: true,
  },
})
