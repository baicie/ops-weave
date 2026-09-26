import { createSignal, For, onCleanup, Show } from '@zeus-js/zeus'
import { usePlatformSession } from '../../state/platform-session.ts'
import { forItem } from '../../adapters/zeus-ui/for-item.ts'
import { ZwButton } from '../../adapters/zeus-ui/ZwButton.tsx'
import { ZwInput } from '../../adapters/zeus-ui/ZwInput.tsx'
import { sourceScanRuns, sourceScanRun, ScanRunError, type ScanObjectType, type ScanRun, type ScanRunPage, type ScanRunRead } from '../../api/source-scan-runs.ts'
import { SourceConnectionCheckPanel } from './SourceConnectionCheckPanel.tsx'

export function SourceScanRunsPage(){
  const [objectType,setObjectType]=createSignal<ScanObjectType>('host')
  const [limit,setLimit]=createSignal(20)
  const [page,setPage]=createSignal<ScanRunPage|null>(null),[read,setRead]=createSignal<ScanRunRead|null>(null)
  const [id,setId]=createSignal(''),[busy,setBusy]=createSignal(false),[error,setError]=createSignal('')
  let controller:AbortController|undefined,sequence=0
  function results(){setPage(null);setRead(null)}
  function clear(){controller?.abort();sequence++;results();setId('');setBusy(false);setError('')}
  const ready=usePlatformSession(change=>{clear();setError(change.error?.message??'')});onCleanup(clear)
  async function run(work:(signal:AbortSignal,current:()=>boolean)=>Promise<void>){
    controller?.abort();const active=new AbortController();controller=active;const seq=++sequence
    setBusy(true);setError('')
    try{await work(active.signal,()=>seq===sequence)}
    catch(e){if(seq!==sequence)return;if(e instanceof ScanRunError && [400,401,403,404,503].includes(e.status))results();setError(e instanceof Error?e.message:'扫描记录读取待确认')}
    finally{if(seq===sequence)setBusy(false)}
  }
  function choose(type:ScanObjectType){if(type===objectType())return;setObjectType(type);results()}
  function size(value:number){if(value===limit())return;setLimit(value);results()}
  function load(after:string|null=null){setRead(null);void run(async(signal,current)=>{const p=await sourceScanRuns(objectType(),{limit:limit(),after},signal);if(current())setPage(p)})}
  function find(){setRead(null);void run(async(signal,current)=>{const r=await sourceScanRun(objectType(),id().trim(),signal);if(current())setRead(r)})}
  const disabled=()=>busy() || !ready()
  return <section class="panel" data-page="source-scan-runs" data-object-type={objectType()} data-limit={limit()}><h2>来源扫描</h2>
    <p>只读取平台已经持久化的来源扫描日志：状态、时间、游标、页数与映射版本。这里不会触发扫描、重试失败运行、修复元数据或对账缺失对象；失败扫描不会因为被读取而改变状态。</p>
    <p>扫描日志的 dataMode 是 scan-log，表示这是持久化日志而非数据面内容；每条运行自带当时的写入模式（例如 labeled-fixture），上游是否在线不由此推断。</p>
    <div role="group" aria-label="扫描对象类型"><ZwButton variant={objectType()==='host'?'primary':'outline'} disabled={disabled()} onPress={()=>choose('host')}>Host 扫描</ZwButton><ZwButton variant={objectType()==='item'?'primary':'outline'} disabled={disabled()} onPress={()=>choose('item')}>Item 扫描</ZwButton></div>
    <div role="group" aria-label="每页数量"><ZwButton variant={limit()===10?'primary':'outline'} disabled={disabled()} onPress={()=>size(10)}>每页 10 条</ZwButton><ZwButton variant={limit()===20?'primary':'outline'} disabled={disabled()} onPress={()=>size(20)}>每页 20 条</ZwButton><ZwButton variant={limit()===50?'primary':'outline'} disabled={disabled()} onPress={()=>size(50)}>每页 50 条</ZwButton></div>
    <ZwButton variant="outline" disabled={disabled()} onPress={()=>load()}>读取扫描运行</ZwButton>
    <p>按开始时间倒序，每页 10/20/50 条（默认 20，上限 50）；翻页只回填服务端签发的不透明游标，不要自己构造。</p>
    <Show when={page()}><section data-scan-run-list><p>{`${page()?.tenantId} · ${page()?.sourceInstanceId} · ${page()?.objectType} · ${page()?.storage} · 本页 ${page()?.items.length} 条`}</p>
      <p data-scan-run-retention>{`扫描日志保留上限：本范围 ${page()?.retention.maxRunsPerScope} 条 / 本租户 ${page()?.retention.maxRunsPerTenant} 条；当前本范围存储 ${page()?.retention.retained} 条。读取不会清理记录；超出上限的运行会在下一次扫描开始时从最旧的已结束运行删起，仍在运行或被映射版本钉住的运行不会被删除。`}</p>
      <Show when={page()!.items.length===0}><p data-scan-run-empty>本页没有存储的扫描运行；这不代表该来源从未被扫描过。</p></Show>
      <For each={page()?.items??[]}>{row=><ScanRunRow run={forItem(row)} />}</For>
      <ZwButton variant="outline" disabled={disabled() || !page()?.nextCursor} onPress={()=>load(page()?.nextCursor??null)}>下一页扫描运行</ZwButton>
    </section></Show>
    <h3>按标识读取</h3><p>未知、跨租户、跨来源或跨对象类型的运行一律按未找到处理，不泄漏存在性。</p>
    <label>扫描运行标识<ZwInput value={id()} disabled={disabled()} onValueChange={value=>{setId(value);setRead(null)}} /></label>
    <ZwButton variant="outline" disabled={disabled() || !id().trim()} onPress={find}>查询扫描运行</ZwButton>
    <p role="alert">{error()}</p>
    <Show when={read()}><section data-scan-run-read><h3>已存储的扫描运行</h3><ScanRunRow run={read()!.run} /></section></Show>
    <SourceConnectionCheckPanel />
  </section>
}

function ScanRunRow(props:{run:ScanRun}){const run=props.run;return <article data-scan-run-id={run.syncRunId}>
  <p>{`${run.objectType} · ${run.status} · ${run.startedAt} → ${run.completedAt ?? '未完成'}`}</p>
  <p>{`游标 ${run.cursor ?? '无'} · 页数 ${run.pages} · 抓取 ${run.fetched} · 采纳 ${run.accepted} · 拒绝 ${run.rejected} · 完整快照 ${run.snapshotComplete?'是':'否'} · ${run.dataMode}`}</p>
  <p>{`边界 ${run.scanConsistency==='hostid-watermark-snapshot'?'已验证 hostid 水位快照'
    :run.scanConsistency==='itemid-watermark-snapshot'?'已验证 itemid 水位快照':'offset 尝试（无快照证明）'}`}</p>
  <Show when={run.failureCode}><p data-scan-run-failure>{`${run.failureCode}：${run.failureSummary}`}</p></Show>
  <Show when={run.pipelineVersion}><p>{`映射版本 ${run.pipelineVersion?.id} 修订 ${run.pipelineVersion?.revision} · ${run.pipelineVersion?.digest}`}</p></Show>
  <p>{run.syncRunId}</p>
</article>}
