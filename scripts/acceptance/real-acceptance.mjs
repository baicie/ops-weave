// CLI for the bounded platform-only acceptance probes.
import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runAcceptance } from './acceptance-runner.mjs'

const root = fileURLToPath(new URL('../../', import.meta.url))
const args = process.argv.slice(2)
const reportArg = args.find(value => value.startsWith('--report='))
const reportPath = reportArg ? path.resolve(reportArg.slice('--report='.length))
  : path.join(root, '.tmp/acceptance', new Date().toISOString().replaceAll(':', '-') + '-report.json')
const { report, exitCode } = await runAcceptance(process.env, args)
try {
  await mkdir(path.dirname(reportPath), { recursive: true })
  await writeFile(reportPath, JSON.stringify(report, null, 2), { mode: 0o600 })
  console.log('mode=' + report.mode + '; status=' + report.status + '; report written')
  console.log('NOT milestone acceptance: target environment, human review, identity/TLS and failure-path checks remain unverified.')
  process.exitCode = exitCode
} catch {
  console.error('REPORT_WRITE_FAILED'); process.exitCode = 1
}
