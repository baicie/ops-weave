$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
python -X utf8 scripts/check_repo.py
if ($LASTEXITCODE -ne 0) { throw 'Static checks failed' }
python -X utf8 scripts/check_java_domain.py
if ($LASTEXITCODE -ne 0) { throw 'Java domain checks failed' }
python -X utf8 -m pytest tests/contracts
if ($LASTEXITCODE -ne 0) { throw 'Contract tests failed' }
cargo test --workspace --locked
if ($LASTEXITCODE -ne 0) { throw 'Rust tests failed' }
cargo test --workspace --all-features --locked
if ($LASTEXITCODE -ne 0) { throw 'Rust all-features tests failed' }
node --test tests/acceptance/*.test.mjs
if ($LASTEXITCODE -ne 0) { throw 'Acceptance probe tests failed' }
cargo check --workspace --all-features --all-targets --locked
if ($LASTEXITCODE -ne 0) { throw 'Feature build failed' }
