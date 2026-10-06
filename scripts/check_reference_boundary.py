"""Check commit candidates against a private policy stored outside the checkout."""
from pathlib import Path
import argparse
import json
import os
import subprocess
import sys
import unicodedata

ROOT = Path(__file__).resolve().parents[1]


def normalize(value: str) -> str:
    return unicodedata.normalize('NFKC', value).casefold()


def load_terms(path: Path, root: Path) -> list[str]:
    resolved = path.resolve(strict=True)
    if resolved.is_relative_to(root.resolve()):
        raise ValueError('Private policy must remain outside the checkout')
    if resolved.stat().st_size > 65536:
        raise ValueError('Private policy exceeds the size limit')
    value = json.loads(resolved.read_text(encoding='utf-8-sig'))
    terms = value.get('terms') if isinstance(value, dict) else None
    if not isinstance(value, dict) or value.get('version') != 1 or not isinstance(terms, list) or not 1 <= len(terms) <= 128:
        raise ValueError('Private policy requires version 1 and 1..128 terms')
    if any(not isinstance(term, str) or not term.strip() or len(term) > 120 for term in terms):
        raise ValueError('Private policy contains an invalid term')
    return sorted({normalize(term.strip()) for term in terms})


def check(root: Path, terms: list[str]) -> tuple[int, list[str]]:
    result = subprocess.run(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=root, check=True, capture_output=True)
    paths = sorted(set(filter(None, result.stdout.decode('utf-8').split('\0'))))
    failures, checked = [], 0
    for name in paths:
        path = root / name
        if not path.exists():
            continue
        if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
            failures.append('A candidate path crosses the checkout boundary')
            continue
        if not path.is_file():
            continue
        checked += 1
        if any(term in normalize(name) for term in terms):
            failures.append(f'Candidate #{checked}: restricted reference in path')
            continue
        if path.stat().st_size > 32 * 1024 * 1024:
            failures.append(f'Candidate #{checked}: exceeds the scan size limit')
            continue
        content = normalize(path.read_bytes().decode('utf-8', errors='replace'))
        if any(term in content for term in terms):
            # The path has already passed the private-term check; never print content.
            failures.append(f'{name}: restricted reference in content')
    return checked, failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--policy', type=Path, default=os.environ.get('OPSWEAVE_REFERENCE_POLICY'))
    args = parser.parse_args()
    if args.policy is None:
        parser.error('Provide --policy or OPSWEAVE_REFERENCE_POLICY with a private external policy path')
    try:
        terms = load_terms(Path(args.policy), ROOT)
        checked, failures = check(ROOT, terms)
    except (OSError, ValueError, subprocess.SubprocessError):
        print('Reference boundary check could not complete; check the private policy and Git worktree', file=sys.stderr)
        return 2
    if failures:
        print('\n'.join(failures), file=sys.stderr)
        return 1
    print(f'Reference boundary check passed: {checked} commit candidate files')
    return 0


if __name__ == '__main__':
    sys.exit(main())
