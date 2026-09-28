// Synthetic corpus only: no storage startup, external model or human sign-off.
import assert from 'node:assert/strict'
import { createHash, randomBytes } from 'node:crypto'
import { spawn } from 'node:child_process'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const root = fileURLToPath(new URL('../', import.meta.url))
assert(process.argv.slice(2).every(arg => arg === '--all-features'), 'Only --all-features is supported')
const all = process.argv.includes('--all-features')
const name = new Date().toISOString().replaceAll(/[:.]/g, '-') + '-' + randomBytes(4).toString('hex')
const directory = path.join(root, '.tmp/current-diagnosis-eval', name)
await mkdir(directory, { recursive: true })
const reportPath = path.join(directory, 'report.json'), reviewPath = path.join(directory, 'review.md')
const args = ['test', '-p', 'opsweave-agent-runtime', '--locked']
if (all) args.push('--all-features')
args.push('current_workflows::evaluation_tests::annotated_current_corpus_runs_real_workflow_with_fixture_ports', '--', '--exact')
await new Promise((resolve, reject) => {
  const child = spawn('cargo', args, { cwd: root, windowsHide: true, stdio: 'inherit', env: { ...process.env, OPSWEAVE_EVAL_REPORT: reportPath } })
  child.once('error', () => reject(new Error('Cargo evaluation launch failed')))
  child.once('exit', code => code === 0 ? resolve() : reject(new Error('Corpus evaluation failed; no review is published for this run')))
})
const bytes = await readFile(path.join(root, 'contracts/evals/current-diagnosis.json'))
const size = Buffer.alloc(8); size.writeBigUInt64BE(BigInt(bytes.length))
const digest = 'sha256:' + createHash('sha256').update(size).update(bytes).digest('hex')
const corpus = JSON.parse(bytes), report = JSON.parse(await readFile(reportPath, 'utf8'))
assert.equal(report.corpusDigest, digest, 'Report must match the exact checked-in corpus')
assert.equal(report.dataMode, 'labeled-fixture')
assert.equal(report.verification, 'workflow-boundaries-and-reference-integrity-only')
assert.equal(report.allMachineChecksPassed, true)
assert.equal(report.actualProviderCalls, 0)
assert.equal(report.realModelComparison, 'not-run')
assert.equal(report.humanReview, 'pending')
assert.equal(report.rootCauseAccuracy, null)
assert.deepEqual(report.skillRef, corpus.skillRef)
assert.deepEqual(report.cases.map(c => c.id), corpus.cases.map(c => c.id))
const tick = String.fromCharCode(96), slash = String.fromCharCode(92)
const markdownChars = '*_[]<>|#' + tick + slash
const escape = text => Array.from(String(text), c => markdownChars.includes(c) ? slash + c : c).join('').replaceAll('\n', ' ')
const fenced = value => {
  const text = JSON.stringify(value, null, 2)
  const longest = Math.max(2, ...[...text.matchAll(new RegExp(tick + '+', 'g'))].map(m => m[0].length))
  const fence = tick.repeat(longest + 1)
  return fence + 'json\n' + text + '\n' + fence
}
const lines = ['# 当前诊断：合成语料评估记录', '',
  '本次只运行版本化 fixture 和实际 Rust 工作流。平台读取、费用许可和保存均为内存测试端口，未启动 PG、调用外部来源或真实模型。', '',
  '确定性摘要来自现有 MockModel；脚本输出用于检查流程边界。它们不能证明模型增加信息、理解冲突或达到 RCA 准确率。', '',
  '真实模型对照：未执行。人工审阅：待完成。全部机器断言通过也不关闭 M4。', '',
  '生成时间：' + escape(report.createdAt), '', '语料摘要：' + escape(report.corpusDigest), '',
  'Skill：' + escape(report.skillRef.id + '@' + report.skillRef.version + ' ' + report.skillRef.digest), '',
  '时间策略：整组合成时间戳共同平移到当前访问时间附近，保留相对时间、过期和查询窗口；每个对照对使用完全相同的输入。', '',
  '| 案例 | 分类 | 确定性流程 | 脚本边界检查 | 真实模型 | 人工审阅 |',
  '|---|---|---|---|---|---|']
for (const item of report.cases) {
  assert.equal(item.realModelComparison, 'not-run'); assert.equal(item.humanReview, 'pending')
  for (const result of [item.deterministicBaseline, item.scriptedBoundaryCheck]) {
    assert.equal(result.actualProviderCalls, 0)
    assert.equal(result.savedResultStorage, result.outcome === 'saved' ? 'memory' : null)
  }
  lines.push('| ' + [item.id, item.category, item.deterministicBaseline.outcome, item.scriptedBoundaryCheck.outcome, '未执行', '待完成'].map(escape).join(' | ') + ' |')
}
for (const item of report.cases) {
  const seed = corpus.cases.find(c => c.id === item.id)
  lines.push('', '## ' + escape(item.id), '', '以下为待人工核对的合成标注，不是已签署的事实质量结论。', '',
    '应有证据支持：', '', ...item.annotation.supportedFacts.map(s => '- ' + escape(s)), '',
    '不应作出的判断：', '', ...item.annotation.forbiddenClaims.map(s => '- ' + escape(s)), '',
    '确定性流程结果：', '', fenced(item.deterministicBaseline.savedFixtureSubmission?.insight ?? { outcome: item.deterministicBaseline.outcome }), '',
    '脚本边界输入（明确为模拟文本，不是实际模型响应）：', '', fenced(seed.scriptedModel), '',
    '脚本流程计数：', '', fenced(Object.fromEntries(Object.entries(item.scriptedBoundaryCheck).filter(([key]) => !['rebasedFixtureContext', 'savedFixtureSubmission'].includes(key)))), '',
    '人工对照问题：', '', ...item.annotation.reviewQuestions.map(s => '- ' + escape(s)), '',
    '真实模型文本、额外可核验信息、错误事实、遗漏、费用和审阅人：均待真实模型运行后记录，不从本次 fixture 结果自动填入。')
}
await writeFile(reviewPath, lines.join('\n') + '\n')
console.log('PASS: 10 synthetic cases evaluated; actual provider calls = 0; real-model comparison and human review remain pending.')
console.log('Review: ' + reviewPath)
console.log('Machine report: ' + reportPath)
