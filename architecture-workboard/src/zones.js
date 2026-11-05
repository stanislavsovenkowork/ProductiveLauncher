import {ancestors,projectView} from './architecture.js';

export function componentOf(model,id){
 return ancestors(model,id).find(g=>g.parent==='root')?.id??'root';
}

// Folders describe ownership. Zones describe a continuous canvas, never a navigation level.
export function zoneView(model,component,{expanded=false,classIds=null}={}){
 component=componentOf(model,component);
 if(component==='root'&&!classIds)return {...projectView(model,'root',{pageSize:Infinity}),zones:[],zonal:false};
 const {graph,classes}=model,scope=model.groups.get(component),primary=new Set(classIds??scope.classIds);
 const included=new Set(primary);
 for(const e of classIds?[]:graph.edges)if(primary.has(e.sourceClass)||primary.has(e.targetClass)){
  if(classes.has(e.sourceClass))included.add(e.sourceClass);
  if(classes.has(e.targetClass))included.add(e.targetClass);
 }
 const zones=new Map(),owner=new Map();
 function place(c){
  const path=ancestors(model,c.id),root=path.find(g=>g.parent==='root')??scope;
  const local=!!classIds||root.id===component;
  const layer=path.find(g=>g.kind==='layer');
  // One physical feature folder, including all descendants. Never one box per source file.
  const anchor=path.find(g=>g.kind==='module')??path.find(g=>g.kind==='area');
  const generated=c.generated||path.some(g=>g.kind==='generated');
  const id=local?(generated?'zone:'+root.id+':generated':'zone:'+(anchor?.id??layer?.id??root.id)):'zone:external:'+root.id;
  if(!zones.has(id))zones.set(id,{id,kind:'zone',label:local?(generated?'Generated':anchor?.label??layer?.label??root.label):root.label,
   subtitle:local?(anchor&&layer?layer.label:layer?'Слой':'Компонент'):'Связанный компонент',layer:layer?.label??'',external:!local,
   layers:[],module:anchor?.kind==='module',defaultHidden:classIds?false:root.kind==='tests'||generated,groupId:anchor?.id??layer?.id??root.id,componentId:root.id,classIds:[],
   reason:local?(anchor?.reason??layer?.reason??root.reason):'Классы, непосредственно связанные с '+scope.label});
  const z=zones.get(id);if(layer&&!z.layers.includes(layer.label))z.layers.push(layer.label);
  if(z.module){z.subtitle='Модуль · '+z.layers.sort().join(' / ');z.layer='module'}
  owner.set(c.id,id);
 }
 for(const id of [...included].sort())place(classes.get(id));
 const declaredOwner=new Map(owner),contracts=new Map(),implementsByInterface=new Map();
 for(const e of graph.edges)if(e.kind==='implements'||e.kind==='inherits'){
  if(classes.get(e.targetClass)?.kind!=='interface')continue;
  if(!implementsByInterface.has(e.targetClass))implementsByInterface.set(e.targetClass,new Set());
  implementsByInterface.get(e.targetClass).add(e.sourceClass);
 }
 function concrete(id,seen=new Set()){
  if(seen.has(id))return [];seen.add(id);
  return [...(implementsByInterface.get(id)??[])].flatMap(next=>classes.get(next)?.kind==='interface'?concrete(next,seen):included.has(next)?[next]:[]);
 }
 // A contract remains one real symbol. Its calls still end at that symbol, never an assumed runtime implementation.
 for(const id of [...included].sort())if(classes.get(id).kind==='interface'){
  const implementations=[...new Set(concrete(id))].sort();if(!implementations.length)continue;
  const candidates=new Map();for(const impl of implementations){const zone=declaredOwner.get(impl);if(!zone||zones.get(zone).defaultHidden)continue;candidates.set(zone,(candidates.get(zone)??0)+1)}
  const target=[...candidates].sort((a,b)=>b[1]-a[1]||Number(zones.get(a[0]).external)-Number(zones.get(b[0]).external)||a[0].localeCompare(b[0]))[0]?.[0];
  if(!target)continue;
  // Do not move a component's own contracts into an unrelated external component.
  if(primary.has(id)&&zones.get(target).external)continue;
  owner.set(id,target);
  const origin=ancestors(model,id).slice(1).filter(g=>g.kind!=='file').map(g=>g.label).join(' / ');
  contracts.set(id,{origin,anchor:implementations.find(i=>declaredOwner.get(i)===target),implementations,moved:target!==declaredOwner.get(id)});
 }
 for(const id of included)zones.get(owner.get(id)).classIds.push(id);
 for(const [id,z] of zones)if(!z.classIds.length)zones.delete(id);
 const nodes=[...included].sort().map(id=>({id,kind:'class',c:classes.get(id),classIds:[id],zoneId:owner.get(id),contract:contracts.get(id),
  ownership:ancestors(model,id).slice(1).filter(g=>g.kind!=='file').map(g=>g.label).join(' / ')}));
 const buckets=new Map();
 for(const e of graph.edges){if(!included.has(e.sourceClass)||!included.has(e.targetClass))continue;
  const source=e.sourceClass,target=e.targetClass;
  const sourceMethod=expanded&&classes.get(source).methods.some(m=>m.id===e.source)?e.source:null;
  const targetMethod=expanded&&classes.get(target).methods.some(m=>m.id===e.target)?e.target:null;
  if(source===target&&(!expanded||sourceMethod===targetMethod))continue;
  const id=JSON.stringify([source,target,e.kind,sourceMethod,targetMethod]);
  if(!buckets.has(id))buckets.set(id,{id,source,target,sourceMethod,targetMethod,kind:e.kind,originals:[],count:0});
  const b=buckets.get(id);b.originals.push(e);b.count++;
 }
 return {scope,nodes,zones:[...zones.values()],edges:[...buckets.values()],owner,zonal:true,total:nodes.length,primaryCount:primary.size,pages:1};
}

export function visibleZones(projection,settings={}){
 return new Set(projection.zones.filter(z=>settings[z.id]!==undefined?settings[z.id]:!z.defaultHidden).map(z=>z.id));
}
export function filterZoneEdges(edges,owner,visible){return edges.filter(e=>visible.has(owner.get(e.source))&&visible.has(owner.get(e.target)))}
export function aggregateZoneEdges(edges,owner){
 const result=new Map();for(const e of edges){const source=owner.get(e.source),target=owner.get(e.target);if(!source||!target||source===target)continue;
 const id=JSON.stringify(['zones',source,target]);if(!result.has(id))result.set(id,{id,source,target,kind:'zone',originals:[],count:0});const b=result.get(id);b.originals.push(...e.originals);b.count+=e.count;}
 return [...result.values()];
}
