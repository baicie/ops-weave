import { useRef } from 'react'
import { PanelsTopLeft, X, Check } from 'lucide-react'
import { setConsoleLayout, useConsoleLayout } from '../../state/layout-preference.ts'

export function LayoutSettings() {
  const layout = useConsoleLayout()
  const dialog = useRef<HTMLDialogElement>(null)
  return <>
    <button type="button" className="icon-button layout-trigger" aria-label="布局设置" title="布局设置" onClick={() => dialog.current?.showModal()}><PanelsTopLeft size={16} /></button>
    <dialog className="layout-settings" ref={dialog} aria-labelledby="layout-settings-title" onClick={event => { if (event.target === dialog.current) dialog.current?.close() }}>
      <div className="layout-settings-content">
        <div className="layout-settings-heading"><h2 id="layout-settings-title">布局设置</h2><button className="icon-button" type="button" aria-label="关闭布局设置" onClick={() => dialog.current?.close()}><X size={16} /></button></div>
        <p>选择适合你的工作方式，两种布局均支持浅色和深色主题。</p>
        <fieldset><legend>页面布局</legend>
          {(['standard', 'tabs'] as const).map(value => <label key={value} className="layout-choice" data-selected={layout === value}>
            <input type="radio" name="console-layout" value={value} checked={layout === value} onChange={() => setConsoleLayout(value)} />
            <span className={'layout-miniature ' + value} aria-hidden="true"><i /><span><b />{value === 'tabs' ? <em /> : null}<strong /></span></span>
            <span><strong>{value === 'standard' ? '标准布局' : '多页签布局'}</strong><small>{value === 'standard' ? '聚焦当前页面' : '多个页面同时打开，切换时保留编辑'}</small></span>
            {layout === value ? <Check size={16} aria-hidden="true" /> : null}
          </label>)}
        </fieldset>
        <button type="button" data-slot="button" className="layout-done" onClick={() => dialog.current?.close()}>完成</button>
      </div>
    </dialog>
  </>
}
