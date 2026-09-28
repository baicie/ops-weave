import { pathFor, ROUTES, type RouteName } from '../state/routes.ts'
type Guide = { purpose: string; steps: string[]; next: RouteName[] }
const guides: Record<RouteName, Guide> = {
  start: { purpose: '从任务出发，找到对应页面。', steps: [], next: [] },
  'source-center': { purpose: '新接入从这里开始。选择类型、确认配置，再进入清洗画布。', steps: ['建立平台会话后，选择 Zabbix 或手工 JSON。', '在抽屉选择目标实体模型，确认后生成工作流草稿。', '已有配置可直接继续编排；确认配置不会自动采集。'], next: ['workflows', 'model-entities'] },
  workflows: { purpose: '把来源字段映射、清洗成目标模型，使用样本逐步验证。', steps: ['读取已有草稿，或从数据源中心创建。', '点击节点配置字段；拖动只调整布局，上移/下移调整执行顺序。', '填写样本，保存 → 预览 → 发布。逐条结果到运行记录查看。'], next: ['source-center', 'workflow-runs'] },
  'workflow-runs': { purpose: '查看哪条数据成功、失败或被过滤，定位具体节点和字段。', steps: ['读取最近运行，按结果筛选。', '点击查看明细，展开失败记录。', '修正规则后回到工作流显式重新测试；历史记录保留。'], next: ['workflows'] },
  'model-entities': { purpose: '定义资产的结构：有哪些类型、字段和校验规则。', steps: ['读取模型目录，查看内置类型。', '复制内置类型或新建自定义类型，配置字段并保存草稿。', '预览字段清洗、发布固定版本，再到数据源中心选择该模型。'], next: ['source-center', 'inventory'] },
  'model-metrics': { purpose: '查看指标的含义、单位和来源转换规则。', steps: ['读取模型目录查看内置指标。', '核对来源键和单位转换。', '查看实时采样曲线请进入指标页面。'], next: ['metrics'] },
  'model-relations': { purpose: '定义哪些类型可以相连；关系类型本身不会创建资产连线。', steps: ['读取目录，在关系示意图查看已定义的类型连接。', '打开关系卡片，查看端点和基数，或新建自定义关系类型。', '实际资产之间已保存的关系在资产关系图查看。'], next: ['topology', 'model-entities'] },
  inventory: { purpose: '查询已经接入的平台资产，查看来源、字段和历史。', steps: ['读取资产，可按名称、IP 和生命周期筛选。', '打开资产详情查看来源信息。', '查看资产之间的直接关联，进入资产关系图。'], next: ['topology', 'metrics', 'source-center'] },
  topology: { purpose: '只展示已保存且有权查看的资产直接关系，不按名称猜测依赖。', steps: ['搜索并读取资产，选择一个起点。', '点击图中节点查看来源；下方列表也能选择。', '空图表示未找到可展示关系；截断提示表示图不完整。'], next: ['model-relations', 'inventory'] },
  metrics: { purpose: '查看已绑定资产指标的历史曲线和覆盖情况。', steps: ['选择资产、指标和查询时间范围。', '读取指标，核对来源和数据缺口。', '计数器需要时查看变化率；空数据不代表系统正常。'], next: ['inventory', 'model-metrics'] },
  incidents: { purpose: '查看故障事件、关联资产、外部告警和事件时间线。', steps: ['读取故障列表并选择一项事件。', '核对告警状态、关联资产和时间线。', '需要解释时进入平台诊断，错误归属到告警归属调整处理。'], next: ['current-diagnose', 'reorganize'] },
  'current-diagnose': { purpose: '围绕故障执行受控只读诊断，回查结果和证据。', steps: ['选择故障和时间范围，填写诊断问题。', '显式运行诊断，或读取已保存结果。', '点击证据核对来源、有效期和缺失数据；模型解释需人工判断。'], next: ['incidents', 'agent'] },
  reorganize: { purpose: '修正告警属于哪个故障事件，操作会修改平台归属。', steps: ['读取原事件与目标事件并核对权限。', '先预览合并或拆分影响。', '确认具体对象和版本后提交，再查询归属历史。'], next: ['incidents'] },
  pipelines: { purpose: '维护原有 Zabbix Host 采集链，和新版清洗画布是不同入口。', steps: ['读取来源连接和已有 Host 流水线版本。', '需要维护旧规则时选择固定版本、预览或只读重放。', '新建自定义实体转换，请使用数据源中心和数据工作流。'], next: ['source-center', 'workflows', 'source-scan-runs'] },
  'source-scan-runs': { purpose: '查看 Zabbix 实际采集批次；工作流转换结果在另一页。', steps: ['读取来源扫描记录。', '核对批次成功、失败、页数和完整性。', '工作流使用已有 Host 批次时，复制批次 ID 到输入配置。'], next: ['workflows', 'workflow-runs'] },
  'source-snapshots': { purpose: '导入 CMDB 快照并核对来源对象。', steps: ['准备符合契约的完整快照并核对来源。', '按页面先预览，再明确确认导入。', '如来源对象绑定错资产，使用资产绑定纠错。'], next: ['source-corrections', 'inventory'] },
  'source-corrections': { purpose: '更正来源对象到平台资产的绑定，避免误关联。', steps: ['读取现有绑定及目标资产。', '预览更正影响并核对版本。', '确认后提交；历史记录可回查，不会静默批量纠错。'], next: ['source-snapshots', 'inventory'] },
  skills: { purpose: '管理诊断技能的配置、草稿和固定发布版本。', steps: ['读取技能目录并打开一个技能。', '修改私有草稿并测试配置。', '发布新版本供后续诊断使用；旧版本不会被覆盖。'], next: ['current-diagnose', 'agent'] },
  agent: { purpose: '查看 AI 执行历史、状态和费用。', steps: ['读取执行记录。', '打开运行详情核对模型、步骤和用量。', '业务诊断结果与证据在平台诊断页面回查。'], next: ['current-diagnose', 'retention'] },
  retention: { purpose: '按既定策略查看、预览和清理 AI 正文留存。', steps: ['读取留存策略和可清理对象。', '预览影响范围后再确认清理。', '查看清理回执；这不是工作流运行记录管理。'], next: ['agent'] },
  diagnose: { purpose: '使用明确标记为 Fixture 的合成数据演示诊断。', steps: ['填写演示环境凭据与问题。', '运行只读演示，核对 Fixture / Mock 标记。', '实际平台故障分析请使用平台诊断。'], next: ['current-diagnose'] },
}
export function PageGuide(props: { route: RouteName }) {
  const guide = guides[props.route]
  return (
    <details className="page-guide">
      <summary>
        <span className="guide-mark">?</span>
        <span>{guide.purpose}</span>
        <b>使用说明</b>
      </summary>
      <div className="page-guide-body">
        <ol>
          {guide.steps.map(step => <li key={step}>{step}</li>)}
        </ol>
        <div>
          {guide.next.map(item => (
            <a key={item} href={pathFor(item)}>{ROUTES.find(r => r.name === item)?.label + ' →'}</a>
          ))}
        </div>
      </div>
    </details>
  )
}
