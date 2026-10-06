import {test,expect} from '@playwright/test'
import {mkdirSync} from 'node:fs'
import {fixture,batchId} from './host-workflow-fixture.ts'

for(const width of [1440,390])for(const theme of ['light','dark'])test(`fixed host scan fixture ${width} ${theme}`,async({page})=>{
 await page.setViewportSize({width,height:1000});await page.addInitScript(theme=>localStorage.setItem('opsweave.ui.theme',theme),theme);const calls=await fixture(page,'unknown')
 const results=page.getByRole('region',{name:'固定接入资产批次'});await expect(results).toContainText('已确认 0 批 / 0 条');await expect(results).toContainText('结果待确认');await expect(page.getByRole('textbox',{name:'运行来源标识字段'})).toHaveValue('entity_id');await expect(page.getByRole('textbox',{name:'运行来源标识字段'})).toBeDisabled();await expect(page.getByRole('button',{name:'执行并写入资产',exact:true})).toHaveCount(0);await expect(page.getByRole('button',{name:'启动扫描',exact:true})).toBeDisabled()
 await page.getByRole('button',{name:'恢复原扫描',exact:true}).click();await expect(results).toContainText('已确认 1 批 / 5 条');await expect(results.getByRole('cell',{name:'已确认',exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'停止任务',exact:true})).toBeEnabled()
 expect(calls.filter(c=>c.body)).toHaveLength(1);expect(calls.find(c=>c.body)!.body.settings).toEqual({identityField:'entity_id',nameField:'hostname'});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
 if(process.env.OPSWEAVE_SOURCE_SCREENSHOTS){mkdirSync(process.env.OPSWEAVE_SOURCE_SCREENSHOTS,{recursive:true});await page.locator('.workflow-runtime-section').filter({has:page.getByText('执行与任务管理',{exact:true})}).screenshot({path:process.env.OPSWEAVE_SOURCE_SCREENSHOTS+`/host-runtime-${width}-${theme}.png`})}
})
test('new scan is explicit and stopping preserves recovery instead of starting over',async({page})=>{
 const calls=await fixture(page);await page.getByRole('combobox',{name:'运行实体名称字段'}).selectOption('hostname');await page.getByRole('button',{name:'启动扫描',exact:true}).click();await expect(page.getByRole('button',{name:'停止任务',exact:true})).toBeEnabled();await page.getByRole('button',{name:'停止任务',exact:true}).click();await expect(page.getByRole('button',{name:'恢复原扫描',exact:true})).toBeEnabled();await expect(page.getByRole('button',{name:'启动扫描',exact:true})).toBeDisabled();expect(calls.filter(c=>c.body).map(c=>c.path.split('/').at(-1))).toEqual(['start','stop'])
})
test('lost resume response keeps original command and query never repeats the resume',async({page})=>{
 const calls=await fixture(page,'lost-resume');await page.getByRole('button',{name:'恢复原扫描',exact:true}).click();await expect(page.getByRole('region',{name:'任务控制结果待确认'})).toContainText('恢复请求待确认');await expect(page.getByRole('button',{name:'创建下一版',exact:true})).toBeDisabled();const original=calls.find(c=>c.path.endsWith('/resume'))!.body;await page.getByRole('button',{name:'查询原控制结果',exact:true}).click();await expect(page.getByRole('region',{name:'固定接入资产批次'})).toContainText('已确认 1 批 / 5 条');expect(calls.filter(c=>c.path.endsWith('/resume'))).toHaveLength(1);expect(calls.find(c=>c.path.includes('/commands/'))!.path).toContain(original.requestId)
})
for(const mode of ['corrupt','failed-read'])test('scan '+mode+' blocks writes and does not automatically reread',async({page})=>{
 const calls=await fixture(page,mode);await expect(page.locator('.workflow-runtime-panel').getByRole('alert').filter({hasText:mode==='corrupt'?'不符合契约':'资产批次读取失败'})).toBeVisible();await expect(page.getByRole('button',{name:'启动扫描',exact:true})).toBeDisabled();await expect(page.getByRole('button',{name:'恢复原扫描',exact:true})).toHaveCount(0);await page.waitForTimeout(5500);expect(calls.filter(c=>c.path.includes('/host-scans/'))).toHaveLength(1);expect(calls.filter(c=>c.body)).toHaveLength(0)
})
test('identity replacement drops a pending resume and late private acknowledgement',async({page})=>{
 let release!:()=>void;const hold=new Promise<void>(r=>release=r),calls=await fixture(page,'lost-resume',hold);await page.getByRole('button',{name:'恢复原扫描',exact:true}).click();await page.getByRole('button',{name:'查询原控制结果',exact:true}).click();await expect.poll(()=>calls.some(c=>c.path.includes('/commands/'))).toBe(true);await page.getByRole('button',{name:'清除开发会话',exact:true}).click();release();await expect(page.locator('.workflow-host-scan-results')).toHaveCount(0);expect(await page.evaluate(()=>JSON.stringify({...localStorage,...sessionStorage}))).not.toContain(batchId)
})

test('completed prior version permits an explicitly selected new scan without resuming the prior version',async({page})=>{
 const calls=await fixture(page,'completed-other');await page.getByRole('combobox',{name:'运行实体名称字段'}).selectOption('hostname');await expect(page.getByRole('button',{name:'启动扫描',exact:true})).toBeEnabled();await expect(page.getByRole('button',{name:'恢复原扫描',exact:true})).toHaveCount(0);expect(calls.filter(c=>c.body)).toHaveLength(0)
})

test('higher version explicitly replaces stopped incomplete scan without resuming old progress',async({page})=>{
 const calls=await fixture(page,'stopped-other');await page.getByRole('combobox',{name:'运行实体名称字段'}).selectOption('hostname');await expect(page.getByRole('button',{name:'恢复原扫描',exact:true})).toHaveCount(0);await page.getByRole('button',{name:'启动扫描',exact:true}).click();await expect(page.getByRole('button',{name:'停止任务',exact:true})).toBeEnabled();const posted=calls.filter(c=>c.body);expect(posted).toHaveLength(1);expect(posted[0].path).toContain('/start');expect(posted[0].body.revision).toBe(2);expect(posted[0].body.expectedGeneration).toBe(1);await expect(page.getByRole('region',{name:'固定接入资产批次'})).toContainText('已确认 0 批 / 0 条')
})
for(const mode of ['unknown-other','older-other'])test('version replacement '+mode+' retains the original version gate without writes',async({page})=>{
 const calls=await fixture(page,mode);await expect(page.getByRole('alert').filter({hasText:'当前任务固定在'})).toBeVisible();await expect(page.getByRole('button',{name:'启动扫描',exact:true})).toHaveCount(0);await expect(page.getByRole('button',{name:'恢复原扫描',exact:true})).toHaveCount(0);expect(calls.filter(c=>c.body)).toHaveLength(0)
})
