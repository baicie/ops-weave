import { PanelLeftClose, PanelLeftOpen, ShieldCheck } from 'lucide-react'

export function SidebarFooter(p: { collapsed?: boolean; onToggle?: () => void; environment: string }) {
  const label = p.collapsed ? '展开导航' : '收起导航'
  return <div className="sidebar-footer">
    {p.onToggle ? <button type="button" data-slot="button" className="sidebar-collapse" aria-label={label} title={label} aria-expanded={!p.collapsed} aria-controls="desktop-navigation" onClick={p.onToggle}>
      {p.collapsed ? <PanelLeftOpen size={16} strokeWidth={1.6}/> : <PanelLeftClose size={16} strokeWidth={1.6}/>}
      <span>{label}</span>
    </button> : null}
    <div className="sidebar-environment" title={p.environment}><ShieldCheck size={15} strokeWidth={1.6}/><span>{p.environment}</span></div>
  </div>
}
