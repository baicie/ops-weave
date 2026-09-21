import { StrictMode, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';

type Finding = { kind: 'observation' | 'hypothesis'; statement: string; evidenceRefs: string[] };
type Evidence = { id: string; kind: string; summary: string; sourceRef: string };
type Result = {
  runId: string; dataMode: string; modelProvider: string; verification: string;
  context: { evidence: Evidence[] };
  insight: { summary: string; findings: Finding[]; missingData: string[]; limitations: string[] };
};
function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every(item => typeof item === 'string');
}
function isResult(value: unknown): value is Result {
  if (!isRecord(value) || !isRecord(value.insight) || !isRecord(value.context)) return false;
  const insight = value.insight;
  if (!['runId', 'dataMode', 'modelProvider', 'verification'].every(key => typeof value[key] === 'string')) return false;
  return typeof insight.summary === 'string' && isStringArray(insight.missingData) && isStringArray(insight.limitations)
    && Array.isArray(insight.findings) && insight.findings.every(f => isRecord(f)
      && (f.kind === 'observation' || f.kind === 'hypothesis') && typeof f.statement === 'string' && isStringArray(f.evidenceRefs))
    && Array.isArray(value.context.evidence) && value.context.evidence.every(e => isRecord(e)
      && ['id', 'kind', 'summary', 'sourceRef'].every(key => typeof e[key] === 'string'));
}
function App() {
  const [token, setToken] = useState('');
  const [question, setQuestion] = useState('为什么订单服务延迟升高？');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState<Result | null>(null);
  async function diagnose() {
    setBusy(true); setError(''); setResult(null);
    const to = new Date(Date.now() - 1000);
    const from = new Date(to.getTime() - 30 * 60 * 1000);
    try {
      const response = await fetch('/agent/api/v1/diagnoses', {
        method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ incidentId: 'inc-demo', question, timeRange: { from: from.toISOString(), to: to.toISOString() }, asOf: to.toISOString() }),
        signal: AbortSignal.timeout(35000),
      });
      if (!response.ok) { throw new Error(`诊断失败（HTTP ${response.status}），检查 Demo 模式、Token 和终端日志。`); }
      const value: unknown = await response.json();
      if (!isResult(value)) { throw new Error('接口响应结构不正确'); }
      setResult(value);
    } catch (e) { setError(e instanceof Error ? e.message : '请求失败'); }
    finally { setBusy(false); }
  }
  return <main>
    <header><span className="eyebrow">OPSWEAVE / 观织 / V4</span><h1>可观测与智能运维平台</h1>
      <p>Java 平台 · Rust Agent Runtime · TypeScript 控制台</p></header>
    <section className="next"><h2>只读诊断 Demo</h2>
      <p>使用固定的合成故障数据，不查询真实 Zabbix，也不执行生产动作。先在本机启动 Rust Demo。</p>
      <label>开发 Token（仅保存在当前页面内存）<input type="password" autoComplete="off" value={token} onChange={e => setToken(e.target.value)} /></label>
      <label>问题<textarea value={question} maxLength={2000} onChange={e => setQuestion(e.target.value)} /></label>
      <button disabled={busy || token.length < 32 || !question.trim()} onClick={() => { void diagnose(); }}>{busy ? '正在诊断…' : '运行只读诊断'}</button>
      <p role="alert">{error}</p>
    </section>
    {result && <section className="next" aria-live="polite"><h2>{result.insight.summary}</h2>
      <p><code>{result.dataMode} / {result.modelProvider}</code></p>
      {result.insight.findings.map((f, i) => <article key={i}><b>{f.kind === 'hypothesis' ? '候选解释' : '观测'}</b><p>{f.statement}</p><p>{f.evidenceRefs.map(id => <a key={id} href={'#evidence-' + encodeURIComponent(id)}>{id} </a>)}</p></article>)}
      <h3>证据（合成数据）</h3>{result.context.evidence.map(e => <details key={e.id} id={'evidence-' + encodeURIComponent(e.id)}>
        <summary>{e.id} · {e.kind}</summary><p>{e.summary}</p><code>{e.sourceRef}</code>
      </details>)}
      <h3>缺少的数据</h3><p>{result.insight.missingData.join('；')}</p>
      <h3>限制</h3><p>{result.insight.limitations.join('；')}</p>
      <small>Run: {result.runId} · {result.verification}</small>
    </section>}
    <section className="grid">{['数据接入 / Connector','资源 / Entity 与 Relation','故障 / Incident','Skill / 配置与发布'].map(x => <article key={x}><span className="badge">平台业务待实现</span><h3>{x}</h3></article>)}</section>
    <footer>模板仓库，不是生产版本。证据引用校验不代表根因已经被证明。</footer>
  </main>;
}
const root = document.getElementById('root');
if (!root) throw new Error('Missing root element');
createRoot(root).render(<StrictMode><App /></StrictMode>);
