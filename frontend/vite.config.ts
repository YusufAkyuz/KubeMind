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
    },
  },
})
