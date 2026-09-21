# Web Console

React + TypeScript + Vite. Development server proxies `/agent` to the local Rust runtime.
The diagnostic form uses a developer token kept only in React state. No model key belongs
in a `VITE_*` variable. The UI renders model text as text, never raw HTML.

`pnpm install --lockfile-only` at the repo root generates `pnpm-lock.yaml`; review it and commit it.
Then use `pnpm install --frozen-lockfile`, `pnpm typecheck:web`, `pnpm build:web`.

The Nginx static image does NOT automatically proxy the local demo runtime. Configure a
trusted production reverse proxy/BFF only after OIDC and authorization are implemented.
The connected diagnostic demo is supported through `pnpm dev:web` on the same host.
