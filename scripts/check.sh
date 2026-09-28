#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 -X utf8 scripts/check_repo.py
python3 -X utf8 scripts/check_java_domain.py
python3 -X utf8 -m pytest tests/contracts
cargo fmt --all -- --check
cargo test --workspace --locked
cargo test --workspace --all-features --locked
node --test tests/acceptance/*.test.mjs
cargo check --workspace --all-features --all-targets --locked
