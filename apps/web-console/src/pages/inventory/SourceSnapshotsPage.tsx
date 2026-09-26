import { createSignal, For, onCleanup, Show } from '@zeus-js/zeus'
import { usePlatformSession } from '../../state/platform-session.ts'
import { forItem } from '../../adapters/zeus-ui/for-item.ts'
import { ZwButton } from '../../adapters/zeus-ui/ZwButton.tsx'
import { ZwInput } from '../../adapters/zeus-ui/ZwInput.tsx'
import { snapshotConfig, snapshotReceipt, submitSnapshot, parseSnapshotInput, validateSnapshotSubmission, SnapshotError, type SnapshotConfig, type SnapshotReceipt } from '../../api/source-snapshots.ts'

export function SourceSnapshotsPage(){
  const [config,setConfig]=createSignal<SnapshotConfig|null>(null),[receipt,setReceipt]=createSignal<SnapshotReceipt|null>(null),[busy,setBusy]=createSignal(false),[pending,setPending]=createSignal(false),[error,setError]=createSignal('')
  const [raw,setRaw]=createSignal('[]'),[observed,setObserved]=createSignal(new Date().toISOString()),[complete,setComplete]=createSignal(false),[confirmed,setConfirmed]=createSignal(false),[id,setId]=createSignal(new URLSearchParams(location.hash.split('?')[1]).get('requestId')??'')
  let controller:AbortController|undefined,sequence=0
  function clear(){controller?.abort();sequence++;setConfig(null);setReceipt(null);setRaw('[]');setComplete(false);setConfirmed(false);setPending(false);setBusy(false);setError('')}
  const ready=usePlatformSession(change=>{clear();setError(change.error?.message??'')});onCleanup(clear)
  async function run(work:(signal:AbortSignal,current:()=>boolean)=>Promise<void>){controller?.abort();const active=new AbortController();controller=active;const seq=++sequence;setBusy(true);setError('');try{await work(active.signal,()=>seq===sequence)}catch(e){if(seq!==sequence)return;if(e instanceof SnapshotError && [400,401,403,409].includes(e.status))setPending(false);setError(e instanceof Error?e.message:'快照结果待确认')}finally{if(seq===sequence)setBusy(false)}}
  function load(){setReceipt(null);void run(async(signal,current)=>{const c=await snapshotConfig(signal);if(current())setConfig(c)})}
  function apply(){if(!config() || !confirmed() || pending())return;let input;try{input=parseSnapshotInput({requestId:crypto.randomUUID(),observedAt:observed(),complete:complete(),records:JSON.parse(raw())});validateSnapshotSubmission(input,config()!)}catch(e){setError(e instanceof Error?e.message:'JSON不正确');return}
    setId(input.requestId);setPending(true);setConfirmed(false);setReceipt(null);history.replaceState(null,'',`#/integrations/cmdb?requestId=${input.requestId}`);const c=config()!
    void run(async(signal,current)=>{const r=await submitSnapshot(input,c,signal);if(current()){setReceipt(r);setPending(false)}})
  }
  function read(){void run(async(signal,current)=>{const r=await snapshotReceipt(id(),signal);if(current()){setReceipt(r);setPending(false)}})}
  const disabled=()=>busy() || !ready()
  return <section class="panel" data-page="source-snapshots"><h2>CMDB 来源快照</h2><p>导入明确的来源记录，按人工核对并登记的资产 UUID 自动定位。字段进入待审核记录；名称或 IP 不会自动合并资产。</p>
    <p>这是人工导入适配，未连接 CMDB 厂商 API。来源确认有效期为 7 天；完整快照会把该来源未出现的已绑定记录标记为缺失，其他来源仍有效的资产会保留。</p>
    <ZwButton variant="outline" disabled={disabled() || pending()} onPress={load}>读取快照配置</ZwButton>
    <Show when={config()}><p>{`${config()?.tenantId} · ${config()?.actor} · ${config()?.sourceInstanceId} · ${config()?.namespace} · import / postgres · 最多100条，浏览器正文上限64KiB`}</p>
      <label>来源观测时间（UTC）<ZwInput value={observed()} disabled={disabled() || pending()} onValueChange={v=>{setObserved(v);setConfirmed(false)}} /></label>
      <label>来源记录 JSON<textarea rows={8} prop:value={raw()} disabled={disabled() || pending()} onInput={e=>{setRaw((e.target as HTMLTextAreaElement).value);setConfirmed(false)}} /></label>
      <p>每条包含 externalId、assetUuid、values；values 只允许 name、ip、owner、environment。未登记或冲突的标识会使整批失败。</p>
      <label><input type="checkbox" checked={complete()} disabled={disabled() || pending()} onChange={e=>{setComplete((e.target as HTMLInputElement).checked);setConfirmed(false)}} />我确认这是该来源的完整快照；允许对未出现的记录标记缺失</label>
      <label><input type="checkbox" checked={confirmed()} disabled={disabled() || pending()} onChange={e=>setConfirmed((e.target as HTMLInputElement).checked)} />已核对标识、观测时间与完整性，确认导入本批</label>
      <ZwButton variant="primary" disabled={disabled() || pending() || !confirmed()} onPress={apply}>导入来源快照</ZwButton>
    </Show>
    <Show when={pending()}><p>提交结果待确认。请查询原请求回执；不会自动重试或提交下一批。</p></Show>
    <label>快照请求标识<ZwInput value={id()} disabled={busy()} onValueChange={setId} /></label><ZwButton variant="outline" disabled={disabled() || !id()} onPress={read}>查询快照回执</ZwButton>
    <p role="alert">{error()}</p><Show when={receipt()}><section data-snapshot-receipt><h3>快照已保存</h3><p>{`${receipt()?.tenantId} · ${receipt()?.actor} · ${receipt()?.ingestedAt} · 来源标记缺失 ${receipt()?.markedAbsent} 条`}</p>
      <p>字段值尚未自动采用。请进入资产详情读取补充来源并审核；已有生效字段需先撤销，再导入新快照取得当前版本的待审记录。</p>
      <ul><For each={receipt()?.resolved??[]}>{row=><li>{`${forItem(row).externalId} → ${forItem(row).entityId} · 待审 ${forItem(row).reviewId}`}</li>}</For></ul></section></Show>
  </section>
}
