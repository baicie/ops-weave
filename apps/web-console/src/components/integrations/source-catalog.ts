import { CloudDownload, Database, FileJson } from 'lucide-react'
import type { SourceType } from '../../api/source-setups.ts'

export const sourceTitles: Record<SourceType, string> = {
  ZABBIX_HOST: 'Zabbix', MANUAL_SAMPLE: 'JSON 手工样本', CMDB_SNAPSHOT: 'CMDB 快照',
}
export const sourceCatalog = [
  { id: 'ZABBIX_HOST' as const, category: '监控平台', description: '主机清单与已有采集批次', icon: Database },
  { id: 'MANUAL_SAMPLE' as const, category: '样本输入', description: '验证实体、指标和日志转换', icon: FileJson },
  { id: 'CMDB_SNAPSHOT' as const, category: '资产导入', description: '导入资源快照与核对来源', icon: CloudDownload },
]
