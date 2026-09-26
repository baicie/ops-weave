import { createSignal, For, onCleanup, Show } from '@zeus-js/zeus'
import { usePlatformSession } from '../../state/platform-session.ts'
import { forItem } from '../../adapters/zeus-ui/for-item.ts'
import { ZwButton } from '../../adapters/zeus-ui/ZwButton.tsx'
import { sourcePresence, type PresencePage } from '../../api/source-snapshots.ts'
import type { EntityItem } from '../../api/entities.ts'
export function SourcePresence(props:{entity:EntityItem}){
  const [page,setPage]=createSignal<PresencePage|null>(null),[busy,setBusy]=createSignal(false),[error,setError]=createSignal('');let active:AbortController|undefined,seq=0
  function clear(){active?.abort();seq++;setPage(null);setBusy(false);setError('')}
  const ready=usePlatformSession(clear);onCleanup(clear)
  async function read(){clear();const controller=new AbortController();active=controller;const current=seq;setBusy(true);try{const p=await sourcePresence(props.entity,controller.signal);if(seq===current)setPage(p)}catch(e){if(seq===current)setError(e instanceof Error?e.message:'来源状态不可用')}finally{if(seq===current)setBusy(false)}}
  return <section data-source-presence><h3>第二来源确认</h3><p>只读展示导入来源的最后确认；过期、缺失或登记已撤销均不能继续作为有效确认。字段选择仍需单独审核。</p><ZwButton variant="outline" disabled={busy() || !ready()} onPress={()=>{void read()}}>读取来源状态</ZwButton><p>{error()}</p>
    <Show when={page()}><p>{`判断时间 ${page()?.evaluatedAt} · import / postgres`}</p><Show when={page()?.items.length===0}><p>尚无第二来源快照，不能推断其他来源已确认或缺失。</p></Show><ul><For each={page()?.items??[]}>{row=><li>{`${forItem(row).sourceInstanceId} / ${forItem(row).externalId} · ${forItem(row).status} · 观测 ${forItem(row).observedAt} · 到期 ${forItem(row).expiresAt}`}</li>}</For></ul></Show>
  </section>
}
