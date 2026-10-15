#!/usr/bin/env python3
"""Copy packaged skill folders to .claude/skills without replacing existing skills by default."""
import argparse
from pathlib import Path
import shutil

parser = argparse.ArgumentParser()
parser.add_argument('--force', action='store_true', help='Replace existing same-name directories')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
src = root / '.agents' / 'skills'
target = root / '.claude' / 'skills'
if not src.is_dir():
    raise SystemExit('Missing .agents/skills; run this from the copied kit inside repo root')
target.mkdir(parents=True, exist_ok=True)
for folder in sorted(src.iterdir()):
    if not folder.is_dir() or not (folder / 'SKILL.md').exists():
        continue
    dest = target / folder.name
    if dest.exists():
        if not args.force:
            print(f'SKIP {dest} (existing; specify --force to replace)')
            continue
        shutil.rmtree(dest)
    shutil.copytree(folder, dest)
    print(f'COPY {folder.name} -> {dest}')
print('Review the copied SKILL.md files before use. No AGENTS.md change was made.')
