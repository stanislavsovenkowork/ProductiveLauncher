import {routeConnection} from './routing.js';
let defaultEngine;
async function engine(){if(!defaultEngine){const {default:ELK}=await import('elkjs/lib/elk.bundled.js');defaultEngine=new ELK()}return defaultEngine}
const intersects=(a,b)=>a.x<b.x+b.width+55&&a.x+a.width+55>b.x&&a.y<b.y+b.height+45&&a.y+a.height+45>b.y;
export async function layoutVisible(nodes,edges,previous={},direction='RIGHT',providedEngine=null){
 if(!nodes.length)return {nodes:[],edges:[],unrouted:0};
 const byId=new Map(nodes.map(n=>[n.id,n]));
 const usable=nodes.filter(n=>previous[n.id]?.width===n.width&&previous[n.id]?.height===n.height);
 let placed;
 if(usable.length){
  placed=usable.map(n=>({...n,x:previous[n.id].x,y:previous[n.id].y}));
  for(const n of nodes.filter(n=>!usable.includes(n))){const before=previous[n.id];let p={...n,x:before?.x??0,y:before?.y??0};let attempts=0;while(placed.some(q=>intersects(p,q))){p.y+=80;if(++attempts%20===0){p.x+=n.width+140;p.y=0}}placed.push(p)}
 }else if(nodes.every(n=>n.kind!=='class')){
  // Container views have architectural order, bounded columns and dedicated line corridors.
  // Expanding an area switches to a dependency layout for its actual classes.
  const columns=direction==='DOWN'?2:3,gapX=145,gapY=100;
  placed=nodes.map((n,i)=>({...n,x:(i%columns)*(270+gapX),y:Math.floor(i/columns)*(174+gapY)}));
 }else{
  const result=await (providedEngine??await engine()).layout({id:'visible',layoutOptions:{'elk.algorithm':'layered','elk.direction':direction,'elk.spacing.nodeNode':'85','elk.layered.spacing.nodeNodeBetweenLayers':'150','elk.spacing.componentComponent':'95','elk.aspectRatio':'1.3','elk.layered.considerModelOrder.strategy':'NODES_AND_EDGES','elk.randomSeed':'1'},children:nodes.map(n=>({id:n.id,width:n.width,height:n.height})),edges:[...new Map(edges.filter(e=>e.source!==e.target).map(e=>[e.source+'>'+e.target,{id:e.id,sources:[e.source],targets:[e.target]}])).values()]});
  placed=result.children.map(n=>({...byId.get(n.id),x:n.x,y:n.y}));
 }
 const positions=new Map(placed.map(n=>[n.id,n]));
 const boxes=placed.map(n=>({left:n.x,top:n.y,right:n.x+n.width,bottom:n.y+n.height}));
 let unrouted=0;const labels=[];
 const routed=edges.map((e,index)=>{
  const a=positions.get(e.source),b=positions.get(e.target);if(!a||!b)return null;
  const vertical=Math.abs(a.x-b.x)<Math.min(a.width,b.width),fromSide=vertical?'right':a.x<b.x?'right':'left',toSide=vertical?'right':a.x<b.x?'left':'right';
  const offset=(node,method)=>node.ports?.[method]??node.ports?.class??node.height/2;
  const start=[a.x+(fromSide==='right'?a.width:0),a.y+offset(a,e.sourceMethod)],end=[b.x+(toSide==='right'?b.width:0),b.y+offset(b,e.targetMethod)];
  const path=routeConnection(start,end,boxes,fromSide==='right'?1:-1,toSide==='right'?1:-1,index);
  if(!path)unrouted++;
  const candidates=[.5,.35,.65,.22,.78,.12,.88].map(t=>midpoint(path,t));
  const label=candidates.find(([x,y])=>labels.every(([lx,ly])=>Math.abs(x-lx)>52||Math.abs(y-ly)>30)&&boxes.every(r=>x+25<r.left||x-25>r.right||y+15<r.top||y-15>r.bottom))??candidates[0];if(e.count>1)labels.push(label);
  return {...e,path,label,fromSide,toSide,sourceHandle:(a.ports?.[e.sourceMethod]!==undefined?e.sourceMethod:'class')+'-'+fromSide+'-source',targetHandle:(b.ports?.[e.targetMethod]!==undefined?e.targetMethod:'class')+'-'+toSide+'-target'};
 }).filter(Boolean);
 return {nodes:placed,edges:routed,unrouted};
}

function midpoint(path,fraction=.5){
 const points=[];let p=[0,0];for(const match of path.matchAll(/([MLCQ])([^MLCQ]+)/g)){const v=match[2].trim().split(/[ ,]+/).map(Number),type=match[1];if(type==='M'){p=v;points.push(p);continue}const end=v.slice(-2);for(let i=1;i<=20;i++){const t=i/20,u=1-t;points.push(type==='C'?[0,1].map(k=>u*u*u*p[k]+3*u*u*t*v[k]+3*u*t*t*v[k+2]+t*t*t*v[k+4]):type==='Q'?[0,1].map(k=>u*u*p[k]+2*u*t*v[k]+t*t*v[k+2]):[0,1].map(k=>u*p[k]+t*end[k]))}p=end}
 let total=0;const lengths=points.map((p,i)=>i?total+=Math.hypot(p[0]-points[i-1][0],p[1]-points[i-1][1]):0);return points[lengths.findIndex(l=>l>=total*fraction)]??[0,0];
}
