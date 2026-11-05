import {routeConnection} from './routing.js';
import {aggregateZoneEdges} from './zones.js';

const GAP_X=100,GAP_Y=100,PAD=52,HEADER=82;
const roleRank={Presentation:0,Application:1,Domain:2,Infrastructure:3};

export function layoutZones(nodes,edges,zones){
 const byId=new Map(nodes.map(n=>[n.id,n])),owner=new Map(nodes.map(n=>[n.id,n.zoneId]));
 const weights=new Map();for(const e of edges)if(e.source!==e.target){const key=[e.source,e.target].sort().join('|');weights.set(key,(weights.get(key)??0)+e.count)}
 function ordered(ids){
  const contracts=ids.filter(id=>byId.get(id).contract?.anchor&&ids.includes(byId.get(id).contract.anchor));
  const remaining=new Set(ids.filter(id=>!contracts.includes(id)).sort((a,b)=>byId.get(a).c.name.localeCompare(byId.get(b).c.name)||a.localeCompare(b))),out=[];
  while(remaining.size){const last=out.at(-1);const id=[...remaining].sort((a,b)=>(weights.get([last,b].sort().join('|'))??0)-(weights.get([last,a].sort().join('|'))??0))[0];
   out.push(...contracts.filter(c=>byId.get(c).contract.anchor===id).sort(),id);remaining.delete(id);
  }
  return out;
 }
 const blocks=zones.map(z=>{
  const members=ordered(z.classIds),cols=Math.min(4,Math.max(1,Math.ceil(Math.sqrt(members.length*.85))));
  const children=[];let y=HEADER;
  for(let i=0;i<members.length;i+=cols){const row=members.slice(i,i+cols).map(id=>byId.get(id)),h=Math.max(...row.map(n=>n.height));
   row.forEach((n,j)=>children.push({...n,x:PAD+j*(n.width+GAP_X),y,rowTop:y-GAP_Y/2,rowBottom:y+h+GAP_Y/2}));y+=h+GAP_Y;
  }
  return {...z,children,width:PAD*2+cols*286+(cols-1)*GAP_X,height:y-GAP_Y+PAD};
 });
 const result=[];let sectionY=0;
 // Large external/test regions cannot stretch the useful component overview.
 for(const external of [false,true]){
  const group=blocks.filter(b=>b.external===external).sort((a,b)=>Number(a.defaultHidden)-Number(b.defaultHidden)||(roleRank[a.layer]??4)-(roleRank[b.layer]??4)||b.height-a.height||a.label.localeCompare(b.label));
  if(!group.length)continue;
  const area=group.reduce((sum,b)=>sum+(b.width+130)*(b.height+130),0);
  const target=Math.max(...group.map(b=>b.width),Math.min(6200,Math.sqrt(area*1.45)));
  const packed=[];
  for(const block of group){
   const candidates=[0,...packed.map(p=>p.x+p.width+150)].filter(x=>x+block.width<=target);
   const choices=candidates.map(x=>({x,y:Math.max(sectionY,...packed.filter(p=>x<p.x+p.width+150&&x+block.width+150>p.x).map(p=>p.y+p.height+150))}));
   const {x,y}=choices.sort((a,b)=>a.y-b.y||a.x-b.x)[0]??{x:0,y:sectionY};
   const placed={...block,x,y,children:block.children.map(n=>({...n,x:n.x+x,y:n.y+y,rowTop:n.rowTop+y,rowBottom:n.rowBottom+y}))};
   packed.push(placed);result.push(placed);
  }
  sectionY=Math.max(...packed.map(p=>p.y+p.height))+250;
 }
 const zoneEdges=aggregateZoneEdges(edges,owner),boxes=result.map(n=>({left:n.x,top:n.y,right:n.x+n.width,bottom:n.y+n.height})),byZone=new Map(result.map(z=>[z.id,z]));
 const routes=zoneEdges.map((e,i)=>{
  const a=byZone.get(e.source),b=byZone.get(e.target),right=a.x+a.width/2<b.x+b.width/2;
  const fromSide=right?'right':'left',toSide=right?'left':'right';
  const start=[right?a.x+a.width:a.x,a.y+a.height/2],end=[right?b.x:b.x+b.width,b.y+b.height/2];
  const path=routeConnection(start,end,boxes,right?1:-1,right?-1:1,i);
  return {...e,path,start,end,fromSide,toSide,label:[(start[0]+end[0])/2,(start[1]+end[1])/2]};
 });
 const scene={nodes:result.flatMap(z=>z.children),zones:result.map(({children,...z})=>z),zoneEdges:routes,edges,unrouted:routes.filter(r=>!r.path).length};
 // Resolve both precise method routes and passive class routes in the worker.
 // Hover and viewport changes only look up the resulting geometry.
 const route=createZoneRouter(scene),classRoutes={};
 const candidates=new Map([...edges,...edges.map(e=>({...e,sourceMethod:null,targetMethod:null}))].map(e=>[routeKey(e),e]));
 for(const [key,e] of candidates){const r=route(e);if(r){const {path,label,fromSide,toSide,sourceHandle,targetHandle}=r;classRoutes[key]={path,label,fromSide,toSide,sourceHandle,targetHandle}}}
 return {...scene,classRoutes};
}

// Routes inside a zone use the space between rows and columns, never card interiors.
function roundPolyline(points,radius=38){
 const compact=[];for(const p of points){if(compact.length&&Math.hypot(p[0]-compact.at(-1)[0],p[1]-compact.at(-1)[1])<.1)continue;
 while(compact.length>1){const a=compact.at(-2),b=compact.at(-1);if((a[0]===b[0]&&b[0]===p[0])||(a[1]===b[1]&&b[1]===p[1]))compact.pop();else break}compact.push(p)}
 if(!compact.length)return '';let d='M'+compact[0].join(' ');
 for(let i=1;i<compact.length-1;i++){const a=compact[i-1],b=compact[i],c=compact[i+1],ab=Math.hypot(b[0]-a[0],b[1]-a[1]),bc=Math.hypot(c[0]-b[0],c[1]-b[1]),r=Math.min(radius,ab/2,bc/2);
 const enter=[b[0]+(a[0]-b[0])*r/ab,b[1]+(a[1]-b[1])*r/ab],exit=[b[0]+(c[0]-b[0])*r/bc,b[1]+(c[1]-b[1])*r/bc];d+=` L${enter.join(' ')} Q${b.join(' ')} ${exit.join(' ')}`;}
 return d+' L'+compact.at(-1).join(' ');
}
const routeKey=e=>JSON.stringify([e.source,e.target,e.sourceMethod??null,e.targetMethod??null]);
export function createZoneRouter(layout){
 const nodes=new Map(layout.nodes.map(n=>[n.id,n])),zones=new Map(layout.zones.map(z=>[z.id,z]));
 const routeCache=new Map(),zoneBoxes=layout.zones.map(z=>({left:z.x,top:z.y,right:z.x+z.width,bottom:z.y+z.height}));
 const port=(n,side,method)=>[n.x+(side==='right'?n.width:0),n.y+(n.ports?.[method]??n.ports?.class??n.height/2)];
 return e=>{
  const cached=layout.classRoutes?.[routeKey(e)];if(cached)return {...e,...cached};
  const a=nodes.get(e.source),b=nodes.get(e.target);if(!a||!b)return null;
  let fromSide,toSide,path,start,end;
  if(a.zoneId===b.zoneId){
   const sameColumn=a.x===b.x,right=a.x<b.x;fromSide=sameColumn?'right':right?'right':'left';toSide=sameColumn?'right':right?'left':'right';
   start=port(a,fromSide,e.sourceMethod);end=port(b,toSide,e.targetMethod);
   const ax=start[0]+(fromSide==='right'?1:-1)*GAP_X/2,bx=end[0]+(toSide==='right'?1:-1)*GAP_X/2;
   if(a.rowTop===b.rowTop&&Math.abs(a.x-b.x)<a.width+GAP_X+1&&a!==b){const bend=Math.max(35,Math.abs(end[0]-start[0])*.5);path=`M${start.join(' ')} C${start[0]+(right?bend:-bend)} ${start[1]},${end[0]+(right?-bend:bend)} ${end[1]},${end.join(' ')}`;}
   else {const y=b.y>a.y?a.rowBottom:a.rowTop;path=roundPolyline([start,[ax,start[1]],[ax,y],[bx,y],[bx,end[1]],end]);}
  }else{
   const za=zones.get(a.zoneId),zb=zones.get(b.zoneId),right=a.x+a.width/2<b.x+b.width/2;
   fromSide=right?'right':'left';toSide=right?'left':'right';
   start=port(a,fromSide,e.sourceMethod);end=port(b,toSide,e.targetMethod);
   // Each relation leaves its actual row. No shared invisible hub at a zone's centre,
   // and no reversal where a local segment joins the inter-zone curve.
   let hash=0;for(const char of e.source+'|'+e.target+'|'+(e.sourceMethod??'')+'|'+(e.targetMethod??''))hash=(hash*31+char.charCodeAt(0))>>>0;
   const lane=(hash%7-3)*4;
   const exit=[fromSide==='right'?za.x+za.width:za.x,a.rowTop+lane],entry=[toSide==='right'?zb.x+zb.width:zb.x,b.rowTop+lane];
   const cacheKey=[...exit,...entry,fromSide,toSide].join('|');
   if(!routeCache.has(cacheKey))routeCache.set(cacheKey,routeConnection(exit,entry,zoneBoxes,fromSide==='right'?1:-1,toSide==='right'?1:-1,0));
   const middle=routeCache.get(cacheKey);if(!middle)return null;
   const local=(p,side,boundary)=>{const desired=p[0]+(side==='right'?1:-1)*(GAP_X/2+lane),x=side==='right'?Math.min(desired,boundary[0]-24):Math.max(desired,boundary[0]+24);return [p,[x,p[1]],[x,boundary[1]],boundary]};
   const first=roundPolyline(local(start,fromSide,exit),26),last=roundPolyline(local(end,toSide,entry).reverse(),26);
   path=first+middle.replace(/^M[^CLQ]+/,'')+last.replace(/^M[^CLQ]+/,'');
  }
  return {...e,path,fromSide,toSide,label:[(start[0]+end[0])/2,(start[1]+end[1])/2],sourceHandle:(a.ports?.[e.sourceMethod]!==undefined?e.sourceMethod:'class')+'-'+fromSide+'-source',targetHandle:(b.ports?.[e.targetMethod]!==undefined?e.targetMethod:'class')+'-'+toSide+'-target'};
 };
}
