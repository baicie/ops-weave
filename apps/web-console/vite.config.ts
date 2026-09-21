import zeus from '@zeus-js/vite-plugin'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [zeus()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/agent': {
        target: 'http://127.0.0.1:8090',
        rewrite: path => path.replace(/^\/agent/, ''),
      },
    },
  },
})
