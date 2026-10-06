import { useEffect, useRef, useState } from 'react'
import { usePlatformSession } from '../../state/platform-session.ts'
import { Button } from '@/components/ui/button'
import { QueryToolbar } from '../../components/PageLayout.tsx'
import { recentConnectionChecks, runConnectionCheck, ConnectionCheckError, type ConnectionCheckPage, type SourceConnectionCheck } from '../../api/source-connection-checks.ts'

/**
 * Read-only source self-check. The message is not a second role="alert" because the host page owns the
 * single alert region its specs assert on; aria-live keeps it announced without changing that contract.
 */
export function SourceConnectionCheckPanel() {
  const [limit, setLimit] = useState(20)
  const [receipt, setReceipt] = useState<SourceConnectionCheck | null>(null)
  const [page, setPage] = useState<ConnectionCheckPage | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const controllerRef = useRef<AbortController | undefined>(undefined)
  const sequenceRef = useRef(0)
  function results() { setReceipt(null); setPage(null) }
  function clear() { controllerRef.current?.abort(); sequenceRef.current++; results(); setBusy(false); setError('') }
  const ready = usePlatformSession(change => { clear(); setError(change.error?.message ?? '') })
  useEffect(() => () => clear(), [])
  async function run(work: (signal: AbortSignal, current: () => boolean) => Promise<void>) {
    controllerRef.current?.abort(); const active = new AbortController(); controllerRef.current = active; const seq = ++sequenceRef.current
    setBusy(true); setError('')
    try { await work(active.signal, () => seq === sequenceRef.current) }
    catch (e) { if (seq !== sequenceRef.current) return; if (e instanceof ConnectionCheckError && [400, 401, 403, 503].includes(e.status)) results(); setError(e instanceof Error ? e.message : '来源连接自检待确认') }
    finally { if (seq === sequenceRef.current) setBusy(false) }
  }
  function size(value: number) { if (value === limit) return; setLimit(value); setPage(null) }
  function probe() { setPage(null); void run(async (signal, current) => { const r = await runConnectionCheck(signal); if (current()) setReceipt(r.check) }) }
  function load() { setReceipt(null); void run(async (signal, current) => { const p = await recentConnectionChecks(limit, signal); if (current()) setPage(p) }) }
  const disabled = busy || !ready
  return <section data-connection-check-panel><h3>来源连接自检（只读）</h3>
    <p>自检对已配置来源做一次有界只读探针：fixture 模式是显式标注的合成探针，JSON-RPC 模式只读 <code>apiinfo.version</code>。它不启动扫描、不取租约、不写库存、不调用模型；探针失败会记成失败回执，绝不回退成 fixture 成功。</p>
    <p>回执里的 dataMode 是该来源配置的模式，不是本次探针的结论；fixture 回执不代表真实厂商可用。statusCode 是稳定码而不是厂商原文，reportedVersion 是来源自报的版本（未验证），不构成平台已验证的支持声明。</p>
    <QueryToolbar label="来源自检与回执查询">
    <div role="group" aria-label="自检回执数量"><Button variant={limit === 10 ? 'default' : 'outline'} disabled={disabled} onClick={() => size(10)}>最近 10 条</Button><Button variant={limit === 20 ? 'default' : 'outline'} disabled={disabled} onClick={() => size(20)}>最近 20 条</Button><Button variant={limit === 50 ? 'default' : 'outline'} disabled={disabled} onClick={() => size(50)}>最近 50 条</Button></div>
    <Button variant="outline" disabled={disabled} onClick={probe}>来源连接自检</Button>
    <Button variant="outline" disabled={disabled} onClick={load}>读取自检回执</Button>
    </QueryToolbar>
    <p>自检需要来源级 source.sync 权限，按新到旧读取回执；每个 tenant/来源只保留最近 100 份，读取单页上限 50。</p>
    <p data-connection-check-error aria-live="assertive">{error}</p>
    {receipt ? <section data-connection-check><h3>本次自检回执</h3><CheckRow check={receipt} /></section> : null}
    {page ? <section data-connection-check-list><p>{`${page.tenantId} · ${page.sourceInstanceId} · 最近 ${page.items.length} 条（上限 ${page.limit}）`}</p>
      {page.items.length === 0 ? <p data-connection-check-empty>没有存储的自检回执；这不代表来源可用，也不代表从未自检过。</p> : null}
      {page.items.map(check => <CheckRow key={check.checkId} check={check} />)}
    </section> : null}
  </section>
}

function CheckRow(props: { check: SourceConnectionCheck }) {
  const check = props.check
  return <article data-connection-check-id={check.checkId}>
    <p>{`${check.actor} · ${check.checkedAt} · 来源模式 ${check.dataMode} · 可达 ${check.reachable ? '是' : '否'}`}</p>
    <p>{`状态码 ${check.statusCode}`}</p>
    <p>{check.reportedVersion === null ? '无版本声明（来源没有报告版本）' : `来源自报版本（未验证）：${check.reportedVersion}`}</p>
    <p>{check.checkId}</p>
  </article>
}
