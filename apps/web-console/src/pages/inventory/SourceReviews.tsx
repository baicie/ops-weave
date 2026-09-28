import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { usePlatformSession } from '../../state/platform-session.ts'
import { EntityRequestError, getEntity, type EntityItem } from '../../api/entities.ts'
import { readReviews, submitReview, reviewFields, type Choices, type Review, type ReviewField, type ReviewPage, type ReviewRequest } from '../../api/source-reviews.ts'
import type { IdentityPin } from '../../api/asset-identities.ts'

const labels: Record<ReviewField, string> = { name: '名称', ip: 'IP', owner: '负责人', environment: '环境' }
export function SourceReviews(props: { entity: EntityItem; changed: (entity: EntityItem) => void; identity?: IdentityPin }) {
  const [page, setPage] = useState<ReviewPage | null>(null)
  const [selected, setSelected] = useState<Review | null>(null)
  const [current, setCurrent] = useState(props.entity)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [receipt, setReceipt] = useState('')
  const [external, setExternal] = useState('')
  const [observed, setObserved] = useState(new Date().toISOString())
  const [values, setValues] = useState<Partial<Record<ReviewField, string>>>({})
  const [choices, setChoices] = useState<Choices>({})
  const [reason, setReason] = useState('')
  const [pending, setPending] = useState<ReviewRequest | null>(null)
  const [now, setNow] = useState(Date.now())
  const controllerRef = useRef<AbortController | undefined>(undefined)
  const sequenceRef = useRef(0)

  function clear() {
    controllerRef.current?.abort(); sequenceRef.current++; setPage(null); setSelected(null); setPending(null); setValues({}); setChoices({}); setReason(''); setExternal(''); setReceipt(''); setError(''); setBusy(false)
  }
  const authenticated = usePlatformSession(change => { clear(); if (change.error) setError(change.error.message) })
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30000)
    return () => { clear(); window.clearInterval(timer) }
  }, [])
  const enabled = !busy && authenticated && !pending
  function select(review: Review) { setSelected(review); setChoices({}); setReason(''); setError('') }
  async function run(request: ReviewRequest | null = null, after: string | null = null) {
    controllerRef.current?.abort(); const active = new AbortController(); controllerRef.current = active; const seq = ++sequenceRef.current
    setBusy(true); setError(''); if (request) setPending(request)
    try {
      if (request) {
        const result = await submitReview(props.entity, request, active.signal); if (seq !== sequenceRef.current) return
        setPending(null); setReceipt(`已保存：${result.id} · ${result.status} · 版本 ${result.version}`); select(result); setPage(null)
      }
      const [list, entity] = await Promise.all([readReviews(props.entity, after, active.signal), getEntity(props.entity.id, active.signal)])
      if (seq !== sequenceRef.current) return
      if (entity.tenantId !== props.entity.tenantId) throw new Error('资产租户与补充记录不一致')
      setPage(list); setCurrent(entity); props.changed(entity)
      if (!request) { setSelected(null); setChoices({}); setReason('') }
    } catch (cause) {
      if (seq !== sequenceRef.current) return
      if (cause instanceof EntityRequestError && [400, 401, 403, 404, 409].includes(cause.status)) setPending(null)
      setPage(null); setSelected(null)
      setError(cause instanceof Error ? cause.message : '补充来源不可用')
    } finally { if (seq === sequenceRef.current) setBusy(false) }
  }
  function stage() {
    const p = page; if (!p) return
    const requestId = crypto.randomUUID(), raw = Object.fromEntries(Object.entries(values).filter(([, v]) => v.trim()))
    void run({
      path: `/api/v1/entities/${props.entity.id}/source-reviews`, source: p.sourceInstanceId, reviewId: requestId,
      body: { requestId, expectedEntityVersion: current.version, externalId: external, observedAt: observed, values: raw, mappingDigest: p.mapping.digest, ...(props.identity ? { identity: props.identity } : {}) },
    })
  }
  function decide(action: 'ACCEPT' | 'REJECT' | 'REVOKE') {
    const r = selected; if (!r) return
    void run({
      path: `/api/v1/entities/${props.entity.id}/source-reviews/${r.id}/decisions`, source: r.sourceInstanceId, reviewId: r.id,
      body: { requestId: crypto.randomUUID(), expectedEntityVersion: current.version, expectedReviewVersion: r.version, action, choices: action === 'ACCEPT' ? choices : {}, reason },
    })
  }
  return <section data-source-reviews aria-label="补充来源字段审核">
    <h3>补充来源字段审核</h3>
    <p>CMDB 导入后先核对目标与外部编号，再逐字段确认。导入不判断在线状态，也不会按名称或 IP 自动合并资产。需要资产管理与来源同步权限。</p>
    {props.identity ? <p data-resolved-import>{`已按登记标识定位：${props.identity.namespace} / ${props.identity.value}。暂存与确认时会再次核对标识；撤销后旧导入不能生效。`}</p> : null}
    <div className="actions"><Button variant="outline" disabled={!enabled} onClick={() => { void run() }}>读取补充来源</Button>
      <Button variant="outline" disabled={!enabled || !page?.nextCursor} onClick={() => { void run(null, page?.nextCursor ?? null) }}>下一页补充记录</Button></div>
    <p role="status" data-source-review-receipt>{receipt}</p><p data-source-review-error>{error}</p>
    {pending ? <><p>提交结果待确认。保留原请求标识：{String(pending.body.requestId)}；请按原请求重试，避免重复建立记录。</p>
      <Button variant="outline" disabled={busy} onClick={() => { void run(pending) }}>按原请求重试</Button></> : null}
    {page ? <>
      <p>{`import · ${page.storage} · 来源 ${page.sourceInstanceId} · 映射 ${page.mapping.engine} / ${page.mapping.digest}`}</p>
      {page.active ? <><p data-active-source>{`生效记录 ${page.active.id} · ${Date.parse(page.active.expiresAt) <= now ? '已过期，仅保留最后已知字段' : '有效期内'} · 到期 ${page.active.expiresAt}`}</p>
        <Button variant="outline" disabled={!enabled} onClick={() => select(page.active!)}>查看生效字段与撤销</Button></> : null}
      <details><summary>导入一条补充记录</summary><div className="metric-controls">
        <label>CMDB 外部编号<Input value={external} onChange={event => setExternal(event.currentTarget.value)} /></label>
        <label>来源观测时间（UTC）<Input value={observed} onChange={event => setObserved(event.currentTarget.value)} /></label>
        {reviewFields.map(field => <ImportField key={field} field={field} value={values[field] ?? ''} changed={(key, value) => setValues({ ...values, [key]: value })} />)}
      </div><p>仅填写要补充的字段，空白表示未提供；时间须在最近 7 天内。这里提交的是人工导入数据，未连接 CMDB 厂商 API。</p>
      <Button variant="outline" disabled={!enabled || !external.trim() || !Object.values(values).some(v => v?.trim())} onClick={stage}>暂存并预览</Button></details>
      <p>{`本页 ${page.items.length} 条；记录按 ID 分页。生效绑定须先撤销，才能接受另一条记录。`}</p>
      {page.items.map(row => <ReviewRow key={row.id} review={row} disabled={!enabled} select={select} />)}
    </> : null}
    {selected ? <section data-source-review-preview>
      <h4>{`核对 ${selected.externalId} · ${selected.status}`}</h4>
      <p>{`目标资产 ${props.entity.id} · 暂存时版本 ${selected.baseVersion} · 当前版本 ${current.version}`}</p>
      <p>{`观测 ${selected.observedAt} · 接收 ${selected.ingestedAt} · 到期 ${selected.expiresAt}`}</p>
      <p>{`导入人 ${selected.actor} · Raw ${selected.rawRecordRef}`}</p>
      {selected.identity ? <p data-review-identity>{`身份依据 ${selected.identity.namespace} / ${selected.identity.value} · 登记 ${selected.identity.id}`}</p> : null}
      {Date.parse(selected.expiresAt) <= now ? <p>来源记录已过期，不能接受；已生效字段仅作为最后已知值保留。</p> : null}
      <div className="pipeline-table"><table><thead><tr><th>字段</th><th>导入时主来源</th><th>补充来源原始值</th><th>字段权威选择</th></tr></thead><tbody>
        {reviewFields.filter(key => Object.hasOwn(selected.values, key)).map(field => (
          <ReviewFieldRow key={field} field={field} review={selected}
            choice={choices[field] ?? ''} disabled={selected.status !== 'PENDING' || !enabled} choose={(key, value) => setChoices({ ...choices, [key]: value })} />
        ))}
      </tbody></table></div>
      {selected.decisions.map((row, index) => <ReviewDecision key={`${row.at}-${row.action}-${index}`} decision={row} />)}
      {selected.status === 'PENDING' || (selected.status === 'ACCEPTED' && page?.active?.id === selected.id) ? <>
        <label>核对或撤销原因<Input value={reason} onChange={event => setReason(event.currentTarget.value)} /></label>
        <div className="actions">{selected.status === 'PENDING' ? <>
          <Button variant="default" disabled={!enabled || !reason.trim() || Date.parse(selected.expiresAt) <= now || selected.baseVersion !== current.version || Object.keys(selected.values).some(key => !choices[key as ReviewField]) || !!page?.active} onClick={() => decide('ACCEPT')}>确认字段选择</Button>
          <Button variant="outline" disabled={!enabled || !reason.trim()} onClick={() => decide('REJECT')}>拒绝这条导入</Button></> : null}
          {selected.status === 'ACCEPTED' ? <Button variant="outline" disabled={!enabled || !reason.trim()} onClick={() => decide('REVOKE')}>撤销并恢复最新主来源</Button> : null}</div>
      </> : null}
    </section> : null}
  </section>
}

function ImportField(props: { field: ReviewField; value: string; changed: (field: ReviewField, value: string) => void }) {
  return <label>{`导入${labels[props.field]}`}<Input value={props.value} onChange={event => props.changed(props.field, event.currentTarget.value)} /></label>
}
function ReviewRow(props: { review: Review; disabled: boolean; select: (review: Review) => void }) {
  return <article className="panel" data-source-review-record><p>{`${props.review.externalId} · ${props.review.status} · ${props.review.observedAt} · ${props.review.id}`}</p>
    <Button variant="outline" disabled={props.disabled} onClick={() => props.select(props.review)}>核对这条记录</Button></article>
}
function ReviewFieldRow(props: { field: ReviewField; review: Review; choice: string; disabled: boolean; choose: (field: ReviewField, value: Choices[ReviewField]) => void }) {
  return <tr><td>{labels[props.field]}</td><td>{props.review.primaryAtImport[props.field] ?? '未提供'}</td><td>{props.review.values[props.field]}</td><td>
    {props.review.status === 'PENDING' ? (
      <select aria-label={`选择${labels[props.field]}来源`} value={props.choice} disabled={props.disabled} onChange={event => props.choose(props.field, event.currentTarget.value as Choices[ReviewField])}>
        <option value="">请选择</option><option value="PRIMARY">保留主来源</option><option value="SUPPLEMENTAL">采用补充来源</option>
      </select>
    ) : null}
    {props.review.status !== 'PENDING' ? <span>{props.review.decisions[0]?.choices[props.field] === 'SUPPLEMENTAL' ? '采用补充来源' : props.review.decisions[0]?.choices[props.field] === 'PRIMARY' ? '保留主来源' : '未确认'}</span> : null}</td></tr>
}
function ReviewDecision(props: { decision: Review['decisions'][number] }) {
  return <p data-review-decision>{`${props.decision.action} · ${props.decision.actor} · ${props.decision.at} · ${props.decision.reason} · ${JSON.stringify(props.decision.choices)}`}</p>
}
