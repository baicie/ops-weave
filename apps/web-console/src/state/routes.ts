export type RouteName = 'start' | 'topology' | 'source-center' | 'workflow-runs' | 'workflows' | 'model-entities' | 'model-metrics' | 'model-relations' | 'model-relation-instances' | 'diagnose' | 'current-diagnose' | 'reorganize' | 'incidents' | 'inventory' | 'metrics' | 'pipelines' | 'source-scan-runs' | 'retention' | 'source-snapshots' | 'source-corrections'

export type Route = {
  name: RouteName
  path: string
  label: string
  group: NavGroupId
  description: string
  keywords?: string
  navigation?: false
}

export type NavGroupId = 'workbench' | 'maintenance' | 'integration' | 'model' | 'observe' | 'incident' | 'ai' | 'development'
export const NAV_GROUPS: { id: NavGroupId; label: string; expanded?: boolean }[] = [
  { id: 'workbench', label: '工作台', expanded: true },
  { id: 'integration', label: '数据接入', expanded: true },
  { id: 'model', label: '模型中心' },
  { id: 'observe', label: '资源观测' },
  { id: 'incident', label: '故障诊断' },
  { id: 'ai', label: 'AI 管理' },
  { id: 'maintenance', label: '接入维护' },
  { id: 'development', label: '开发演示' },
]

export const ROUTES: Route[] = [
  { name: 'start', path: '/start', label: '运维工作台', group: 'workbench', description: '查看授权范围内的资产、事件与常用操作', keywords: '首页 概览 帮助 指南 菜单' },
  { name: 'source-center', path: '/integrations/sources', label: '数据源中心', group: 'integration', description: '选择来源类型、配置接入并进入工作流', keywords: '数据源 Zabbix JSON 连接 手工样本' },
  { name: 'workflows', path: '/integrations/workflows', label: '数据工作流', group: 'integration', description: '在画布配置字段、处理规则与输出，预览样本结果', keywords: '画布 编排 转换 实体 指标 日志 workflow' },
  { name: 'workflow-runs', path: '/integrations/workflows/runs', label: '工作流运行记录', group: 'integration', description: '查询转换成功、失败、过滤与节点处理明细', keywords: '日志 解析 运行 历史 错误' },
  { name: 'pipelines', path: '/integrations/pipelines', label: 'Host 采集维护', group: 'maintenance', description: '维护旧版 Host 采集规则、固定版本和历史重放', keywords: '数据源 Zabbix 同步 清洗 转换 旧版', navigation: false },
  { name: 'source-scan-runs', path: '/integrations/zabbix/runs', label: '采集运行记录', group: 'integration', description: '查询采集运行与扫描结果', keywords: 'Zabbix 同步 采集' },
  { name: 'source-snapshots', path: '/integrations/cmdb', label: 'CMDB 快照导入', group: 'maintenance', description: '导入快照与核对来源对象', navigation: false },
  { name: 'source-corrections', path: '/integrations/cmdb/corrections', label: '资产绑定纠错', group: 'maintenance', description: '核对绑定、预览更正与查询历史', navigation: false },
  { name: 'model-entities', path: '/modeling/entities', label: '实体模型', group: 'model', description: '内置与自定义实体类型、字段和版本', keywords: '自定义 主机 应用 服务 数据库' },
  { name: 'model-metrics', path: '/modeling/metrics', label: '指标定义', group: 'model', description: '内置指标语义、单位与来源映射', keywords: 'CPU 内存 uptime' },
  { name: 'model-relations', path: '/modeling/relations', label: '关系模型', group: 'model', description: '关系类型、端点模型与基数', keywords: '自定义 依赖 部署' },
  { name: 'model-relation-instances', path: '/modeling/relation-instances', label: '关系实例', group: 'model', description: '在已授权资产之间维护关系实例', keywords: '关系 实例 拓扑 依赖', },
  { name: 'inventory', path: '/inventory', label: '资产', group: 'observe', description: '浏览已接入的资源与来源详情', keywords: '主机 Host 实体' },
  { name: 'topology', path: '/inventory/topology', label: '资产关系图', group: 'observe', description: '选择资产，查看已保存的直接关系与来源', keywords: '拓扑 依赖 G6' },
  { name: 'metrics', path: '/metrics', label: '指标', group: 'observe', description: '查询时序曲线与变化率', keywords: '监控 时序 CPU 内存' },
  { name: 'incidents', path: '/incidents', label: '故障事件', group: 'incident', description: '故障事件、外部告警与时间线', keywords: '告警 事件' },
  { name: 'current-diagnose', path: '/incidents/current-diagnose', label: '平台诊断', group: 'incident', description: '只读诊断与已保存结果、证据', keywords: 'AIInsight AI 分析 根因' },
  { name: 'reorganize', path: '/incidents/reorganize', label: '告警归属调整', group: 'incident', description: '预览和调整告警的事件归属', keywords: '告警 合并 拆分' },
  { name: 'retention', path: '/ai/retention', label: 'AI 数据留存', group: 'ai', description: '诊断正文的留存与清理记录' },
  { name: 'diagnose', path: '/incidents/diagnose', label: 'Fixture 诊断演示', group: 'development', description: '使用合成数据演示诊断流程', keywords: 'fixture mock 演示 测试', navigation: false },
]

export function groupFor(name: RouteName) {
  const route = ROUTES.find(item => item.name === name)!
  return NAV_GROUPS.find(group => group.id === route.group)!
}

export function searchRoutes(query: string): Route[] {
  const terms = query.trim().toLowerCase().split(/\s+/).filter(Boolean)
  return ROUTES.filter(item => item.navigation !== false).filter(item => {
    const text = [item.label, item.path, item.description, item.keywords ?? '', groupFor(item.name).label].join(' ').toLowerCase()
    return terms.every(term => text.includes(term))
  })
}
export function pathFor(name: RouteName): string {
  const route = ROUTES.find(item => item.name === name)
  return route ? `#${route.path}` : '#/start'
}

export function routeFromHash(hash: string): RouteName {
  const path = hash.replace(/^#/, '').split('?')[0] || '/start'
  const exact = ROUTES.find(item => item.path === path)
  if (exact) return exact.name
  if (path === '/' || path === '/incidents') return 'start'
  return 'start'
}
