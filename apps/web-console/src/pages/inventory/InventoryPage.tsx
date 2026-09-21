import { ModulePlaceholder } from '../../components/ModulePlaceholder.tsx'

export function InventoryPage() {
  return (
    <ModulePlaceholder
      title="资产列表"
      capability="服务端分页、筛选与表格尚未接入。Java inventory API 未实现；本页不假装已连接 CMDB/Zabbix。"
      next="下一步用 OpsWeave 查询适配层驱动表格。Zeus UI data-grid 需单独验收后再引入，不能把租户范围交给组件库。"
    />
  )
}
