$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
python scripts/check_repo.py
if ($LASTEXITCODE -ne 0) { throw 'Static checks failed' }
python scripts/check_java_domain.py
if ($LASTEXITCODE -ne 0) { throw 'Java domain checks failed' }
python -m pytest tests/contracts
if ($LASTEXITCODE -ne 0) { throw 'Contract tests failed' }
cargo test --workspace --locked
if ($LASTEXITCODE -ne 0) { throw 'Rust tests failed' }
cargo check --workspace --all-features --all-targets --locked
if ($LASTEXITCODE -ne 0) { throw 'Feature build failed' }
