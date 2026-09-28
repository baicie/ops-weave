import path from 'node:path'
import { fileURLToPath } from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const root = path.dirname(fileURLToPath(import.meta.url))

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(root, 'src'),
    },
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/agent': {
        target: 'http://127.0.0.1:8090',
        rewrite: path => path.replace(/^\/agent/, ''),
      },
      '/api': {
        target: 'http://127.0.0.1:8080',
      },
    },
  },
})
