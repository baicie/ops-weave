import { Button } from '../ui/button.tsx'
import type { SourceCredential } from '../../api/source-credentials.ts'

export function SourceCredentialTable(p: { items: SourceCredential[]; disabled: boolean; loaded: boolean; busy: boolean; failed: boolean; filtered: boolean; truncated: boolean; manage: (id: string) => void }) {
  return <>
    <div className="integration-table-wrap"><table className="integration-table" aria-label="接入凭据列表"><thead><tr><th scope="col">凭据名称</th><th scope="col">凭据标识</th><th scope="col">当前版本</th><th scope="col">状态</th><th scope="col">更新时间</th><th scope="col">操作</th></tr></thead><tbody>{p.items.map(c => <tr key={c.id}><td>{c.name}</td><td><code>{c.id}</code></td><td>v{c.revision}</td><td>{c.state === 'REVOKED' ? '已撤销' : '可管理'}</td><td>{new Date(c.updatedAt).toLocaleString()}</td><td><Button type="button" variant="outline" disabled={p.disabled} aria-label={'管理凭据：' + c.name} onClick={() => p.manage(c.id)}>管理</Button></td></tr>)}</tbody></table></div>
    {!p.items.length ? <div className="integration-list-empty"><strong>{p.busy ? '正在读取凭据…' : !p.loaded ? p.failed ? '凭据读取失败' : '尚未读取凭据' : p.filtered ? '没有匹配的凭据' : '暂无接入凭据'}</strong></div> : null}
    {p.truncated ? <p>仅显示最近20份凭据。</p> : null}
  </>
}
