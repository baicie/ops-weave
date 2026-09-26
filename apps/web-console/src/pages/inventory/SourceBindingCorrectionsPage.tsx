import { createSignal, For, Show, onCleanup } from '@zeus-js/zeus'
import { usePlatformSession } from '../../state/platform-session.ts'
import { ZwButton } from '../../adapters/zeus-ui/ZwButton.tsx'
import { ZwInput } from '../../adapters/zeus-ui/ZwInput.tsx'
import { forItem } from '../../adapters/zeus-ui/for-item.ts'
import { prepareCorrection, submitCorrection, readCorrection, correctionHistory, CorrectionError, type CorrectionPreview, type CorrectionReceipt, type CorrectionPage } from '../../api/source-binding-corrections.ts'
import { validateSnapshotSubmission } from '../../api/source-snapshots.ts'
import { correctionInput } from '../../api/source-binding-corrections.ts'

export function SourceBindingCorrectionsPage(){
  const [previous,setPrevious]=createSignal(''),[external,setExternal]=createSignal(''),[target,setTarget]=createSignal(''),[observed,setObserved]=createSignal(new Date().toISOString()),[raw,setRaw]=createSignal('{}'),[reason,setReason]=createSignal('')
  const [preview,setPreview]=createSignal<CorrectionPreview|null>(null),[receipt,setReceipt]=createSignal<CorrectionReceipt|null>(null),[page,setPage]=createSignal<CorrectionPage|null>(null),[confirmed,setConfirmed]=createSignal(false),[pending,setPending]=createSignal(false),[busy,setBusy]=createSignal(false),[error,setError]=createSignal('')
  const [id,setId]=createSignal(new URLSearchParams(location.hash.split('?')[1]).get('requestId')??'');let controller:AbortController|undefined,sequence=0
  function invalidate(){setPreview(null);setConfirmed(false);setReceipt(null);setPage(null)}
  function clear(){controller?.abort();sequence++;invalidate();setPrevious('');setExternal('');setTarget('');setObserved(new Date().toISOString());setRaw('{}');setReason('');setPending(false);setBusy(false);setError('')}
  const ready=usePlatformSession(change=>{clear();setError(change.error?.message??'')});onCleanup(clear)
  async function run(work:(signal:AbortSignal,current:()=>boolean)=>Promise<void>){controller?.abort();controller=new AbortController();const active=controller,seq=++sequence;setBusy(true);setError('');try{await work(active.signal,()=>seq===sequence)}catch(e){if(seq!==sequence)return;if(e instanceof CorrectionError && [400,401,403,409].includes(e.status)){setPending(false);setPreview(null);setConfirmed(false)}setError(e instanceof Error?e.message:'更正结果待确认')}finally{if(seq===sequence)setBusy(false)}}
  const disabled=()=>busy() || pending() || !ready()
  function prepare(){invalidate();void run(async(signal,current)=>{const p=await prepareCorrection(previous().trim(),external().trim(),target().trim(),observed(),JSON.parse(raw()),reason(),signal);if(current())setPreview(p)})}
  function apply(){const p=preview();if(!p || !confirmed() || pending())return;try{validateSnapshotSubmission(correctionInput(p.command),p.config)}catch(e){setError(e instanceof Error?e.message:'请重新预览');return}setPending(true);setId(p.command.requestId);setConfirmed(false);setReceipt(null);history.replaceState(null,'',`#/integrations/cmdb/corrections?requestId=${p.command.requestId}`);void run(async(signal,current)=>{const r=await submitCorrection(p,signal);if(current()){setReceipt(r);setPending(false);setPreview(null)}})}
  function read(){setReceipt(null);void run(async(signal,current)=>{const r=await readCorrection(id(),signal);if(current()){setReceipt(r);setPending(false);setPreview(null)}})}
  function historyPage(after:string|null=null){void run(async(signal,current)=>{const p=await correctionHistory(previous().trim(),after,signal);if(current())setPage(p)})}
  function edit(set:(v:string)=>void,value:string){set(value);invalidate()}
  return <section class="panel" data-page="source-binding-corrections"><h2>来源绑定更正</h2>
    <p>人工核对错误绑定后，使用目标资产已登记的 UUID 和一份新的来源观测更正当前绑定。原观测、指标、Incident、字段审核和旧回执保留原资产归属；新字段仍需审核。</p>
    <p>这是固定 CMDB 人工导入入口。若原绑定存在生效字段，请先在原资产详情撤销；不会自动搬移或批准字段。</p>
    <label>原资产 ID<ZwInput value={previous()} disabled={disabled()} onValueChange={v=>edit(setPrevious,v)} /></label>
    <label>待更正来源外部 ID<ZwInput value={external()} disabled={disabled()} onValueChange={v=>edit(setExternal,v)} /></label>
    <label>目标已登记资产 UUID<ZwInput value={target()} disabled={disabled()} onValueChange={v=>edit(setTarget,v)} /></label>
    <label>更正来源观测时间（UTC）<ZwInput value={observed()} disabled={disabled()} onValueChange={v=>edit(setObserved,v)} /></label>
    <label>更正来源字段 JSON<textarea rows={5} prop:value={raw()} disabled={disabled()} onInput={e=>edit(setRaw,(e.target as HTMLTextAreaElement).value)} /></label>
    <p>字段只允许 name、ip、owner、environment，至少一项。观测时间须晚于该来源已保存快照；不会对账其他对象。</p>
    <label>绑定更正原因<ZwInput value={reason()} disabled={disabled()} onValueChange={v=>edit(setReason,v)} /></label>
    <ZwButton variant="outline" disabled={disabled() || !previous() || !target() || !external() || !reason().trim()} onPress={prepare}>读取更正预览</ZwButton>
    <Show when={preview()}><section data-correction-preview><h3>核对原绑定与目标</h3><p>{`${preview()?.config.tenantId} · ${preview()?.config.actor} · ${preview()?.config.sourceInstanceId} · ${preview()?.config.namespace}`}</p>
      <p>{`原资产 ${preview()?.previous.name} · ${preview()?.previous.id} · 版本 ${preview()?.previous.version}`}</p><p>{`原确认 ${preview()?.presence.status} · ${preview()?.presence.identity.value} · ${preview()?.presence.observedAt}`}</p>
      <p>{`目标资产 ${preview()?.target.name} · ${preview()?.target.id} · 版本 ${preview()?.target.version} · UUID ${preview()?.command.targetIdentity.value}`}</p><p>{`新观测 ${preview()?.command.observedAt} · ${JSON.stringify(preview()?.command.values)} · 原因 ${preview()?.command.reason}`}</p>
      <label><input type="checkbox" checked={confirmed()} disabled={disabled()} onChange={e=>setConfirmed((e.target as HTMLInputElement).checked)} />已核对新旧资产，确认更正当前绑定并保留历史归属</label><ZwButton variant="primary" disabled={disabled() || !confirmed()} onPress={apply}>确认绑定更正</ZwButton>
    </section></Show>
    <Show when={pending()}><p data-correction-pending>更正结果待确认。只能查询原请求回执，不会自动重试或提交其他更正。</p></Show>
    <label>更正请求标识<ZwInput value={id()} disabled={busy() || pending()} onValueChange={v=>{setId(v);setReceipt(null)}} /></label><ZwButton variant="outline" disabled={busy() || !ready() || !id()} onPress={read}>查询更正回执</ZwButton>
    <p role="alert">{error()}</p><Show when={receipt()}><section data-correction-receipt><h3>绑定更正已保存</h3><ReceiptContent receipt={receipt()!} /></section></Show>
    <h3>资产更正历史</h3><p>按上方原资产 ID 查询参与过的更正，显示当时操作者与新旧归属。每页10条，读取需要当前整源管理权限。</p>
    <ZwButton variant="outline" disabled={disabled() || !previous()} onPress={()=>historyPage()}>读取更正历史</ZwButton><ZwButton variant="outline" disabled={disabled() || !page()?.nextCursor} onPress={()=>historyPage(page()?.nextCursor??null)}>下一页更正历史</ZwButton>
    <Show when={page()}><section data-correction-history><p>{`本页 ${page()?.items.length} 条；历史记录不会按当前绑定改写。`}</p><For each={page()?.items??[]}>{row=><article><ReceiptContent receipt={forItem(row)} /></article>}</For></section></Show>
  </section>
}
function ReceiptContent(props:{receipt:CorrectionReceipt}){const r=props.receipt;return <div><p>{`${r.command.externalId} · ${r.command.previousEntityId} → ${r.command.targetEntityId}`}</p><p>{`${r.actor} · ${r.snapshot.ingestedAt} · ${r.command.reason}`}</p><p>{`原资产版本 ${r.previousEntityVersionAfter} · 目标版本 ${r.snapshot.resolved[0]?.entityVersion} · 待审 ${r.snapshot.resolved[0]?.reviewId}`}</p><p>{`请求 ${r.command.requestId}；旧快照 ${r.previous.snapshotId} 及其历史归属保留。`}</p></div>}
