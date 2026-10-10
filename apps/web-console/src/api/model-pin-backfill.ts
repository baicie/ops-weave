import { platformClient } from './http.ts'

// Wire shapes and bounds: contracts/schemas/v1/entity-model-pin-backfill-*.schema.json.
export type EntityModelPinBackfillCommand = {
  requestId: string
  entityType: string
  model: { id: string; revision: number }
  maxUpdates: number
}
export type EntityModelPinBackfillCounts = {
  scanned: number
  updated: number
  keptWithoutPin: number
  skippedPinned: number
  skippedSource: number
}
export type EntityModelPinBackfillReceipt = {
  schemaVersion: '1.0'
  storage: string
  replayed: boolean
  tenantId: string
  requestId: string
  request: { entityType: string; model: { id: string; revision: number; digest: string } }
  counts: EntityModelPinBackfillCounts
  completedAt: string
}

/** Unknown outcome: the original command must be retried byte-identically to read the original receipt. */
export class BackfillUnknownError extends Error {}
export class BackfillRequestError extends Error {
  constructor(readonly status: number, message: string) { super(message) }
}

const uuidPattern = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i
function checkCommand(command: EntityModelPinBackfillCommand): void {
  if (!uuidPattern.test(command.requestId) || typeof command.entityType !== 'string' || !/^[A-Za-z][A-Za-z0-9_.-]{0,63}$/.test(command.entityType)
    || !command.model || !/^(builtin|custom)\.[a-z][a-z0-9_]{0,47}$/.test(command.model.id)
    || !Number.isSafeInteger(command.model.revision) || command.model.revision < 1 || command.model.revision > 10000
    || !Number.isSafeInteger(command.maxUpdates) || command.maxUpdates < 1 || command.maxUpdates > 500) {
    throw new Error('回填命令不符合契约')
  }
}
function receipt(value: unknown, command: EntityModelPinBackfillCommand): EntityModelPinBackfillReceipt {
  const isRecord = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v)
  const bounded = (v: unknown): number => Number.isSafeInteger(v) && (v as number) >= 0 && (v as number) <= 500 ? v as number : NaN
  if (!isRecord(value) || value.schemaVersion !== '1.0' || typeof value.storage !== 'string' || !value.storage
    || typeof value.replayed !== 'boolean' || typeof value.tenantId !== 'string' || !value.tenantId
    || typeof value.requestId !== 'string' || value.requestId.toLowerCase() !== command.requestId.toLowerCase()
    || typeof value.completedAt !== 'string' || !Number.isFinite(Date.parse(value.completedAt))) throw new Error('receipt')
  const request = value.request
  if (!isRecord(request) || request.entityType !== command.entityType || !isRecord(request.model)
    || request.model.id !== command.model.id || request.model.revision !== command.model.revision
    || typeof request.model.digest !== 'string' || !/^sha256:[a-f0-9]{64}$/.test(request.model.digest)) throw new Error('receipt')
  const counts = value.counts
  if (!isRecord(counts)) throw new Error('receipt')
  const parsed: EntityModelPinBackfillCounts = {
    scanned: bounded(counts.scanned), updated: bounded(counts.updated), keptWithoutPin: bounded(counts.keptWithoutPin),
    skippedPinned: Number.isSafeInteger(counts.skippedPinned) && (counts.skippedPinned as number) >= 0 ? counts.skippedPinned as number : NaN,
    skippedSource: Number.isSafeInteger(counts.skippedSource) && (counts.skippedSource as number) >= 0 ? counts.skippedSource as number : NaN,
  }
  if (Object.values(parsed).some(v => !Number.isSafeInteger(v)) || parsed.updated + parsed.keptWithoutPin !== parsed.scanned) throw new Error('receipt')
  return value as unknown as EntityModelPinBackfillReceipt
}

export async function backfillEntityModelPins(command: EntityModelPinBackfillCommand, signal: AbortSignal): Promise<EntityModelPinBackfillReceipt> {
  checkCommand(command)
  const body = {
    requestId: command.requestId,
    entityType: command.entityType,
    model: { id: command.model.id, revision: command.model.revision },
    maxUpdates: command.maxUpdates,
  }
  let value: unknown
  try {
    value = await platformClient.request('/api/v1/entities/model-pins/backfill', {
      method: 'POST', body, signal,
      error: status => new BackfillRequestError(status, status === 403
        ? '当前身份无权执行版本回填（需要租户级 entity.manage 授权）。'
        : status === 404 ? '指定的模型版本不可用。'
          : status === 409 ? '同一请求编号已用于不同内容；请读取原回执或更换请求编号。'
            : status >= 500 ? '服务端未确认回填结果；请使用同一请求原样重试以核对原回执。'
              : `版本回填失败（HTTP ${status}）。`),
    })
  } catch (cause) {
    if (cause instanceof BackfillRequestError && cause.status < 500) throw cause
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause
    throw new BackfillUnknownError('版本回填结果待确认。保留原请求编号与内容后可原样重试。')
  }
  try {
    return receipt(value, command)
  } catch {
    throw new BackfillUnknownError('服务端已响应，但回填回执无法确认；请使用原请求原样重试核对。')
  }
}

export async function readBackfillReceipt(command: EntityModelPinBackfillCommand, signal: AbortSignal): Promise<EntityModelPinBackfillReceipt | null> {
  if (!uuidPattern.test(command.requestId)) throw new Error('回填命令不符合契约')
  try {
    return receipt(await platformClient.request('/api/v1/entities/model-pins/backfill/' + encodeURIComponent(command.requestId), {
      signal, error: status => new BackfillRequestError(status, `回填回执读取失败（HTTP ${status}）。`),
    }), command)
  } catch (cause) {
    if (cause instanceof BackfillRequestError && cause.status === 404) return null
    throw cause
  }
}
