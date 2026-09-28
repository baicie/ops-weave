import { createEffect, createSignal, onCleanup, Show } from '@zeus-js/zeus'
import type { Graph, Node } from '@antv/x6'
import type { Definition, Layout, NodeType } from '../../api/workflows.ts'

const labels: Record<NodeType,string> = {SOURCE:'数据输入',MAP:'字段映射',TRIM:'去除空白',EMPTY_TO_NULL:'空串转空值',DEFAULT:'补充默认值',ENUM_MAP:'枚举替换',SCALE:'数值换算',FILTER:'条件过滤',VALIDATE:'模型校验',OUTPUT:'输出预览'}
const glyphs: Record<NodeType,string> = {SOURCE:'IN',MAP:'⇄',TRIM:'Aa',EMPTY_TO_NULL:'∅',DEFAULT:'＋',ENUM_MAP:'≍',SCALE:'×',FILTER:'▽',VALIDATE:'✓',OUTPUT:'OUT'}
let engine: Promise<typeof import('@antv/x6')> | undefined
function loadEngine() {
  return engine ??= import('@antv/x6').then(api => {
    api.Shape.HTML.register({shape:'opsweave-workflow-node',width:220,height:76,effect:['data'],html(cell) {
      const data=cell.getData(); const button=document.createElement('button');button.type='button';button.className='workflow-node'+(data.selected?' is-selected':'');button.dataset.nodeId=cell.id;
      button.setAttribute('aria-label',data.label+'节点 '+cell.id);button.setAttribute('aria-pressed',String(data.selected));button.dataset.kind=data.type;
      const icon=document.createElement('b');icon.textContent=glyphs[data.type as NodeType];const content=document.createElement('span');const label=document.createElement('strong');label.textContent=data.label;
      const subtitle=document.createElement('small');subtitle.textContent=data.subtitle;content.append(label,subtitle);button.append(icon,content);
      button.addEventListener('click',()=>data.select(cell.id));button.addEventListener('keydown',(e)=>{if(!['ArrowUp','ArrowDown','ArrowLeft','ArrowRight'].includes(e.key)||data.locked())return;e.preventDefault();const p=(cell as Node).position();data.move(cell.id,p.x+(e.key==='ArrowRight'?10:e.key==='ArrowLeft'?-10:0),p.y+(e.key==='ArrowDown'?10:e.key==='ArrowUp'?-10:0))});return button;
    }});return api;
  }).catch(error=>{engine=undefined;throw error})
}
export function WorkflowCanvas(props:{definition:()=>Definition;layout:()=>Layout;selected:()=>string;select:(id:string)=>void;zoom:()=>number;onZoom:(z:number)=>void;fit:()=>number;locked:()=>boolean;move:(id:string,x:number,y:number)=>void}) {
  const [status,setStatus]=createSignal('loading');let host:HTMLDivElement|null=null;let graph:Graph|undefined;let disposed=false;let resizing:ResizeObserver|undefined;let syncing=false;let signature='';let fitRequest=props.fit();
  const move=(id:string,x:number,y:number)=>{if(!props.locked())props.move(id,Math.max(0,Math.min(4000,Math.round(x))),Math.max(0,Math.min(4000,Math.round(y))))}
  function fit(){graph?.zoomToFit({padding:32,maxScale:1});if(graph)props.onZoom(graph.zoom())}
  function sync(){
    const d=props.definition(),positions=props.layout(),selected=props.selected(),locked=props.locked(),zoom=props.zoom(),nextFit=props.fit();if(!graph||disposed)return;
    syncing=true;
    const next=d.id+'@'+d.revision+':'+d.nodes.map(n=>n.id).join(',');
    if(signature!==next){signature=next;graph.clearCells();graph.addNodes(d.nodes.map(n=>({id:n.id,shape:'opsweave-workflow-node',...positions[n.id],data:{}})));graph.addEdges(d.edges.map((e,i)=>({id:'edge-'+i,source:{cell:e.from,anchor:'bottom'},target:{cell:e.to,anchor:'top'},connector:{name:'rounded'},router:{name:'orth'},attrs:{line:{stroke:'var(--graph-edge)',strokeWidth:2,targetMarker:{name:'classic',size:7}}},zIndex:0})))}
    for(const n of d.nodes){const cell=graph.getCellById(n.id) as Node;const p=positions[n.id]??{x:0,y:0};cell.position(p.x,p.y);const subtitle=n.type==='OUTPUT'?'只读预览 · 不写入':n.type==='SOURCE'?(d.source.kind==='MANUAL_SAMPLE'?'手工样本':'Zabbix 保留批次'):n.type==='VALIDATE'?d.target.id+' · v'+d.target.revision:n.id;
      const data=cell.getData();if(data.selected!==(n.id===selected)||data.subtitle!==subtitle||data.type!==n.type)cell.setData({type:n.type,label:labels[n.type],subtitle,selected:n.id===selected,select:props.select,locked:props.locked,move});
    }
    if(Math.abs(graph.zoom()-zoom)>.001)graph.zoomTo(zoom);if(nextFit!==fitRequest){fitRequest=nextFit;fit()}syncing=false;
  }
  async function init(node:HTMLDivElement|null){host=node;if(!host)return;try{const api=await loadEngine();if(disposed||!host)return;graph=new api.Graph({container:host,width:host.clientWidth,height:host.clientHeight,grid:{size:20,visible:true,type:'dot',args:{color:'#cbd5e1',thickness:1}},background:{color:'transparent'},panning:{enabled:true,eventTypes:['leftMouseDown']},mousewheel:{enabled:true,modifiers:['ctrl','meta'],minScale:.25,maxScale:1.5},scaling:{min:.25,max:1.5},interacting:()=>({nodeMovable:!props.locked(),edgeMovable:false,magnetConnectable:false,arrowheadMovable:false}),connecting:{allowBlank:false,allowLoop:false,allowEdge:false,validateConnection:()=>false}});
      graph.use(new api.Snapline({enabled:true}));graph.on('node:click',({node})=>props.select(node.id));graph.on('node:moved',({node})=>{if(!syncing){const p=node.position();move(node.id,p.x,p.y)}});graph.on('scale',({sx})=>{if(!syncing)props.onZoom(sx)});
      resizing=new ResizeObserver(()=>{if(graph&&host&&host.clientWidth)graph.resize(host.clientWidth,host.clientHeight)});resizing.observe(host);sync();fit();setStatus('ready');
    }catch{if(!disposed)setStatus('error')}
  }
  createEffect(sync);onCleanup(()=>{disposed=true;resizing?.disconnect();graph?.dispose();graph=undefined});
  return <div class="graph-frame" data-engine="x6" data-graph-status={status()}><div class="workflow-canvas x6-canvas" aria-label="工作流画布" ref={node=>void init(node)} /><Show when={status()!=='ready'}><div class="graph-message" role="status">{status()==='error'?'画布加载失败，请刷新页面重试。':'正在加载工作流画布…'}</div></Show><div class="graph-caption">拖动节点调整位置 · 拖动空白移动画布 · Ctrl / ⌘ + 滚轮缩放</div></div>
}
