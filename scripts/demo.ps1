$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if (-not (Test-Path '.env')) { throw 'Create .env using scripts/init_env.py first.' }
$line = Get-Content '.env' | Where-Object { $_ -like 'OPSWEAVE_DEV_TOKEN=*' } | Select-Object -First 1
if (-not $line) { throw 'OPSWEAVE_DEV_TOKEN is missing' }
$env:OPSWEAVE_DEV_TOKEN = $line.Substring('OPSWEAVE_DEV_TOKEN='.Length)
$env:OPSWEAVE_MODE = 'demo'
$env:OPSWEAVE_LISTEN = '127.0.0.1:8090'
$env:OPSWEAVE_PROVIDER = 'mock'
$env:RUST_LOG = 'info'
cargo run -p opsweave-agent-runtime
if ($LASTEXITCODE -ne 0) { throw 'Rust process failed' }
