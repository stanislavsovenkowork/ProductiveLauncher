import test from 'node:test';
import assert from 'node:assert/strict';
import {buildArchitecture,projectView,ancestors,projectShape,visualEdges} from '../src/architecture.js';
import {layoutVisible} from '../src/architecture-layout.js';
const project=(id,name,directory,isTest=false,references=[])=>({id,name,directory,path:directory+'/'+name+'.csproj',isTest,references,solutionFolders:[]});
const cls=(id,projectId,file,extra={})=>({id,name:id,fullName:id,projectId,project:projectId,group:'Services',locations:[{file:'/repo/'+file}],methods:[],...extra});
const edge=(id,sourceClass,targetClass,kind='calls')=>({id,source:id+'.source',target:id+'.target',sourceClass,targetClass,kind,dispatch:'static'});
const fixture={projectName:'Shop',root:'/repo',projectPath:'/repo/Shop.sln',projects:[project('app','Application','src/Application'),project('infra','Infrastructure','src/Infrastructure',false,['app']),project('history','History.Application','History.Application'),project('tests','Shop.Tests','tests',true,['infra'])],classes:[cls('Contract','app','src/Application/Services/Delivery/Contract.cs'),cls('Sender','infra','src/Infrastructure/Services/Delivery/Sender.cs'),cls('Helper','infra','src/Infrastructure/Services/Delivery/Sender.cs',{containingTypeId:'Sender'}),cls('Other','history','History.Application/Services/Other.cs'),cls('Test','tests','tests/Test.cs')],edges:[edge('one','Sender','Contract'),edge('two','Helper','Contract'),edge('three','Test','Sender'),edge('four','Other','Contract')]};
test('structural hierarchy separates layers, components and tests; nested helpers retain ownership',()=>{
 const m=buildArchitecture(fixture),root=m.groups.get('root');assert.deepEqual(root.children.map(id=>m.groups.get(id).label),['Shop','History','Tests']);
 assert.deepEqual(new Set(root.classIds),new Set(fixture.classes.map(c=>c.id)));
 assert.equal(m.owner.get('Helper'),m.owner.get('Sender'));assert.ok(![...m.groups.values()].some(g=>g.children.includes('Helper')));
 assert.notEqual(m.owner.get('Sender'),m.owner.get('Contract'));assert.deepEqual(ancestors(m,'Sender').map(g=>g.label),['Shop','Shop','Infrastructure','Delivery']);
 assert.match(m.groups.get(m.owner.get('Sender')).reason,/Delivery/);
});
test('overview aggregates exactly; drill-down preserves all crossing evidence and direction',()=>{
 const m=buildArchitecture(fixture),overview=projectView(m);assert.equal(overview.nodes.length,3);assert.equal(overview.edges.reduce((n,e)=>n+e.originals.filter(o=>o.kind!=='project').length,0),2);
 const local=projectView(m,m.owner.get('Sender'));const outgoing=local.edges.filter(e=>e.source==='Sender');assert.equal(outgoing.flatMap(e=>e.originals).length,2);assert.equal(outgoing[0].count,2);assert.ok(local.nodes.some(n=>n.kind==='context'));assert.ok(local.edges.some(e=>e.target==='Sender'));
});
test('same-project modules and vertical slices get actual folder boundaries',()=>{
 const g={...fixture,projects:[project('api','Api','src/Api')],classes:[cls('Invoice','api','src/Api/Modules/Billing/Domain/Invoice.cs'),cls('Notify','api','src/Api/Modules/Notifications/Handlers/Notify.cs')],edges:[]};const m=buildArchitecture(g);
 assert.ok(ancestors(m,'Invoice').some(g=>g.label==='Billing'));assert.ok(ancestors(m,'Notify').some(g=>g.label==='Notifications'));assert.notEqual(m.owner.get('Invoice'),m.owner.get('Notify'));
 assert.equal(projectShape(project('x','Billing.Domain','Modules/Billing/Domain'),'Shop').component,'Billing');
});
test('manual overrides survive reordering and maintain exact membership',()=>{
 const o={custom:[{id:'delivery',name:'Delivery module',classIds:['Sender']}],names:{}};const m=buildArchitecture(fixture,o);assert.ok(ancestors(m,'Sender').some(g=>g.label==='Delivery module'));assert.equal(m.owner.get('Helper'),m.owner.get('Sender'));const reversed=buildArchitecture({...fixture,classes:[...fixture.classes].reverse()},o);assert.deepEqual([...m.owner].sort(),[...reversed.owner].sort());
});
test('pagination leaves no unrepresented crossing links',()=>{
 const g={...fixture,projects:[project('p','P','P')],classes:Array.from({length:35},(_,i)=>cls('C'+i,'p','P/One.cs')),edges:[edge('last','C0','C34')]};const m=buildArchitecture(g);const v=projectView(m,m.owner.get('C0'));assert.equal(v.pages,2);assert.ok(v.nodes.some(n=>n.kind==='context'));assert.equal(v.edges.flatMap(e=>e.originals).length,1);
});
test('layout preserves coordinates on an edge-only change and never overlaps a new node',async()=>{
 const nodes=[{id:'a',width:270,height:174},{id:'b',width:270,height:174}];const links=[{id:'ab',source:'a',target:'b'}];const a=await layoutVisible(nodes,links);const old=Object.fromEntries(a.nodes.map(n=>[n.id,n]));const b=await layoutVisible([...nodes,{id:'c',width:270,height:174}],links,old);
 for(const n of a.nodes){assert.equal(b.nodes.find(x=>x.id===n.id).x,n.x);assert.equal(b.nodes.find(x=>x.id===n.id).y,n.y)}
 for(let i=0;i<b.nodes.length;i++)for(let j=i+1;j<b.nodes.length;j++){const p=b.nodes[i],q=b.nodes[j];assert.ok(p.x+p.width<=q.x||q.x+q.width<=p.x||p.y+p.height<=q.y||q.y+q.height<=p.y)}assert.ok(b.edges[0].path);assert.equal(b.unrouted,0);
});

test('visual aggregation conserves underlying relationship kinds and direction',()=>{const m=buildArchitecture(fixture);const v=projectView(m);const edges=visualEdges(v.edges);assert.equal(edges.flatMap(e=>e.originals).length,v.edges.flatMap(e=>e.originals).length);for(const e of edges)assert.equal(e.count,e.originals.length);assert.ok(visualEdges(v.edges,'calls').every(e=>e.originals.every(o=>o.kind==='calls')))});

test('a manual nested-type override moves ownership without duplicating the overview',()=>{const m=buildArchitecture(fixture,{custom:[{id:'helper',name:'Helper area',classIds:['Helper']}]});assert.notEqual(m.owner.get('Helper'),m.owner.get('Sender'));const ids=projectView(m).nodes.flatMap(n=>n.classIds);assert.equal(ids.filter(id=>id==='Helper').length,1);assert.equal(new Set(ids).size,fixture.classes.length)});
