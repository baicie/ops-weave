import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { localSessionPlugin } from './dev/local-session.mjs'

const root = path.dirname(fileURLToPath(import.meta.url))
// Same byte framing as RuntimeOrigin and LoadedSkill; only the digest enters the browser bundle.
const skillHash = createHash('sha256')
for (const name of ['skill.json', 'prompt.md', 'output.schema.json']) {
  const bytes = readFileSync(path.resolve(root, '../../extensions/skills/incident-diagnosis-current', name))
  const length = Buffer.alloc(8)
  length.writeBigUInt64BE(BigInt(bytes.length))
  skillHash.update(length).update(bytes)
}
const currentSkillDigest = `sha256:${skillHash.digest('hex')}`

export default defineConfig(({ command }) => {
  const localToken = command === 'serve' ? process.env.OPSWEAVE_WEB_LOCAL_TOKEN : undefined
  return {
    plugins: [react(), tailwindcss(), ...(localToken ? [localSessionPlugin(localToken)] : [])],
    define: {
      __OPSWEAVE_CURRENT_SKILL_DIGEST__: JSON.stringify(currentSkillDigest),
      ...(localToken ? { 'import.meta.env.VITE_PLATFORM_AUTH': JSON.stringify('local-preview') } : {}),
    },
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
