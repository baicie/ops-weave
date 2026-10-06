import { randomBytes } from 'node:crypto'

const SESSION_MS = 30 * 60 * 1000
const HEADER = 'x-opsweave-local-session'
const BOOTSTRAP = '/__opsweave/local-session'

// Development-only bridge. The platform token never enters a browser response.
export function createLocalSessionBridge(token, { now = Date.now, limit = 64 } = {}) {
  if (typeof token !== 'string' || token.length < 32 || token.length > 4096 || /\s|\0/.test(token) || token.startsWith('REPLACE_')) {
    throw new Error('本地预览服务端凭据未正确配置')
  }
  const sessions = new Map()
  return (req, res, next) => {
    const path = (req.url ?? '').split('?')[0]
    if (path !== BOOTSTRAP && !path.startsWith('/api/')) return next()
    const send = (status, body) => {
      res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' })
      res.end(JSON.stringify(body))
    }
    const host = `127.0.0.1:${req.socket.localPort}`
    if (!['127.0.0.1', '::ffff:127.0.0.1', '::1'].includes(req.socket.remoteAddress)
      || req.headers.host !== host
      || req.headers.origin !== undefined && req.headers.origin !== `http://${host}`
      || req.headers['sec-fetch-site'] !== undefined && !['same-origin', 'none'].includes(req.headers['sec-fetch-site'])
      || req.headers.authorization !== undefined) return send(403, { error: 'FORBIDDEN' })
    const time = now()
    for (const [nonce, deadline] of sessions) if (deadline <= time) sessions.delete(nonce)
    if (path === BOOTSTRAP) {
      if (req.url !== BOOTSTRAP || req.method !== 'GET' || req.headers[HEADER] !== 'bootstrap') return send(403, { error: 'FORBIDDEN' })
      if (sessions.size >= limit) return send(429, { error: 'LOCAL_SESSION_LIMIT' })
      const sessionNonce = randomBytes(32).toString('base64url')
      const expiresAt = new Date(time + SESSION_MS).toISOString()
      sessions.set(sessionNonce, time + SESSION_MS)
      return send(200, { sessionNonce, expiresAt })
    }
    if (!path.startsWith('/api/v1/') && !/^\/api\/v2\/(?:data-sources|metric-bindings)(?:\/|$)/.test(path)) return send(403, { error: 'FORBIDDEN' })
    const nonce = req.headers[HEADER]
    if (typeof nonce !== 'string' || !sessions.has(nonce)) return send(401, { error: 'UNAUTHENTICATED' })
    delete req.headers[HEADER]
    req.headers.authorization = `Bearer ${token}`
    next()
  }
}

export function localSessionPlugin(token) {
  return {
    name: 'opsweave-local-session',
    apply: 'serve',
    configureServer(server) {
      if (server.config.server.host !== '127.0.0.1' || server.config.server.https) throw new Error('本地自动会话仅支持 127.0.0.1 开发预览')
      server.middlewares.use(createLocalSessionBridge(token))
    },
  }
}
