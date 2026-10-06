import { ArrowRight, Check } from 'lucide-react'

export function SourceSetupGuide(props: { host: boolean; fixture: boolean; configure: () => void }) {
  const steps = props.host ? [
    { title: '核对来源连接', body: '在接入配置中核对平台已配置的实例和 API 地址，按需测试连接。连接测试仅验证连通性，不代表完整采集兼容验收。' },
    { title: '保存来源，进入画布', body: '填写接入名称，保存后打开本地处理流程。在字段映射节点将 name、ip 等来源字段对应到主机模型字段，输出节点固定模型版本。' },
    { title: '使用已有批次预览', body: '保存工作流草稿，在测试区填写已有 Host 采集批次 ID；最多读取5条，缺失原始数据和失败状态会明确显示。' },
    { title: '核对结果，再发布版本', body: '检查各节点输入、输出及字段错误，通过预览后显式发布。实体写入和批次任务在发布后的运行管理中另行确认，保存接入不会启动采集。' },
  ] : [
    { title: '命名这份接入', body: '填写接入名称和用途说明。来源仅保存配置回执，JSON 样本不会随接入配置保存。' },
    { title: '配置处理和输出', body: '进入画布后，在输出节点选择实体、指标或日志；实体固定已发布模型版本，指标和日志使用各自的格式。再配置字段映射与清洗规则。' },
    { title: '填写样本，检查每个节点', body: '保存草稿，在测试区填写1–5条 JSON 对象，每条最多32个标量字段。预览后核对必填字段、时间、数值和各节点输出。' },
    { title: '发布固定版本', body: '通过预览后显式发布。日志和指标当前用于预览与版本测试；持续采集和存储写入尚未实现。实体运行需另行配置并明确执行。' },
  ]
  return <section className="source-guide" aria-label="接入教程">
    <header><h4>{props.host ? 'Zabbix 主机接入教程' : 'JSON 样本接入教程'}</h4><p>从来源配置到处理结果，按步骤完成一次可核对的接入。</p></header>
    <div className="source-guide-overview"><span><Check size={14} />保存来源</span><ArrowRight size={14} /><span>配置流程</span><ArrowRight size={14} /><span>样本预览</span><ArrowRight size={14} /><span>发布版本</span></div>
    <ol className="source-guide-steps">{steps.map((step, index) => <li key={step.title}><span>{String(index + 1).padStart(2, '0')}</span><div><h5>{step.title}</h5><p>{step.body}</p></div></li>)}</ol>
    {props.host && props.fixture ? <p className="source-capability-note">当前来源为 Fixture 合成数据。连接测试属于本地自检，不能证明真实 Zabbix 已连接。</p> : null}
    <details className="source-guide-faq"><summary>常见问题</summary><dl><dt>{props.host ? '想接入监控指标？' : '找不到实体输出模型？'}</dt><dd>{props.host ? '本接入处理已有 Host 批次。监控指标目录显示版本化映射定义；指标采样走现有独立链路，不由保存这份主机接入启用。' : '实体需要当前身份有权读取的已发布模型。日志和指标使用独立格式，不要求实体模型。'}</dd><dt>保存超时或提示结果待确认？</dt><dd>保留原配置，先查询确认结果，必要时按原配置重试；不要重新创建同一份接入。</dd><dt>预览没有通过？</dt><dd>查看失败节点和字段错误，修正后保存并重新预览；修改字段或样本后，旧预览不能用于发布。</dd></dl></details>
    <button type="button" className="source-guide-configure" onClick={props.configure}>返回接入配置 <ArrowRight size={14} /></button>
  </section>
}
