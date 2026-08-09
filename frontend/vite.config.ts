/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// The dev server proxies /api to the Spring Boot backend so cookies are same-origin.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      // WebSocket upgrade for the interactive pod terminal.
      '/ws': { target: 'ws://localhost:8080', ws: true },
      // OIDC login (when configured): the browser is redirected here
      // directly, not fetched via axios, so it needs its own proxy entries
      // alongside /api rather than falling under it — mirrored in
      // deploy/docker/nginx.conf.template for the built app.
      //
      // changeOrigin: false (unlike /api and /ws, which don't need it) —
      // Spring computes the OIDC redirect_uri it hands to the IdP from the
      // request's Host header. Vite's default proxy behavior rewrites that
      // header to match the target (localhost:8080), so Spring would build a
      // redirect_uri pointing at the bare backend origin — which serves no
      // SPA at "/" — instead of back through this dev server. Preserving the
      // original Host keeps the whole login round-trip on localhost:5173.
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
