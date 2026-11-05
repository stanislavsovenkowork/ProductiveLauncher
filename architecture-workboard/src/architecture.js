const norm=p=>(p??'').replaceAll('\\','/');
const generic=new Set(['src','source','sources','services','impl','implementation']);
const roles=new Map([['domain','Domain'],['application','Application'],['infrastructure','Infrastructure'],['persistence','Infrastructure'],['presentation','Presentation'],['webapi','Presentation'],['api','Presentation'],['web','Presentation']]);
export const human=s=>s.replace(/([a-zа-я0-9])([A-ZА-Я])/g,'$1 $2').replace(/([A-Z])([A-Z][a-z])/g,'$1 $2').replaceAll('_',' ');
const key=(...parts)=>parts.map(encodeURIComponent).join('/');
const relative=(file,root)=>norm(file).startsWith(norm(root)+'/')?norm(file).slice(norm(root).length+1):norm(file);
const rank={component:0,module:0,Presentation:1,Application:2,Domain:3,Infrastructure:4,shared:5,tests:9};

export function projectShape(p,rootName){
 const bits=(p.name??'').split('.'),dirs=norm(p.directory).split('/').filter(Boolean);
 const roleToken=bits.find(t=>roles.has(t.toLowerCase()))??dirs.findLast(t=>roles.has(t.toLowerCase()));
 const layer=roles.get(roleToken?.toLowerCase());
 const moduleAt=dirs.findIndex(t=>t.toLowerCase()==='modules');
 const shared=dirs.some(t=>t.toLowerCase()==='shared')||p.solutionFolders?.some(t=>t.toLowerCase()==='shared');
 let component;
 if(p.isTest)component='Tests';
 else if(shared)component='Shared';
 else if(moduleAt>=0&&dirs[moduleAt+1])component=dirs[moduleAt+1];
 else {const index=bits.findIndex(t=>roles.has(t.toLowerCase()));component=index>0?bits.slice(0,index).join('.'):layer?rootName:p.name;}
 return {component,layer,shared,reason:`Проект: ${p.path??p.name}${p.solutionFolders?.length?' · solution: '+p.solutionFolders.join(' / '):''}${layer?' · слой: '+layer:''}`};
}

export function buildArchitecture(graph,overrides={}){
 const groups=new Map(),classes=new Map(graph.classes.map(c=>[c.id,c])),owner=new Map(),projects=new Map();
 const add=(id,label,kind,parent,reason,extra={})=>{if(!groups.has(id)){groups.set(id,{id,label,kind,parent,reason,children:[],classIds:[],...extra});if(parent)groups.get(parent).children.push(id);}return groups.get(id)};
 add('root',graph.projectName,'root',null,'Решение '+graph.projectPath);
 const facts=graph.projects?.length?graph.projects:[...new Set(graph.classes.map(c=>c.project))].map(name=>({id:name,name,path:name,directory:'',references:[],isTest:/tests?$/i.test(name)}));
 for(const p of facts)projects.set(p.id,p);
 const attach=(c,parent)=>{owner.set(c.id,parent);groups.get(parent).children.push(c.id);for(let at=parent;at;at=groups.get(at)?.parent)groups.get(at).classIds.push(c.id)};
 for(const c of graph.classes.filter(c=>!c.containingTypeId||!classes.has(c.containingTypeId)||overrides.custom?.some(g=>g.classIds.includes(c.id))).sort((a,b)=>a.id.localeCompare(b.id))){
  const p=projects.get(c.projectId)||facts.find(p=>p.name===c.project)||{id:c.project,name:c.project,directory:'',path:c.project};
  const shape=projectShape(p,graph.projectName),custom=overrides.custom?.findLast(g=>g.classIds.includes(c.id));
  let component=custom?.name??shape.component,id=custom?'custom:'+custom.id:key('component',component);
  let node=add(id,component,custom?'component':p.isTest?'tests':shape.shared?'shared':'component','root',custom?'Пользовательская область':shape.reason);
  const projectDir=norm(p.directory),file=relative(c.locations[0]?.file,graph.root);
  let dirs=(projectDir&&file.startsWith(projectDir+'/')?file.slice(projectDir.length+1):file).split('/').slice(0,-1);
  const moduleAt=dirs.findIndex(t=>['modules','features'].includes(t.toLowerCase()));
  if(!custom&&moduleAt>=0&&dirs[moduleAt+1]){const module=dirs[moduleAt+1];node=add(key(node.id,'module',module),human(module),'module',node.id,'Папка: '+dirs.slice(0,moduleAt+2).join('/'));dirs=dirs.slice(moduleAt+2);}
  if(!custom&&p.isTest||!custom&&shape.shared){node=add(key(node.id,'project',p.id),p.name,'project',node.id,shape.reason,{projectId:p.id});}
  else if(!custom&&(moduleAt>=0?dirs.some(d=>roles.has(d.toLowerCase())):shape.layer)){const layer=moduleAt>=0?roles.get(dirs.find(d=>roles.has(d.toLowerCase())).toLowerCase()):shape.layer;node=add(key(node.id,'layer',layer),layer,'layer',node.id,shape.reason+' · папка: '+dirs.join('/'),{layer});}
  else if(!custom&&node.label!==p.name&&moduleAt<0){node=add(key(node.id,'project',p.id),p.name,'project',node.id,shape.reason,{projectId:p.id});}
  if(c.generated)node=add(key(node.id,'generated'),'Generated','generated',node.id,'Сгенерированные исходники');
  const meaningful=dirs.filter(d=>!generic.has(d.toLowerCase())&&!roles.has(d.toLowerCase()));
  // Prefer physical feature folders; namespace is a fallback only for flat source layouts.
  if(!meaningful.length&&c.group){const suffix=c.group.split('.').filter(d=>!p.name.split('.').includes(d)&&!graph.projectName.split('.').includes(d)&&!generic.has(d.toLowerCase())&&!roles.has(d.toLowerCase()));if(suffix.length)meaningful.push(...suffix.slice(-2));}
  for(const dir of meaningful)node=add(key(node.id,'area',dir),human(dir),'area',node.id,'Папка / namespace: '+meaningful.join(' / '));
  attach(c,node.id);
 }
 // Nested helpers stay inside their owning type while retaining source identity.
 const pending=graph.classes.filter(c=>c.containingTypeId&&classes.has(c.containingTypeId)&&!owner.has(c.id));
 for(let pass=0;pending.length&&pass<=graph.classes.length;pass++)for(let i=pending.length-1;i>=0;i--){const c=pending[i],parent=owner.get(c.containingTypeId);if(parent){owner.set(c.id,parent);for(let at=parent;at;at=groups.get(at)?.parent)groups.get(at).classIds.push(c.id);pending.splice(i,1)}}
 for(const c of pending)attach(c,'root');
 // Bound large leaves using actual source files, never invented business modules.
 for(const g of [...groups.values()]){const direct=g.children.filter(id=>classes.has(id));if(direct.length>20){const buckets=new Map();for(const id of direct){const file=relative(classes.get(id).locations[0]?.file,graph.root);if(!buckets.has(file))buckets.set(file,[]);buckets.get(file).push(id)}
   if(buckets.size>1){g.children=g.children.filter(id=>!classes.has(id));for(const [file,ids] of buckets){const leaf=add(key(g.id,'file',file),file.split('/').at(-1),'file',g.id,'Исходный файл: '+file);leaf.classIds=g.classIds.filter(id=>ids.includes(id)||ids.includes(classes.get(id)?.containingTypeId));leaf.children=ids;for(const id of leaf.classIds)owner.set(id,leaf.id)}}
  }
 }
 for(const g of groups.values()){g.classIds=[...new Set(g.classIds)];g.label=overrides.names?.[g.id]??g.label;g.children.sort((a,b)=>{const x=groups.get(a),y=groups.get(b);return (x?.kind==='component'&&x.label===graph.projectName?-1:rank[x?.layer??x?.kind]??6)-(y?.kind==='component'&&y.label===graph.projectName?-1:rank[y?.layer??y?.kind]??6)||(x?.label??classes.get(a)?.name??a).localeCompare(y?.label??classes.get(b)?.name??b)});}
 return {groups,classes,owner,projects,graph};
}

export function ancestors(model,id){const out=[];let at=model.groups.has(id)?id:model.owner.get(id);while(at){out.unshift(model.groups.get(at));at=model.groups.get(at)?.parent}return out}
export const relationName=kind=>({calls:'Вызовы',constructs:'Создание',uses:'Использование типов',inherits:'Наследование',implements:'Реализация',project:'Ссылки проектов'}[kind]??kind);

export function visualEdges(edges,kind='all'){
 const result=new Map();for(const e of edges.filter(e=>kind==='all'||e.kind===kind)){const id=key('visual',e.source,e.target,e.sourceMethod??'',e.targetMethod??'');if(!result.has(id))result.set(id,{...e,id,originals:[],kinds:[]});const b=result.get(id);b.originals.push(...e.originals);if(!b.kinds.includes(e.kind))b.kinds.push(e.kind)}
 return [...result.values()].map(e=>({...e,count:e.originals.length,kind:e.kinds.length===1?e.kinds[0]:'mixed'}));
}

export function projectView(model,scope='root',{expanded=false,focus=null,classIds=null,page=0,pageSize=24}={}){
 const {groups,classes,graph}=model,g=groups.get(scope)??groups.get('root');
 let ids=classIds?classIds.filter(id=>classes.has(id)):focus?[focus,...new Set(graph.edges.filter(e=>e.sourceClass===focus||e.targetClass===focus).flatMap(e=>[e.sourceClass,e.targetClass]))].filter(id=>classes.has(id)):g.children;
 const unique=[...new Set(ids)],total=unique.length;ids=unique.slice(page*pageSize,(page+1)*pageSize);
 const members=id=>{if(groups.has(id))return groups.get(id).classIds;const result=new Set([id]);let size;do{size=result.size;for(const c of graph.classes)if(result.has(c.containingTypeId)&&model.owner.get(c.id)===model.owner.get(id))result.add(c.id)}while(result.size!==size);return [...result]};
 let nodes=ids.map(id=>groups.has(id)?{id,kind:'area',group:groups.get(id),classIds:members(id)}:{id,kind:'class',c:classes.get(id),classIds:members(id)});
 const representatives=new Map();for(const n of nodes)for(const id of n.classIds)representatives.set(id,n.id);
 const activeClasses=new Set(nodes.flatMap(n=>n.classIds));
 // A contextual stub represents an actual neighbouring area; its hidden classes stay searchable.
 const context=new Map();
 function outside(id){const path=ancestors(model,id),here=ancestors(model,g.id);let common=0;while(common<path.length&&common<here.length&&path[common].id===here[common].id)common++;
  const target=path.some(p=>p.id===scope)?g:path[common]??path.at(-1)??groups.get('root');
  const cid='context:'+target.id;if(!context.has(cid))context.set(cid,{id:cid,kind:'context',group:target,classIds:[]});context.get(cid).classIds.push(id);representatives.set(id,cid);return cid;}
 const relevant=graph.edges.filter(e=>activeClasses.has(e.sourceClass)||activeClasses.has(e.targetClass));
 for(const e of relevant){if(!representatives.has(e.sourceClass))outside(e.sourceClass);if(!representatives.has(e.targetClass))outside(e.targetClass)}
 nodes.push(...[...context.values()].map(n=>({...n,classIds:[...new Set(n.classIds)]})));
 const buckets=new Map();
 for(const e of relevant){const source=representatives.get(e.sourceClass),target=representatives.get(e.targetClass);if(!source||!target)continue;
  const sourceMethod=expanded&&nodes.find(n=>n.id===source)?.kind==='class'&&e.sourceClass===source?e.source:null,targetMethod=expanded&&nodes.find(n=>n.id===target)?.kind==='class'&&e.targetClass===target?e.target:null;
  if(source===target&&(!expanded||sourceMethod===targetMethod))continue;
  const id=key(source,target,e.kind,e.dispatch,sourceMethod??'',targetMethod??'');
  if(!buckets.has(id))buckets.set(id,{id,source,target,kind:e.kind,dispatch:e.dispatch,sourceMethod,targetMethod,originals:[],pairs:new Set()});const bucket=buckets.get(id);bucket.originals.push(e);bucket.pairs.add(e.sourceClass+'→'+e.targetClass);
 }
 // Project-reference edges are valid only at views representing complete projects.
 const projectRepresentatives=new Map();for(const p of model.projects.values()){const all=graph.classes.filter(c=>(c.projectId||c.project)===p.id);if(!all.length)continue;const rep=representatives.get(all[0].id);if(rep&&nodes.find(n=>n.id===rep)?.kind==='area'&&all.every(c=>representatives.get(c.id)===rep))projectRepresentatives.set(p.id,rep);}
 for(const p of model.projects.values())for(const ref of p.references??[]){const source=projectRepresentatives.get(p.id),target=projectRepresentatives.get(ref);if(!source||!target||source===target)continue;const id=key(source,target,'project');if(!buckets.has(id))buckets.set(id,{id,source,target,kind:'project',dispatch:'static',originals:[],pairs:new Set()});const b=buckets.get(id);b.originals.push({id:p.id+'→'+ref,kind:'project',source:p.id,target:ref});b.pairs.add(p.id+'→'+ref);}
 return {scope:g,nodes,edges:[...buckets.values()].map(({pairs,...b})=>({...b,count:pairs.size})),total,pages:Math.max(1,Math.ceil(total/pageSize)),page};
}
