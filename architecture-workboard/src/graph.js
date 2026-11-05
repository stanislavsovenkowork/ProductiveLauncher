export const cardWidth=286;
export const cardHeight=(c,expanded)=>expanded?108+c.methods.length*36:140;
export const groupName=g=>g.split('.').slice(-1)[0];
// Compact, deterministic namespace blocks. Edges do not stretch the canvas.
export async function layoutGraph(graph,expanded,old={}){
 const gapX=40,gapY=32,pad=30,header=48,zoneGap=64;
 const groups=[...new Set(graph.classes.map(c=>c.group))].sort();
 const blocks=groups.map(group=>{
  const members=graph.classes.filter(c=>c.group===group).sort((a,b)=>a.name.localeCompare(b.name)||a.id.localeCompare(b.id));
  const cols=Math.min(6,Math.max(1,Math.ceil(Math.sqrt(members.length*.8))));
  let y=header;const points={};
  for(let i=0;i<members.length;i+=cols){const row=members.slice(i,i+cols);row.forEach((c,j)=>points[c.id]={x:pad+j*(cardWidth+gapX),y});y+=Math.max(...row.map(c=>cardHeight(c,expanded)))+gapY;}
  return {group,members,points,width:pad*2+cols*cardWidth+(cols-1)*gapX,height:y-gapY+pad};
 });
 const area=blocks.reduce((sum,b)=>sum+(b.width+zoneGap)*(b.height+zoneGap),0);
 const target=Math.max(...blocks.map(b=>b.width),Math.sqrt(area*1.5));
 let x=40,y=70,rowHeight=0;const positions={};
 for(const block of blocks){if(x>40&&x+block.width>target){x=40;y+=rowHeight+zoneGap;rowHeight=0;}
  for(const c of block.members){const p=block.points[c.id];positions[c.id]=old[c.id]??{x:x+p.x,y:y+p.y};}
  x+=block.width+zoneGap;rowHeight=Math.max(rowHeight,block.height);
 }
 return positions;
}
export function visibleEdges(graph,expanded){if(expanded)return graph.edges;return [...new Map(graph.edges.filter(e=>e.sourceClass!==e.targetClass).map(e=>[e.sourceClass+'>'+e.targetClass+':'+e.kind,{...e,id:e.sourceClass+'>'+e.targetClass+':'+e.kind}])).values()]}
export function highlightGraph(graph,selection,hover,expanded){const active=hover??selection;if(!active)return {active:null,related:new Set(),incoming:new Set(),outgoing:new Set(),directions:new Map()};const method=expanded&&active.type==='method'?active.id:null;const cls=active.classId??active.id;const related=new Set([cls]),incoming=new Set(),outgoing=new Set(),directions=new Map();for(const e of visibleEdges(graph,expanded)){let direction='';if(method)direction=e.source===method?'out':e.target===method?'in':'';else if(e.sourceClass!==e.targetClass)direction=e.sourceClass===cls?'out':e.targetClass===cls?'in':'';if(direction){directions.set(e.id,direction);related.add(e.sourceClass);related.add(e.targetClass);(direction==='out'?outgoing:incoming).add(direction==='out'?e.target:e.source);}}return {active:cls,method,related,incoming,outgoing,directions}}
