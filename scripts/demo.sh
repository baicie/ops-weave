#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f .env ]]; then echo 'Create .env with scripts/init_env.py or set OPSWEAVE_DEV_TOKEN manually.' >&2; exit 1; fi
# Parse only the one generated value; do not execute arbitrary dotenv contents as shell code.
export OPSWEAVE_DEV_TOKEN="$(sed -n 's/^OPSWEAVE_DEV_TOKEN=//p' .env | head -1 | tr -d '\r')"
export OPSWEAVE_MODE=demo
export OPSWEAVE_LISTEN=127.0.0.1:8090
export OPSWEAVE_PROVIDER=mock
export RUST_LOG=info
cargo run -p opsweave-agent-runtime
