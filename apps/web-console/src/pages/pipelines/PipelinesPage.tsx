import { usePlatformSession } from '../../state/platform-session.ts'
import { createSignal, For, onCleanup, Show } from '@zeus-js/zeus'
import { forItem } from '../../adapters/zeus-ui/for-item.ts'
import { ZwInput } from '../../adapters/zeus-ui/ZwInput.tsx'
import { ZwButton } from '../../adapters/zeus-ui/ZwButton.tsx'
import { getPipeline, hostDefinition, previewPipeline, publishPipeline, executeReplay, getReplay, listReplays, syncSample,
  saveDraft, getDraft, listDrafts, sameDraftDefinition, type Draft, type DraftList, type DraftHeader,
  type Evaluation, type EvaluationRow, type Published, type ReplayDetail, type ReplayHeader, type ReplayHistory, type ReplayRequest } from '../../api/pipelines.ts'

export function PipelinesPage() {
  const authenticated = usePlatformSession(change => { invalidate(); setRunId(''); setHistory(null); setDraft(null); setDrafts(null); setId('zabbix-host-default'); setRevision('2'); setNameField('name'); setPolicy('skipRecord'); if (change.error) setError(change.error.message) })
  const [runId, setRunId] = createSignal('')
  const [id, setId] = createSignal('zabbix-host-default')
  const [revision, setRevision] = createSignal('2')
  const [nameField, setNameField] = createSignal('name')
  const [policy, setPolicy] = createSignal('skipRecord')
  const [busy, setBusy] = createSignal(false)
  const [error, setError] = createSignal('')
  const [notice, setNotice] = createSignal('')
  const [report, setReport] = createSignal<Evaluation | null>(null)
  const [published, setPublished] = createSignal<Published | null>(null)
  const [detail, setDetail] = createSignal<ReplayDetail | null>(null)
  const [history, setHistory] = createSignal<ReplayHistory | null>(null)
  const [pending, setPending] = createSignal<ReplayRequest | null>(null)
  const [draft, setDraft] = createSignal<Draft | null>(null)
  const [drafts, setDrafts] = createSignal<DraftList | null>(null)
  let abort: AbortController | undefined
  let generation = 0
  let disposed = false
  onCleanup(() => { disposed = true; abort?.abort() })

  function invalidate() {
    abort?.abort(); generation++
    setBusy(false); setError(''); setNotice(''); setReport(null); setPublished(null); setDetail(null); setPending(null)
  }
  function draftMatchesForm() {
    try { return !!draft() && sameDraftDefinition(draft()!.definition, hostDefinition(id(), Number(revision()), nameField(), policy())) }
    catch { return false }
  }
  const validRun = () => /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(runId())
  async function execute(action: 'sync' | 'preview' | 'publish' | 'read' | 'replay' | 'history' | 'next' | 'detail' | 'resume' | 'retry' | 'draft-save' | 'draft-read' | 'draft-list', selectedId?: string, selectedRevision?: number) {
    abort?.abort()
    const controller = new AbortController(); abort = controller
    const current = ++generation
    const previousReport = report(), previousPublished = published()
    const previousDetail = detail(), previousPending = pending()
    setBusy(true); setError(''); setNotice(''); setReport(null)
    if (['sync', 'preview', 'publish', 'read', 'draft-save', 'draft-read'].includes(action)) { setPublished(null); setPending(null) }
    if (action === 'draft-read' || action === 'read') setDraft(null)
    if (action === 'draft-list') setDrafts(null)
    setDetail(null)
    const timeout = window.setTimeout(() => controller.abort(), 35000)
    const active = () => !disposed && generation === current
    try {
      if (action === 'draft-save') {
        const definition = hostDefinition(id(), Number(revision()), nameField(), policy())
        const result = await saveDraft(definition, draft()?.editVersion ?? 0, controller.signal)
        if (active()) { setDraft(result); setNotice('草稿已保存。发布前请重新预览当前内容。') }
      } else if (action === 'draft-read') {
        const result = await getDraft(selectedId ?? id(), selectedRevision ?? Number(revision()), controller.signal)
        if (active()) {
          setId(result.definition.id); setRevision(String(result.definition.revision))
          setNameField(result.definition.nodes.find(node => node.type === 'Map')?.config.displayNameField ?? 'name')
          setPolicy(result.definition.errorPolicy); setDraft(result); setNotice('草稿已载入，当前表单已替换；发布前需要预览。')
        }
      } else if (action === 'draft-list') {
        const result = await listDrafts(controller.signal)
        if (active()) { setDrafts(result); setNotice(result.items.length ? '已读取本人最近草稿。' : '当前范围没有草稿。') }
      } else if (action === 'history' || action === 'next') {
        const page = await listReplays(action === 'next' ? history()?.nextCursor ?? null : null, controller.signal)
        if (active()) { setHistory(page); setNotice(page.items.length ? '已读取本人重放记录。' : '当前范围没有重放记录。') }
      } else if (action === 'detail') {
        if (!selectedId) throw new Error('请先选择记录')
        const result = await getReplay(selectedId, controller.signal)
        if (active()) { setDetail(result); setReport(result.report); setPending(null) }
      } else if (action === 'resume' || action === 'retry') {
        const body = action === 'retry' ? previousPending : previousDetail ? { requestKey: previousDetail.run.requestKey, ...previousDetail.run.spec } : null
        if (!body) throw new Error('请先选择可恢复记录')
        setPending(body)
        const result = await executeReplay(body, controller.signal)
        if (active()) { setDetail(result); setReport(result.report); setPending(null) }
      } else if (action === 'sync') {
        const run = await syncSample(controller.signal)
        if (active()) { setRunId(run); setNotice('Host 同步完成，可预览此批次的原始记录。') }
      } else {
        const definition = hostDefinition(id(), Number(revision()), nameField(), policy())
        if (action === 'preview') {
          const result = await previewPipeline(runId(), definition, controller.signal)
          if (active()) setReport(result)
        } else if (action === 'publish') {
          if (!previousReport || previousReport.mode !== 'PREVIEW') throw new Error('请先预览当前定义')
          const version = await publishPipeline(definition, previousReport.targetVersion, controller.signal)
          if (active()) { setPublished(version); setReport(previousReport); setNotice('版本已发布，后续默认同步仍使用内置版本。') }
        } else if (action === 'read') {
          const version = await getPipeline(id(), Number(revision()), controller.signal)
          if (active()) {
            setNameField(version.definition.nodes.find(node => node.type === 'Map')?.config.displayNameField ?? 'name')
            setPolicy(version.definition.errorPolicy); setPublished(version); setNotice('已载入发布内容，可对指定批次只读重放。')
          }
        } else {
          if (!previousPublished) throw new Error('请先发布或读取版本')
          const body: ReplayRequest = { requestKey: crypto.randomUUID(), syncRunId: runId(), targetVersion: { id: previousPublished.definition.id,
            revision: previousPublished.definition.revision, digest: previousPublished.digest }, purpose: 'COMPARE_VERSION', limit: 100 }
          setPending(body)
          const result = await executeReplay(body, controller.signal)
          if (active()) { setDetail(result); setReport(result.report); setPending(null) }
        }
      }
    } catch (cause) {
      if (active()) {
        const message = cause instanceof Error ? cause.message : '请求失败'
        setError(controller.signal.aborted ? '请求已超时，服务端可能已完成。请读取草稿、版本或记录核对；重放可使用原请求键重试。'
          : message.includes('DRAFT_CONFLICT') ? '草稿已被其他保存更新（DRAFT_CONFLICT）。当前编辑内容仍保留；请记录修改后读取草稿并重新比较，未自动覆盖。' : message)
      }
    } finally {
      window.clearTimeout(timeout)
      if (active()) setBusy(false)
    }
  }
  return (
    <section class="panel pipeline-controls" data-page="pipelines">
      <h2>Host 接入流水线</h2>
      <p>先预览历史原始记录，再发布固定版本。重放仅比较映射结果，不写库存；差异不代表当前资产的增删。</p>
        <label>同步批次 ID<ZwInput value={runId()} onValueChange={value => { invalidate(); setRunId(value) }} /></label>
      <ZwButton variant="outline" disabled={busy() || !authenticated()} onPress={() => { void execute('sync') }}>同步 Host 并取得批次</ZwButton>
      <p>同步按钮会从已配置来源采集并更新资产。也可直接填写已有批次 ID；来源模式由服务端配置决定。</p>
      <div class="pipeline-form">
        <label>流水线 ID<ZwInput value={id()} onValueChange={value => { invalidate(); setDraft(null); setId(value) }} /></label>
        <label>版本号<ZwInput value={revision()} onValueChange={value => { invalidate(); setDraft(null); setRevision(value) }} /></label>
        <label>主机显示名<select prop:value={nameField()} onChange={event => { invalidate(); setNameField((event.target as HTMLSelectElement).value) }}>
          <option value="name">可见名称（name）</option><option value="host">技术名称（host）</option>
        </select></label>
        <label>记录拒绝策略<select prop:value={policy()} onChange={event => { invalidate(); setPolicy((event.target as HTMLSelectElement).value) }}>
          <option value="skipRecord">跳过拒绝记录</option><option value="failFast">立即终止同步</option>
        </select></label>
      </div>
      <p>固定流程：来源 → 解析 → 映射 → 校验 → 实体匹配 → 观测写入</p>
      <div class="actions">
        <ZwButton variant="outline" disabled={busy() || !authenticated()} onPress={() => { void execute('draft-save') }}>保存草稿</ZwButton>
        <ZwButton variant="outline" disabled={busy() || !authenticated()} onPress={() => { void execute('draft-read') }}>读取当前版本草稿</ZwButton>
        <ZwButton variant="outline" disabled={busy() || !authenticated()} onPress={() => { void execute('draft-list') }}>读取最近草稿</ZwButton>
      </div>
      <p>草稿仅本人可见。保存不会发布；载入会替换当前表单。已有发布内容需要新版本号才能变更。</p>
      <Show when={draft()}><p data-draft>{`草稿编辑号 ${draft()?.editVersion} · ${draftMatchesForm() ? '已保存' : '有未保存修改'} · ${draft()?.storage === 'postgres' ? 'PostgreSQL' : '开发内存（服务重启后丢失）'}`}</p></Show>
      <Show when={drafts()}><div data-draft-list>
        <p>{drafts()?.storage === 'postgres' ? '草稿存储：PostgreSQL' : '草稿存储：开发内存，服务重启后丢失'}</p>
        <Show when={drafts()?.truncated}><p>仅显示最近 20 份草稿；较早草稿可填写 ID 和版本号读取。</p></Show>
        <div class="pipeline-table"><table><thead><tr><th>草稿</th><th>编辑号</th><th>更新时间</th><th>操作</th></tr></thead><tbody>
          <For each={drafts()?.items ?? []}>{item => <DraftTableRow item={forItem(item)} busy={busy()} select={target => { void execute('draft-read', target.target.id, target.target.revision) }} />}</For>
        </tbody></table></div>
      </div></Show>
      <div class="actions">
        <ZwButton variant="primary" disabled={busy() || !authenticated() || !validRun()} onPress={() => { void execute('preview') }}>预览当前定义</ZwButton>
        <ZwButton variant="outline" disabled={busy() || report()?.mode !== 'PREVIEW' || published() !== null} onPress={() => { void execute('publish') }}>发布预览版本</ZwButton>
        <ZwButton variant="outline" disabled={busy() || !authenticated()} onPress={() => { void execute('read') }}>读取已发布版本</ZwButton>
        <ZwButton variant="outline" disabled={busy() || !validRun() || published() === null || pending() !== null} onPress={() => { void execute('replay') }}>只读重放</ZwButton>
      </div>
      <Show when={pending()}><p>上次重放尚未确认。重试会沿用请求键和参数，也可先读取历史记录核对。</p>
        <ZwButton variant="outline" disabled={busy()} onPress={() => { void execute('retry') }}>按原请求重试</ZwButton></Show>
      <h3>重放记录</h3>
      <p>仅显示当前身份在此来源发起的记录。刷新页面后重新输入 Token 并读取记录；Token 不会持久保存。</p>
      <div class="actions">
        <ZwButton variant="outline" disabled={busy() || !authenticated()} onPress={() => { void execute('history') }}>读取重放记录</ZwButton>
        <ZwButton variant="outline" disabled={busy() || !history()?.nextCursor} onPress={() => { void execute('next') }}>下一页记录</ZwButton>
      </div>
      <Show when={history()}><p>{history()?.storage === 'postgres' ? '记录存储：PostgreSQL' : '记录存储：开发内存，服务重启后丢失'}</p>
        <div class="pipeline-table"><table data-replay-history><thead><tr><th>记录</th><th>目标版本</th><th>状态</th><th>操作</th></tr></thead><tbody>
          <For each={history()?.items ?? []}>{item => <ReplayHistoryRow item={forItem(item)} busy={busy()} select={id => { void execute('detail', id) }} />}</For>
        </tbody></table></div></Show>
      <p role="alert">{error()}</p><p role="status">{busy() ? '正在处理…' : notice()}</p>
      <Show when={published()}><p data-published>{`已发布：${published()?.definition.id}@${published()?.definition.revision} / ${published()?.digest}`}</p></Show>
      <Show when={detail()}><div data-replay-detail>
        <p>{`记录 ${detail()?.run.id} · ${detail()?.run.state} · 第 ${detail()?.run.attempt}/3 次 · ${detail()?.storage === 'postgres' ? 'PostgreSQL' : '开发内存（服务重启后丢失）'}`}</p>
        <p>{detail()?.run.failureCode ? `失败代码：${detail()?.run.failureCode}` : detail()?.run.state === 'RUNNING' ? `执行租约截至 ${detail()?.run.leaseUntil}；刷新状态不会自动重试。` : '结果已保存，重复请求不会重新计算。'}</p>
        <ZwButton variant="outline" disabled={busy()} onPress={() => { void execute('detail', detail()?.run.id) }}>刷新记录状态</ZwButton>
        <ZwButton variant="outline" disabled={busy() || !canRecover(detail())} onPress={() => { void execute('resume') }}>恢复或确认过期记录</ZwButton>
        <p>失败或租约过期可显式恢复，最多三次。第三次租约过期后，此操作仅确认终止。</p>
      </div></Show>
      <Show when={report()}><EvaluationView report={report() as Evaluation} /></Show>
    </section>
  )
}
function DraftTableRow(props: { item: DraftHeader; busy: boolean; select: (item: DraftHeader) => void }) {
  const item = props.item
  return <tr><td>{`${item.target.id}@${item.target.revision}`}</td><td>{item.editVersion}</td><td>{item.updatedAt}</td>
    <td><ZwButton variant="outline" disabled={props.busy} onPress={() => props.select(item)}>载入草稿</ZwButton></td></tr>
}
function canRecover(detail: ReplayDetail | null) {
  return !!detail && (detail.run.canResume || detail.run.state === 'RUNNING' && Date.parse(detail.run.leaseUntil ?? '') <= Date.now())
}
function ReplayHistoryRow(props: { item: ReplayHeader; busy: boolean; select: (id: string) => void }) {
  const item = props.item
  return <tr><td>{item.id}<br />{item.createdAt}</td><td>{`${item.spec.targetVersion.id}@${item.spec.targetVersion.revision}`}</td>
    <td>{`${item.state} · ${item.attempt}/3`}</td><td><ZwButton variant="outline" disabled={props.busy} onPress={() => props.select(item.id)}>查看记录</ZwButton></td></tr>
}
function EvaluationView(props: { report: Evaluation }) {
  const report = props.report
  return <div data-evaluation>
    <h3>{report.mode === 'PREVIEW' ? '预览结果' : '只读重放结果'}</h3>
    <p>{`${report.sourceInstanceId} / ${report.dataMode} / 来源批次 ${report.sourceRunStatus}`}</p>
    <p>{`原版本 ${report.originalVersion.id}@${report.originalVersion.revision} → 候选 ${report.targetVersion.id}@${report.targetVersion.revision}`}</p>
    <p>{`接受 ${report.accepted} / 拒绝 ${report.rejected} / 映射变化 ${report.changed} / 未执行写入`}</p>
    <p>{`已扫描 ${report.fetched} / 留存 ${report.retained} / 缺失 ${report.missingRaw} / 超大 ${report.oversized}`}</p>
    <Show when={report.truncated || report.missingRaw > 0 || report.oversized > 0 || report.sourceRunStatus === 'FAILED'}><p data-gap>数据不完整：仅展示本次可读取的有界样本，不能作为完整来源快照。</p></Show>
    <Show when={report.wouldFailFast}><p>当前策略会因拒绝记录中止同步。</p></Show>
    <Show when={report.rows.length === 0}><p>没有可展示的留存记录，请核对缺失计数。</p></Show>
    <div class="pipeline-table"><table><thead><tr><th>Raw 引用</th><th>旧映射</th><th>候选映射</th><th>结果</th></tr></thead><tbody>
      <For each={report.rows}>{row => <EvaluationTableRow row={forItem(row)} />}</For>
    </tbody></table></div>
  </div>
}
function EvaluationTableRow(props: { row: EvaluationRow }) {
  const row = props.row
  return <tr><td>{row.rawRef}</td><td>{row.previous ? `${row.previous.name} / ${row.previous.ip} / ${row.previous.lifecycle}` : '未接受或不可读取'}</td>
    <td>{row.candidate ? `${row.candidate.name} / ${row.candidate.ip} / ${row.candidate.lifecycle}` : '未接受或不可读取'}</td><td>{`${row.status}${row.changed ? ' · 变化' : ''}`}</td></tr>
}
