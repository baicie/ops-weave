"""Generate local-only credentials without printing them. Never overwrite an existing file."""
from pathlib import Path
import secrets
root = Path(__file__).resolve().parents[1]
path = root / '.env'
content = 'POSTGRES_DB=opsweave\nPOSTGRES_USER=opsweave_dev\nPOSTGRES_PASSWORD=' + secrets.token_urlsafe(32) + '\nOPSWEAVE_DEV_TOKEN=' + secrets.token_urlsafe(48) + '\n'
try:
    with path.open('x', encoding='utf-8') as f: f.write(content)
    path.chmod(0o600)
except FileExistsError:
    raise SystemExit('.env already exists; nothing was changed')
print('Created .env with local credentials. It is ignored by Git. Do not share it.')
