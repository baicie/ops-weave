import { AppNav } from './navigation.tsx'
import type { RouteName } from '../state/routes.ts'

export function AppHeader(props: { route: () => RouteName }) {
  return (
    <header class="app-header">
      <span class="eyebrow">OPSWEAVE / 观织 / V4</span>
      <h1>可观测与智能运维平台</h1>
      <p>Java 平台 · Rust Agent Runtime · Zeus 控制台</p>
      <AppNav route={props.route} />
    </header>
  )
}

export function AppFooter() {
  return (
    <footer>
      模板仓库，不是生产版本。菜单隐藏不等于授权完成。证据引用校验不代表根因已经被证明。
    </footer>
  )
}
