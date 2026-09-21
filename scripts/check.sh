#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/check_repo.py
python3 scripts/check_java_domain.py
python3 -m pytest tests/contracts
cargo fmt --all -- --check
cargo test --workspace --locked
cargo check --workspace --all-features --all-targets --locked
