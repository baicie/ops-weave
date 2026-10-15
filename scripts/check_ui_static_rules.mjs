#!/usr/bin/env node
/**
 * Basic static audit; designed to be dependency-free and non-destructive.
 * This cannot measure visual aesthetics or accessibility compliance; it only flags risky text patterns.
 * Usage: node scripts/check_ui_static_rules.mjs --root apps/web-console [--strict]
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { resolve, relative } from 'node:path';
const argv = process.argv.slice(2);
const rootArg = argv.includes('--root') ? argv[argv.indexOf('--root') + 1] : 'apps/web-console';
const strict = argv.includes('--strict');
const root = resolve(process.cwd(), rootArg);
if (!existsSync(root)) {
  console.error(`[ui-rules] Missing root: ${rootArg}. New frontend may not have been created yet.`);
  process.exit(2);
}
const excluded = new Set(['node_modules', 'dist', 'build', 'coverage', 'test-results', '.git']);
const files = [];
function walk(dir) {
  for (const entry of readdirSync(dir)) {
    if (excluded.has(entry)) continue;
    const path = resolve(dir, entry);
    if (statSync(path).isDirectory()) walk(path);
    else if (/\.(tsx|jsx|css)$/.test(entry) && !/\.spec\./.test(entry)) files.push(path);
  }
}
walk(root);
const issues = [];
function add(file, line, level, rule, note) {
  issues.push({ file: relative(process.cwd(), file), line, level, rule, note });
}
for (const file of files) {
  const value = readFileSync(file, 'utf8');
  const lines = value.split(/\r?\n/);
  if (/\.(jsx|tsx)$/.test(file) && lines.length > 350) {
    add(file, 1, 'warn', 'COMPONENT_SIZE', `${lines.length} lines; consider splitting view model/presentation`);
  }
  lines.forEach((line, index) => {
    const number = index + 1;
    if (/\.(jsx|tsx)$/.test(file)) {
      if (/\bwindow\.alert\s*\(|\balert\s*\(/.test(line)) add(file, number, 'error', 'ALERT_UI', 'Use app notification/dialog primitives instead');
      if (/\b(?:style=\{\{[^\n]*?(?:color|backgroundColor)\s*:)/.test(line)) add(file, number, 'warn', 'INLINE_COLOR', 'Use semantic token classes instead of inline colors');
      if (/(?:bg|text|border)-\[#[0-9a-fA-F]{3,8}\]/.test(line)) add(file, number, 'error', 'HEX_UTILITY', 'Use semantic color token instead of per-component Tailwind hex');
    }
    if (/\.css$/.test(file)) {
      if (/!important\b/.test(line) && !/prefers-reduced-motion|animation-duration|transition-duration|scroll-behavior/.test(line)) {
        add(file, number, 'warn', 'IMPORTANT', 'Verify specificity and avoid !important for layout');
      }
      // Tokens file is the single explicit exception for named hex values.
      if (/#[0-9a-fA-F]{3,8}\b/.test(line) && !/tokens(\.v2)?\.css$/.test(file)) {
        add(file, number, 'warn', 'CSS_HEX', 'Prefer --ow-* semantic design tokens; intentional exceptions require review');
      }
    }
  });
}
const counts = { error: issues.filter(x => x.level === 'error').length, warn: issues.filter(x => x.level === 'warn').length };
for (const issue of issues) console.log(`${issue.level.toUpperCase()} ${issue.rule} ${issue.file}:${issue.line} ${issue.note}`);
console.log(`[ui-rules] scanned=${files.length}, errors=${counts.error}, warnings=${counts.warn}, mode=${strict ? 'strict' : 'advisory'}`);
console.log('[ui-rules] Static only: cannot pass a browser/screenshot/UX visual gate.');
if (strict && counts.error > 0) process.exitCode = 1;
