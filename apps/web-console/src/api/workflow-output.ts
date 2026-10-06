import type { ModelField } from './model-catalog.ts'
import { parseMappingPin, sameMappingPin, type MappingDefinition, type MappingPin } from './metric-mappings.ts'

// contracts/schemas/v2/workflow-output.schema.json is the authoritative wire union.
export type EntityTarget = { id: string; revision: number; digest: string }
export type OutputTarget = EntityTarget | { kind: 'LOG' | 'METRIC'; schemaVersion: '1.0'; id?: never; revision?: never; digest?: never; mappingPin?: never; metricKey?: never } | {kind:'METRIC';schemaVersion:'1.1';metricKey:string;mappingPin:MappingPin;id?:never;revision?:never;digest?:never}
export type OutputKind = 'ENTITY' | 'LOG' | 'METRIC'
export const outputNodeNames: Record<OutputKind, string> = { ENTITY: '实体输出', LOG: '日志输出', METRIC: '指标输出' }
export function outputKind(target: OutputTarget): OutputKind { return 'kind' in target ? target.kind : 'ENTITY' }
export function outputLabel(target: OutputTarget) { return outputKind(target) === 'ENTITY' ? target.id + ' @ ' + target.revision : outputKind(target) === 'LOG' ? '日志记录 · v1' : 'mappingPin' in target&&target.mappingPin ? target.metricKey + ' · v' + target.mappingPin.revision : '指标数据 · Gauge v1' }
export function parseOutput(value: unknown): OutputTarget {
  const invalid = () => { throw new Error('工作流输出类型不符合契约') }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return invalid()
  const v = value as Record<string, unknown>
  if ('kind' in v) {
    if(v.schemaVersion==='1.1') { if(v.kind!=='METRIC'||Object.keys(v).length!==4||typeof v.metricKey!=='string'||!/^[A-Za-z][A-Za-z0-9_.:/-]{0,127}$/.test(v.metricKey))return invalid();parseMappingPin(v.mappingPin) }
    else if (Object.keys(v).length !== 2 || !['LOG', 'METRIC'].includes(String(v.kind)) || v.schemaVersion !== '1.0') return invalid()
  } else if (Object.keys(v).length !== 3 || typeof v.id !== 'string' || !/^(builtin|custom)\.[a-z][a-z0-9_]{0,47}$/.test(v.id)
    || !Number.isSafeInteger(v.revision) || Number(v.revision) < 1 || Number(v.revision) > 10000 || typeof v.digest !== 'string' || !/^sha256:[a-f0-9]{64}$/.test(v.digest)) return invalid()
  return value as OutputTarget
}
const field = (id: string, label: string, type: ModelField['type'], required = false): ModelField => ({ id, label, type, required })
export function telemetryFields(kind: 'LOG' | 'METRIC'): ModelField[] {
  return kind === 'LOG' ? [field('eventTime', '日志时间', 'DATETIME', true), field('body', '日志正文', 'TEXT', true), field('severityText', '日志级别', 'TEXT'), field('serviceName', '服务名称', 'TEXT'), field('traceId', 'Trace ID', 'TEXT'), field('spanId', 'Span ID', 'TEXT')]
    : [field('timestamp', '采样时间', 'DATETIME', true), field('metricKey', '完整指标标识', 'TEXT', true), field('value', '采样数值', 'DECIMAL', true), field('metricType', '指标类型（GAUGE）', 'ENUM', true), field('unit', '单位', 'TEXT')]
}
export function outputInputFields(target:OutputTarget):ModelField[]{return 'mappingPin' in target&&target.mappingPin?[field('timestamp','采样时间','DATETIME',true),field('sourceKey','完整来源键','TEXT',true),field('value','来源原始数值','DECIMAL',true)]:'kind' in target?telemetryFields(target.kind):[]}
export function supportsStandardMetricMapping(d:MappingDefinition){return d.metricType==='GAUGE'&&['DOUBLE','INTEGER'].includes(d.valueType)&&/^[A-Za-z][A-Za-z0-9_.:/-]{0,127}$/.test(d.metricKey)&&d.dimensionSchema.length===Object.keys(d.fixedDimensions).length}
function compareDecimal(a:string,b:string){
 const parse=(text:string)=>{const m=/^(-?)([0-9]+)(?:\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?$/.exec(text);if(!m||text.length>64||Math.abs(Number(m[4]??0))>308)throw new Error('标准指标数值超出支持范围');return {value:BigInt((m[1]??'')+m[2]+(m[3]??'')),scale:(m[3]?.length??0)-Number(m[4]??0)}};
 const x=parse(a),y=parse(b),scale=Math.max(x.scale,y.scale),left=x.value*10n**BigInt(scale-x.scale),right=y.value*10n**BigInt(scale-y.scale);return left<right?-1:left>right?1:0
}
export function parseStandardMetricRecord(value:unknown,target:OutputTarget,mapping:MappingDefinition|undefined){
 const invalid=()=>{throw new Error('标准指标结果与固定定义不一致')};
 if(!('mappingPin' in target)||!target.mappingPin||!mapping||!supportsStandardMetricMapping(mapping)||!sameMappingPin(mapping.mappingPin,target.mappingPin)||mapping.metricKey!==target.metricKey||!value||typeof value!=='object'||Array.isArray(value))return invalid();
 const v=value as Record<string,unknown>,keys=['timestamp','metricKey','value','unit','metricType','dimensions','mappingPin'];
 if(Object.keys(v).length!==keys.length||keys.some(k=>!Object.hasOwn(v,k))||v.metricKey!==mapping.metricKey||v.unit!==mapping.unit||v.metricType!=='GAUGE'||typeof v.timestamp!=='string'||v.timestamp.length>80||!Number.isFinite(Date.parse(v.timestamp))||typeof v.value!=='string'||v.value.length>64||! /^-?[0-9]+(\.[0-9]+)?$/.test(v.value)||!Number.isFinite(Number(v.value))||!sameMappingPin(parseMappingPin(v.mappingPin),target.mappingPin))return invalid();
 if(!v.dimensions||typeof v.dimensions!=='object'||Array.isArray(v.dimensions)||Object.keys(v.dimensions).length!==Object.keys(mapping.fixedDimensions).length||Object.entries(mapping.fixedDimensions).some(([k,value])=>(v.dimensions as Record<string,unknown>)[k]!==value))return invalid();
 if(mapping.minimum!==null&&compareDecimal(v.value,mapping.minimum)<0||mapping.maximum!==null&&compareDecimal(v.value,mapping.maximum)>0)return invalid();
 return v;
}
