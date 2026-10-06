import { useState } from 'react'
import { AppFooter, AppHeader, AppSidebar } from './layout.tsx'
import { useHashRoute } from '../state/hash-route.ts'
import { useSessionLifecycle } from '../state/platform-session.ts'
import { PageWorkspace } from './PageWorkspace.tsx'

export function App() {
  const route = useHashRoute()
  useSessionLifecycle()
  const [collapsed, setCollapsed] = useState(false)
  return (
    <div className={collapsed ? 'app-shell sidebar-collapsed' : 'app-shell'}>
      <button className="skip-link" type="button" onClick={() => document.getElementById('workspace')?.focus()}>跳到主要内容</button>
      <AppSidebar route={route} collapsed={collapsed} onToggle={() => setCollapsed(value => !value)} />
      <div className="workspace">
      <AppHeader route={route} collapsed={collapsed} toggleSidebar={() => setCollapsed(value => !value)} />
      <PageWorkspace route={route} />
      <AppFooter />
      </div>
    </div>
  )
}
