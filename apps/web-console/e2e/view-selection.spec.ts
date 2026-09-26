import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { inventoryDefault, inventoryHash, inventorySelection, incidentDefault, incidentHash, incidentSelection,
  metricsDefault, metricsHash, metricsSelection } from '../src/state/view-selection.ts'

const id = '11111111-1111-1111-1111-111111111111'
test('selection codecs round-trip supported read state without a credential or identity field', () => {
  const sample = (name: string) => JSON.parse(readFileSync(new URL(`../../../contracts/examples/${name}-view-selection.json`, import.meta.url), 'utf8'))
  expect(inventorySelection(inventoryHash(sample('inventory')))).toEqual(sample('inventory'))
  expect(incidentSelection(incidentHash(sample('incident')))).toEqual(sample('incident'))
  expect(metricsSelection(metricsHash(sample('metrics')))).toEqual(sample('metrics'))
  const inventory = { ...inventoryDefault(), q: '主机 + 10.0.0.1 / % ? #', type: '', lifecycle: 'INACTIVE', after: id, entityId: id }
  expect(inventorySelection(inventoryHash(inventory))).toEqual(inventory)
  const incident = { ...incidentDefault(), status: 'INVESTIGATING', after: id, incidentId: id }
  expect(incidentSelection(incidentHash(incident))).toEqual(incident)
  const metrics = { ...metricsDefault(), q: 'db host', entityId: id, metricKey: 'host.cpu/%total:counter-1', range: '15m' as const, from: 1, till: 901 }
  expect(metricsSelection(metricsHash(metrics))).toEqual(metrics)
  expect(inventoryHash(inventoryDefault())).toBe('#/inventory'); expect(incidentHash(incidentDefault())).toBe('#/incidents'); expect(metricsHash(metricsDefault())).toBe('#/metrics')
})
test('all read routes reject unknown identity commands duplicate parameters and malformed encoding', () => {
  for (const [path, parse] of [['inventory', inventorySelection], ['incidents', incidentSelection], ['metrics', metricsSelection]] as const) {
    for (const suffix of ['tenantId=other', 'token=secret', 'permissions=all', 'execute=true', 'after=1-1-1-1-1', 'after=' + id + '&after=' + id, 'unknown=%ZZ', 'q=%C3%28', 'q=' + 'a'.repeat(2050)]) {
      expect(() => parse(`#/${path}?${suffix}`)).toThrow()
    }
  }
})
test('resource and time selections enforce bounds and never turn a broken fixed window into latest data', () => {
  for (const suffix of ['q=' + 'a'.repeat(101), 'q=hello%0Aworld', 'type=server', 'lifecycle=UNKNOWN', 'entityId=../../x']) expect(() => inventorySelection('#/inventory?' + suffix)).toThrow()
  expect(() => incidentSelection('#/incidents?status=unknown')).toThrow()
  for (const suffix of ['from=1', 'from=1&till=2', 'entityId=' + id + '&from=1&till=7200', 'entityId=' + id + '&from=02&till=3',
    'entityId=' + id + '&from=-1&till=0', 'entityId=' + id + '&from=2&till=1', 'range=24h', 'metricKey=arbitrary%20query']) expect(() => metricsSelection('#/metrics?' + suffix)).toThrow()
  expect(metricsSelection(`#/metrics?entityId=${id}&from=0&till=3600`)).toMatchObject({ entityId: id, from: 0, till: 3600 })
})
