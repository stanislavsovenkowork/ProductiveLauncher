import test from 'node:test';import assert from 'node:assert/strict';import {mkdtemp,writeFile,readFile,rm,realpath} from 'node:fs/promises';import os from 'node:os';import path from 'node:path';import {execFileSync} from 'node:child_process';
const base=process.env.ATLAS_TEST_URL;if(!base)throw new Error('Set ATLAS_TEST_URL to an isolated test server');
const dotnet=process.env.ATLAS_DOTNET||path.join(os.homedir(),'.local/share/atlas-dotnet/dotnet');
const api=async(route,body)=>{const r=await fetch(base+'/api/'+route,body?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}:{});return{status:r.status,data:await r.json()}};
const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function waitVersion(version,predicate=()=>true){for(let i=0;i<45;i++){await delay(800);const {data}=await api('graph');if(data.version!==version&&predicate(data))return data}throw new Error('Graph did not refresh');}
test('Roslyn symbols, exact calls, live updates, Git and safe failure',async()=>{
 const dir=await realpath(await mkdtemp(path.join(os.tmpdir(),'atlas-engine-')));try{
 const project=path.join(dir,'Test.csproj');await writeFile(project,'<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><TargetFramework>net10.0</TargetFramework><ImplicitUsings>enable</ImplicitUsings></PropertyGroup></Project>');
 const code=`namespace Example;
 public interface IPay { void Pay(int amount); }
 public class PayService : IPay { public void Pay(int amount) { Write(amount); } public void Write(int amount) {} }
 public partial class Service {
 public void Run(IPay pay) { Overload(1); Overload("text"); pay.Pay(2); Helper(); }
 public void Overload(int value) { }
 public void Overload(string value) { }
 public void Helper() { }
 private class Detail { }
 }
 public class Other { public void Run() { } }
 `;
 await writeFile(path.join(dir,'Service.cs'),code);await writeFile(path.join(dir,'Partial.cs'),'namespace Example; public partial class Service { public void Extra() { Helper(); } }');
 execFileSync(dotnet,['restore',project,'--nologo'],{stdio:'pipe'});for(const args of [['init'],['config','user.email','atlas-test@example.invalid'],['config','user.name','Atlas test'],['add','Service.cs','Partial.cs','Test.csproj'],['commit','-m','baseline']])execFileSync('git',args,{cwd:dir,stdio:'pipe'});
 let {status,data:g}=await api('open',{path:project});assert.equal(status,200,JSON.stringify(g));assert.equal(g.diagnostics.filter(d=>d.severity==='error').length,0,JSON.stringify(g.diagnostics));
 assert.equal(g.projects.length,1);assert.equal(g.projects[0].path,'Test.csproj');const detail=g.classes.find(c=>c.name==='Detail');assert.ok(detail.containingTypeId);assert.equal(detail.accessibility,'Private');assert.ok(g.edges.some(e=>e.kind==='uses'&&e.targetClass===g.classes.find(c=>c.name==='IPay').id));
 const c=g.classes.find(c=>c.name==='Service');assert.equal(c.locations.length,2);assert.equal(c.methods.filter(m=>m.name==='Overload').length,2);assert.equal(new Set(c.methods.map(m=>m.id)).size,c.methods.length);const run=c.methods.find(m=>m.name==='Run');assert.equal(g.edges.filter(e=>e.source===run.id).length,4);assert.ok(g.edges.some(e=>e.source===run.id&&e.dispatch==='interface'));const payContract=g.classes.find(c=>c.name==='IPay').methods.find(m=>m.name==='Pay');const payImplementation=g.classes.find(c=>c.name==='PayService').methods.find(m=>m.name==='Pay');assert.ok(g.edges.some(e=>e.source===payContract.id&&e.target===payImplementation.id&&e.dispatch==='implementation'));assert.ok(run.code.includes('Overload("text")'));assert.equal(c.status,'existing');
 const orderedCalls=g.edges.filter(e=>e.source===run.id&&e.kind==='calls').sort((a,b)=>a.order-b.order);assert.deepEqual(orderedCalls.map(e=>g.classes.flatMap(c=>c.methods).find(m=>m.id===e.target)?.name),['Overload','Overload','Pay','Helper']);assert.ok(orderedCalls.every((e,i)=>Number.isInteger(e.order)&&(i===0||e.order>orderedCalls[i-1].order)));
 await writeFile(path.join(dir,'Service.cs'),code.replace('Helper(); }','Helper(); Added(); }').replace('public void Helper()','public void Added() { }\n public void Helper()'));
 g=await waitVersion(g.version,g=>g.classes.some(c=>c.methods.some(m=>m.name==='Added')));assert.equal(g.classes.find(c=>c.name==='Service').status,'changed');assert.equal(g.classes.find(c=>c.name==='Service').id,c.id);assert.ok(g.edges.some(e=>e.source===run.id&&e.target.includes('Added')));
 await writeFile(path.join(dir,'Fresh.cs'),'namespace Example; public class Fresh { public void Hello() {} }');g=await waitVersion(g.version,g=>g.classes.some(c=>c.name==='Fresh'));assert.equal(g.classes.find(c=>c.name==='Fresh').status,'new');
 await rm(path.join(dir,'Partial.cs'));g=await waitVersion(g.version,g=>g.git.deletedFiles.some(f=>f.endsWith('Partial.cs')));assert.equal(g.classes.find(c=>c.name==='Service').locations.length,1);
 const bad=await api('open',{path:path.join(dir,'missing.csproj')});assert.equal(bad.status,400);assert.equal((await api('graph')).data.projectPath,project);
 await api('view',{boards:[{id:'feature:test',name:'Test',classIds:[c.id]}],positions:{[c.id]:{x:20,y:30}}});assert.equal((await api('view')).data.boards[0].name,'Test');
 const cross=await fetch(base+'/api/open',{method:'POST',headers:{Origin:'https://untrusted.invalid','Content-Type':'application/json'},body:JSON.stringify({path:project})});assert.equal(cross.status,403);
 }finally{await rm(dir,{recursive:true,force:true})}
});
