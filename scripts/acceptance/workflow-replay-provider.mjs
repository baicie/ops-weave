// Local acceptance data only. This script never starts a product service or resends a mutation.
import {readFileSync,writeFileSync,mkdirSync,existsSync,realpathSync} from 'node:fs';
import {resolve,isAbsolute,relative} from 'node:path';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {setTimeout as delay} from 'node:timers/promises';

const root=realpathSync(fileURLToPath(new URL('../../',import.meta.url)));
const directory=process.env.OPSWEAVE_ACCEPTANCE_REPLAY_DIR;
const credentialsFile=process.env.OPSWEAVE_ACCEPTANCE_PROVIDER_CREDENTIALS;
if(!directory||!credentialsFile||!isAbsolute(directory)||!isAbsolute(credentialsFile))throw Error('Explicit private absolute paths are required');
mkdirSync(directory,{recursive:true});
const privateDir=realpathSync(directory),relativeDir=relative(root,privateDir);
if(!relativeDir||(!relativeDir.startsWith('..')&&!isAbsolute(relativeDir)))throw Error('Acceptance evidence must remain outside the checkout');
const endpoint='http://127.0.0.1:18088/api_jsonrpc.php';
const path=resolve(privateDir,'provider-fixture.json');
const state=existsSync(path)?JSON.parse(readFileSync(path,'utf8')):{schemaVersion:'1.0',syntheticFixture:true,id:randomUUID(),endpoint};
if(state.schemaVersion!=='1.0'||state.syntheticFixture!==true||state.endpoint!==endpoint||!/^[a-f0-9-]{36}$/.test(state.id))throw Error('Owned synthetic acceptance fixture required');
if(!state.cacheReloaded&&process.env.OPSWEAVE_ACCEPTANCE_RELOAD_PROVIDER_CACHE!=='1')throw Error('Explicit local container cache reload opt-in is required');
const save=()=>writeFileSync(path,JSON.stringify(state,null,2)+'\n',{mode:0o600});
const credentials=JSON.parse(readFileSync(credentialsFile,'utf8'));let token;
async function rpc(method,params){
 const response=await fetch(endpoint,{method:'POST',headers:{'Content-Type':'application/json',...(token&&method!=='apiinfo.version'?{Authorization:'Bearer '+token}:{})},body:JSON.stringify({jsonrpc:'2.0',method,params,id:1}),redirect:'error',signal:AbortSignal.timeout(15000)});
 const body=await response.json();if(response.status!==200||body.error||!Object.hasOwn(body,'result'))throw Error('Local provider operation failed: '+method);return body.result;
}
token=await rpc('user.login',{username:credentials.username,password:credentials.adminPassword});
if(typeof token!=='string'||!token.length)throw Error('Local provider authentication failed');
state.token=token;state.providerVersion=await rpc('apiinfo.version',{});save();
async function mutation(name,method,params){
 if(state[name]?.result)return state[name].result;
 if(state[name]?.attempted)throw Error('Previous '+name+' must be observed before any resend');
 state[name]={attempted:true};save();
 const result=await rpc(method,params);state[name].result=result;save();return result;
}
const group=await mutation('group','hostgroup.create',{name:'OpsWeave Synthetic Fixture '+state.id});
const host=await mutation('host','host.create',{host:'opsweave-replay-fixture-'+state.id,groups:[{groupid:group.groupids[0]}],tags:[{tag:'data_mode',value:'Synthetic Fixture'}]});
state.hostId=host.hostids[0];
const itemBase={hostid:state.hostId,type:2,history:'1d',trends:'0',delay:'0',trapper_hosts:'172.16.0.0/12,127.0.0.1',tags:[{tag:'data_mode',value:'Synthetic Fixture'}]};
state.metricItemId=(await mutation('metricItem','item.create',{...itemBase,name:'Synthetic Fixture historical CPU',key_:'system.cpu.util[,user]',value_type:0,units:'%'})).itemids[0];
state.logItemId=(await mutation('logItem','item.create',{...itemBase,name:'Synthetic Fixture historical logs',key_:'log[/var/log/opsweave-replay-fixture.log]',value_type:2})).itemids[0];
save();
// Verify fixture ownership before writing any historical observation.
const hosts=await rpc('host.get',{hostids:[state.hostId],output:['hostid','host'],selectTags:'extend'});
if(hosts.length!==1||hosts[0].host!=='opsweave-replay-fixture-'+state.id||!hosts[0].tags.some(t=>t.tag==='data_mode'&&t.value==='Synthetic Fixture'))throw Error('Synthetic provider ownership changed');
if(!state.cacheReloaded){
 execFileSync('docker',['exec','opsweave-zabbix-local-zabbix-server-1','zabbix_server','-R','config_cache_reload'],{stdio:'ignore',windowsHide:true,timeout:15000});
 state.cacheReloaded=true;save();await delay(2000);
}
if(!state.from){state.from=Math.floor(Date.now()/1000)-180;state.metricCount=60;state.logCount=60;save();}
if(Date.now()/1000-state.from>23*3600)throw Error('Historical fixture expired; use a new private acceptance directory');
const metrics=Array.from({length:state.metricCount},(_,i)=>({itemid:state.metricItemId,clock:state.from+i,ns:0,value:String(10+i)}));
const logs=Array.from({length:state.logCount},(_,i)=>({itemid:state.logItemId,clock:state.from+1,ns:i,value:'  Synthetic Fixture replay '+i+' 🧵\n<script>untrusted()</script>  '}));
const pushed=await mutation('push','history.push',[...metrics,...logs]);
if(pushed.response!=='success'||pushed.data.length!==120||pushed.data.some(x=>x.error))throw Error('Historical fixture push was not complete; do not resend');
let visible=false;
for(let attempt=0;attempt<30;attempt++){
 const [actualMetrics,actualLogs]=await Promise.all([rpc('history.get',{history:0,itemids:[state.metricItemId],time_from:state.from,time_till:state.from+59,output:['clock','ns','value'],sortfield:['clock','ns'],sortorder:['ASC','ASC'],limit:61}),rpc('history.get',{history:2,itemids:[state.logItemId],time_from:state.from,time_till:state.from+59,output:['clock','ns','value'],sortfield:['clock','ns'],sortorder:['ASC','ASC'],limit:61})]);
 visible=actualMetrics.length===60&&actualLogs.length===60&&actualMetrics.every((r,i)=>r.clock===String(metrics[i].clock)&&r.value===metrics[i].value)&&actualLogs.every((r,i)=>r.ns===String(i)&&r.value===logs[i].value);
 if(visible)break;await delay(500);
}
if(!visible)throw Error('Historical fixture is not exactly visible; no source resend');
state.visible=true;state.verifiedAt=new Date().toISOString();save();
console.log(JSON.stringify({actualProvider:true,syntheticFixture:true,metricRecords:60,logRecords:60,providerVersion:state.providerVersion,sourceMutationResends:0}));
