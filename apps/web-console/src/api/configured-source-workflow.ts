import { readConnectionHistory } from './source-connections.ts'
import { readInstance } from './source-instances.ts'
import { template, type Workspace } from './workflows.ts'
import type { WorkflowSelection } from '../state/workflow-selection.ts'

/** Prepare an unsaved first workflow from the explicitly selected immutable configuration. */
export async function configuredSourceWorkflow(workspace: Workspace, selection: Extract<WorkflowSelection, { sourceInstance: string }>, signal: AbortSignal) {
  const instance = await readInstance(selection.sourceInstance, signal)
  if (instance.state !== 'ACTIVE' || instance.source.kind !== 'ZABBIX_HOST' || instance.dataMode !== 'zabbix-jsonrpc' || instance.source.instanceId !== 'connection-' + instance.id) throw new Error('当前接入不支持从此配置创建工作流，请重新选择接入实例。')
  const configurations = await readConnectionHistory(instance.id, signal)
  const configuration = configurations.find(c => c.revision === selection.configurationRevision && c.connectionDigest === selection.connectionDigest)
  if (!configuration || configuration.revision > instance.configurationRevision || Date.parse(configuration.createdAt) < Date.parse(instance.createdAt) || Date.parse(configuration.createdAt) > Date.parse(instance.updatedAt)) throw new Error('找不到所选的固定连接配置，请返回接入列表重新选择。')
  if (!workspace.operatorCatalog) throw new Error('工作区没有可核对的算子目录，无法创建工作流。')
  const model = workspace.models.find(m => m.definition.id === 'builtin.host')
  if (!model) throw new Error('当前身份下没有可用的主机模型，无法创建工作流。')
  if ([...workspace.drafts.items, ...workspace.published.items].some(e => e.definition.id === instance.workflowId)) throw new Error('此接入已有工作流，请从接入列表查看工作流版本，创建下一版后选择所需连接配置。')
  const definition = template(model, { ...instance.source, configuration: { sourceId: instance.id, revision: configuration.revision, digest: configuration.connectionDigest } }, workspace.operatorCatalog)
  return { ...definition, id: instance.workflowId, name: instance.name }
}
