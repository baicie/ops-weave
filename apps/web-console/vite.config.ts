import path from 'node:path'
import { fileURLToPath } from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { localSessionPlugin } from './dev/local-session.mjs'

const root = path.dirname(fileURLToPath(import.meta.url))

export default defineConfig(({ command }) => {
  const localToken = command === 'serve' ? process.env.OPSWEAVE_WEB_LOCAL_TOKEN : undefined
  return {
    plugins: [react(), tailwindcss(), ...(localToken ? [localSessionPlugin(localToken)] : [])],
    define: localToken ? { 'import.meta.env.VITE_PLATFORM_AUTH': JSON.stringify('local-preview') } : {},
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
  }
})
