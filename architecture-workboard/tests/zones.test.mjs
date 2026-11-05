import test from 'node:test';
import assert from 'node:assert/strict';
import {buildArchitecture,visualEdges} from '../src/architecture.js';
import {zoneView,visibleZones,filterZoneEdges,aggregateZoneEdges,componentOf} from '../src/zones.js';
import {layoutZones,createZoneRouter} from '../src/zone-layout.js';
const cls=(id,projectId,file,extra={})=>({id,name:id,fullName:id,projectId,project:projectId,kind:'class',group:'',locations:[{file:'/repo/'+file}],methods:[],...extra});
const edge=(id,sourceClass,targetClass,kind='calls')=>({id,source:sourceClass,target:targetClass,sourceClass,targetClass,kind,count:1});
const fixture={projectName:'Notify',root:'/repo',projectPath:'/repo/Notify.sln',projects:[{id:'app',name:'Application',directory:'Application'},{id:'infra',name:'Infrastructure',directory:'Infrastructure'},{id:'test',name:'Notify.Tests',directory:'Tests',isTest:true}],classes:[cls('IChannel','app','Application/Contracts/IChannel.cs',{kind:'interface'}),cls('IFcm','app','Application/Contracts/IFcm.cs',{kind:'interface'}),cls('Caller','app','Application/Features/Delivery/Jobs/Caller.cs'),cls('Fcm','infra','Infrastructure/Channels/Fcm.cs'),cls('Sms','infra','Infrastructure/Channels/Sms.cs'),cls('Helper','infra','Infrastructure/Channels/Fcm.cs',{containingTypeId:'Fcm'}),cls('Test','test','Tests/ChannelsTests.cs')],edges:[edge('call','Caller','IChannel'),edge('fcm','Fcm','IFcm','implements'),edge('base','IFcm','IChannel','implements'),edge('sms','Sms','IChannel','implements'),edge('test','Test','Caller'),edge('nested','Fcm','Helper')]};
const project=()=>zoneView(buildArchitecture(fixture),'component/Notify');
const layout=p=>layoutZones(p.nodes.map(n=>({...n,width:286,height:140})),visualEdges(p.edges),p.zones);

test('one component contains every type, nested helper and directly connected external caller without pages',()=>{
 const p=project();assert.equal(p.pages,1);assert.equal(new Set(p.nodes.map(n=>n.id)).size,fixture.classes.length);assert.equal(p.nodes.length,fixture.classes.length);
 assert.deepEqual(p.edges.flatMap(e=>e.originals.map(o=>o.id)).sort(),fixture.edges.map(e=>e.id).sort());
 const m=buildArchitecture(fixture);assert.equal(componentOf(m,m.owner.get('Caller')),'component/Notify');
});
test('revision view keeps only changed types across components and their direct relations',()=>{
 const p=zoneView(buildArchitecture(fixture),'root',{classIds:['Caller','Test']});
 assert.equal(p.zonal,true);
 assert.deepEqual(p.nodes.map(n=>n.id).sort(),['Caller','Test']);
 assert.deepEqual(p.edges.flatMap(e=>e.originals.map(o=>o.id)),['test']);
 assert.equal(visibleZones(p).size,p.zones.length);
});
test('contracts sit with their implementations but retain identity, ownership and interface call targets',()=>{
 const p=project(),contract=p.nodes.find(n=>n.id==='IChannel');assert.equal(p.owner.get('IChannel'),p.owner.get('Fcm'));assert.equal(p.owner.get('IFcm'),p.owner.get('Fcm'));assert.match(contract.contract.origin,/Application.*Contracts/);assert.equal(p.nodes.filter(n=>n.id==='IChannel').length,1);
 assert.equal(p.edges.find(e=>e.originals.some(o=>o.id==='call')).target,'IChannel');assert.equal(p.edges.find(e=>e.originals.some(o=>o.id==='call')).source,'Caller');
});
test('hiding a zone removes all its incident relations, restore conserves exact evidence, layout does not change',()=>{
 const p=project(),before=layout(p),initial=visibleZones(p),tests=p.owner.get('Test');assert.ok(!initial.has(tests));
 const visible=visibleZones(p,{[tests]:true}),all=filterZoneEdges(p.edges,p.owner,visible);assert.equal(all.length,p.edges.length);
 const hidden=visibleZones(p,{[tests]:true,[p.owner.get('Fcm')]:false});const filtered=filterZoneEdges(p.edges,p.owner,hidden);assert.ok(filtered.every(e=>hidden.has(p.owner.get(e.source))&&hidden.has(p.owner.get(e.target))));
 assert.deepEqual(layout(p).nodes.map(n=>[n.id,n.x,n.y]),before.nodes.map(n=>[n.id,n.x,n.y]));
 const aggregates=aggregateZoneEdges(all,p.owner);assert.deepEqual(aggregates.flatMap(e=>e.originals.map(o=>o.id)).sort(),all.filter(e=>p.owner.get(e.source)!==p.owner.get(e.target)).flatMap(e=>e.originals.map(o=>o.id)).sort());
});
test('zone and class geometry stays disjoint; all crossing and self-method routes avoid cards',()=>{
 const p=project(),l=layout(p),route=createZoneRouter(l);assert.equal(l.unrouted,0);
 for(const collection of [l.nodes,l.zones])for(let i=0;i<collection.length;i++)for(let j=i+1;j<collection.length;j++){const a=collection[i],b=collection[j];assert.ok(a.x+a.width<=b.x||b.x+b.width<=a.x||a.y+a.height<=b.y||b.y+b.height<=a.y)}
 for(const n of l.nodes){const z=l.zones.find(z=>z.id===n.zoneId);assert.ok(n.x>=z.x&&n.y>=z.y&&n.x+n.width<=z.x+z.width&&n.y+n.height<=z.y+z.height)}
 for(const e of l.edges){const routed=route(e);assert.ok(routed?.path);for(const point of sample(routed.path)){assert.ok(!l.nodes.some(n=>point[0]>n.x+1&&point[0]<n.x+n.width-1&&point[1]>n.y+1&&point[1]<n.y+n.height-1),'line crosses a card: '+e.id)}}
});
function sample(path){const points=[];let p=[0,0];for(const m of path.matchAll(/([MLCQ])([^MLCQ]+)/g)){const v=m[2].trim().split(/[ ,]+/).map(Number),kind=m[1];if(kind==='M'){p=v;continue}const end=v.slice(-2),steps=Math.max(20,Math.ceil(Math.hypot(end[0]-p[0],end[1]-p[1])/5));for(let i=1;i<steps;i++){const t=i/steps,u=1-t;points.push(kind==='C'?[0,1].map(k=>u*u*u*p[k]+3*u*u*t*v[k]+3*u*t*t*v[k+2]+t*t*t*v[k+4]):kind==='Q'?[0,1].map(k=>u*u*p[k]+2*u*t*v[k]+t*t*v[k+2]):[0,1].map(k=>p[k]*u+end[k]*t))}p=end}return points}

test('expanded self calls use actual method ports without crossing the owning card',()=>{
 const g=structuredClone(fixture),c=g.classes.find(c=>c.id==='Fcm');c.methods=[{id:'Fcm.Send',name:'Send'},{id:'Fcm.Build',name:'Build'}];g.edges.push({...edge('self','Fcm','Fcm'),source:'Fcm.Send',target:'Fcm.Build'});
 const p=zoneView(buildArchitecture(g),'component/Notify',{expanded:true}),l=layoutZones(p.nodes.map(n=>({...n,width:286,height:108+n.c.methods.length*36,ports:{class:26,...Object.fromEntries(n.c.methods.map((m,i)=>[m.id,98+i*36]))}})),visualEdges(p.edges),p.zones);
 const e=l.edges.find(e=>e.originals.some(o=>o.id==='self')),r=createZoneRouter(l)(e);assert.match(r.sourceHandle,/Fcm.Send/);assert.match(r.targetHandle,/Fcm.Build/);assert.ok(r.path);
 for(const p of sample(r.path))assert.ok(!l.nodes.some(n=>p[0]>n.x+1&&p[0]<n.x+n.width-1&&p[1]>n.y+1&&p[1]<n.y+n.height-1));
});

test('inter-zone paths have continuous tangents instead of reversing at invisible hubs',()=>{
 const p=project(),l=layout(p),route=createZoneRouter(l);
 for(const e of l.edges.filter(e=>p.owner.get(e.source)!==p.owner.get(e.target))){
  const path=route(e).path;let at=[0,0],previous=null;
  for(const match of path.matchAll(/([MLCQ])([^MLCQ]+)/g)){
   const v=match[2].trim().split(/[ ,]+/).map(Number),kind=match[1];if(kind==='M'){at=v;continue}
   const end=v.slice(-2),first=kind==='L'?end:v.slice(0,2),last=kind==='L'?at:v.slice(-4,-2);
   const incoming=[first[0]-at[0],first[1]-at[1]],outgoing=[end[0]-last[0],end[1]-last[1]];
   if(previous&&Math.hypot(...incoming)>.001){const dot=(previous[0]*incoming[0]+previous[1]*incoming[1])/(Math.hypot(...previous)*Math.hypot(...incoming));assert.ok(dot>-.99,'reversed tangent at '+at+' in '+e.id)}
   if(Math.hypot(...outgoing)>.001)previous=outgoing;at=end;
  }
 }
});
