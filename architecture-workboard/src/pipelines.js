const important=/health|handle|process|execute|consume|dispatch|run|start|send|create/i;
const testProject=/(^|[.\s_-])tests?($|[.\s_-])/i;
const testFolder=/[/\\]tests?[/\\]/i;

function isTestClass(cls){return testProject.test(cls.project??'')||testFolder.test(cls.locations?.[0]?.file??'');}

function methodsOf(graph){
 const result=new Map();
 for(const cls of graph.classes??[])for(const method of cls.methods??[])result.set(method.id,{...method,classId:cls.id,className:cls.name,isTest:isTestClass(cls)});
 return result;
}

function reachability(id,adjacency,seen=new Set(),depth=0){
 if(depth>=12||seen.has(id))return 0;
 seen.add(id);let result=0;
 for(const edge of adjacency.get(id)??[]){result++;result+=reachability(edge.target,adjacency,new Set(seen),depth+1);}
 return result;
}

export function buildPipeline(graph,startId){
 const methods=methodsOf(graph),edges=(graph.edges??[]).filter(edge=>edge.kind==='calls'&&methods.has(edge.source)&&methods.has(edge.target));
 const adjacency=new Map();for(const edge of edges){if(!adjacency.has(edge.source))adjacency.set(edge.source,[]);adjacency.get(edge.source).push(edge);}
 const steps=[],pathEdges=[],seen=new Set();let at=startId;
 for(let depth=0;depth<12&&methods.has(at)&&!seen.has(at);depth++){
  seen.add(at);steps.push({id:at,...methods.get(at)});
  const next=(adjacency.get(at)??[]).filter(edge=>!seen.has(edge.target)).sort((a,b)=>reachability(b.target,adjacency)-reachability(a.target,adjacency)||a.target.localeCompare(b.target))[0];
  if(!next)break;pathEdges.push(next);at=next.target;
 }
 return {startId,steps,edgeIds:pathEdges.map(edge=>edge.id),classIds:[...new Set(steps.map(step=>step.classId))]};
}

// Follow calls in source order, expanding a call before continuing with the
// next call in its caller. The numbers are the static expansion order, not a
// breadth-first listing of unrelated branches.
export function implementationOptions(graph,methodId){
 const classes=new Map((graph.classes??[]).map(cls=>[cls.id,cls]));
 const options=(graph.edges??[]).filter(edge=>edge.kind==='calls'&&edge.dispatch==='implementation'&&edge.source===methodId).map(edge=>{
  const cls=classes.get(edge.targetClass),method=cls?.methods.find(item=>item.id===edge.target);
  return cls&&method?{id:method.id,classId:cls.id,className:cls.name,methodName:method.name,project:cls.project,isTest:isTestClass(cls)}:null;
 }).filter(Boolean).sort((a,b)=>Number(a.isTest)-Number(b.isTest)||a.className.localeCompare(b.className));
 // A production contract often has test doubles as well. They must not turn a
 // runtime pipeline into a fan-out through fixtures when real implementations exist.
 const production=options.filter(option=>!option.isTest);
 return production.length?production:options;
}

export function buildExecutionPipeline(graph,startId,{maxDepth=6,maxMethods=56,implementationChoices={}}={}){
 const methods=methodsOf(graph),edges=(graph.edges??[]).filter(edge=>edge.kind==='calls'&&methods.has(edge.source)&&methods.has(edge.target));
 if(!methods.has(startId))return {startId,steps:[],edgeIds:[],classIds:[],execution:true};
 const adjacency=new Map();for(const edge of edges){if(!adjacency.has(edge.source))adjacency.set(edge.source,[]);adjacency.get(edge.source).push(edge);}
 for(const [source,calls] of adjacency){
  const allowed=new Set(implementationOptions(graph,source).map(option=>option.id));
  if(allowed.size)adjacency.set(source,calls.filter(edge=>edge.dispatch!=='implementation'||allowed.has(edge.target)));
 }
 for(const calls of adjacency.values())calls.sort((a,b)=>(a.order??Number.MAX_SAFE_INTEGER)-(b.order??Number.MAX_SAFE_INTEGER)||(a.dispatch==='implementation'?implementationOptions(graph,a.source).findIndex(x=>x.id===a.target):0)-(b.dispatch==='implementation'?implementationOptions(graph,b.source).findIndex(x=>x.id===b.target):0)||a.target.localeCompare(b.target));
 const seen=new Set(),steps=[],used=[];
 function visit(id,depth){
  seen.add(id);steps.push({id,...methods.get(id)});
  if(depth>=maxDepth)return;
  const calls=adjacency.get(id)??[];
  for(const edge of calls){
   if(edge.dispatch==='implementation'){
    const chosen=implementationChoices[id];
    if(chosen&&chosen!==edge.target)continue;
   }
   if(!seen.has(edge.target)&&steps.length>=maxMethods)continue;
   used.push(edge);
   if(!seen.has(edge.target))visit(edge.target,depth+1);
  }
 }
 visit(startId,0);
 return {startId,steps,edgeIds:[...new Set(used.map(edge=>edge.id))],classIds:[...new Set(steps.map(step=>step.classId))],execution:true};
}

export function pipelineCandidates(graph,limit=24){
 const methods=methodsOf(graph),edges=(graph.edges??[]).filter(edge=>edge.kind==='calls'&&methods.has(edge.source)&&methods.has(edge.target));
 const incoming=new Set(edges.map(edge=>edge.target)),sources=[...new Set(edges.filter(edge=>!methods.get(edge.source)?.isTest).map(edge=>edge.source))];
 const score=pipeline=>{const first=pipeline.steps[0];return (important.test(first.name)?8:0)+(!incoming.has(first.id)?4:0)+Math.min(3,pipeline.steps.length);};
 return sources.map(id=>buildPipeline(graph,id)).filter(pipeline=>pipeline.steps.length>1).sort((a,b)=>score(b)-score(a)||b.steps.length-a.steps.length||a.steps[0].name.localeCompare(b.steps[0].name)).slice(0,limit);
}

export function documentedPipeline(graph,document){
 const methods=methodsOf(graph),steps=(document.steps??[]).map(id=>methods.has(id)?{id,...methods.get(id)}:null).filter(Boolean);
 const edgeIds=[];
 for(let index=1;index<steps.length;index++){const edge=(graph.edges??[]).find(item=>item.kind==='calls'&&item.source===steps[index-1].id&&item.target===steps[index].id);if(edge)edgeIds.push(edge.id);}
 return {id:document.id,startId:steps[0]?.id,steps,edgeIds,classIds:[...new Set(steps.map(step=>step.classId))],name:document.name,description:document.description,documented:true};
}
