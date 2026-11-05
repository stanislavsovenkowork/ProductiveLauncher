import test from 'node:test';
import assert from 'node:assert/strict';
import {buildPipeline,buildExecutionPipeline,pipelineCandidates} from '../src/pipelines.js';

const cls=(id,name)=>({id,name,methods:[{id:id+'.'+name,name}]});
const graph={classes:[cls('Health','Health'),cls('Probe','Probe'),cls('Store','Save'),cls('Other','Other')],edges:[
 {id:'a',kind:'calls',source:'Health.Health',target:'Probe.Probe'},
 {id:'b',kind:'calls',source:'Probe.Probe',target:'Store.Save'},
 {id:'c',kind:'calls',source:'Other.Other',target:'Store.Save'}
]};

test('pipeline follows concrete method calls and preserves method-level edge ids',()=>{
 const pipeline=buildPipeline(graph,'Health.Health');
 assert.deepEqual(pipeline.steps.map(step=>step.id),['Health.Health','Probe.Probe','Store.Save']);
 assert.deepEqual(pipeline.edgeIds,['a','b']);
});

test('pipeline menu promotes entry-point shaped methods',()=>assert.equal(pipelineCandidates(graph)[0].startId,'Health.Health'));

test('execution pipeline retains every concrete branch from the selected method',()=>{
 const branching={...graph,edges:[...graph.edges,{id:'d',kind:'calls',source:'Health.Health',target:'Other.Other'}]};
 const execution=buildExecutionPipeline(branching,'Health.Health');
 assert.deepEqual(execution.edgeIds,['d','c','a','b']);
 assert.deepEqual(execution.steps.map(step=>step.id),['Health.Health','Other.Other','Store.Save','Probe.Probe']);
 assert.equal(execution.execution,true);
});

test('execution expands direct calls before sibling branches in source order',()=>{
 const source={classes:[cls('Controller','Submit'),cls('Identity','GetCurrentUser'),cls('Users','FindUser'),cls('Checkout','PlaceOrder')],edges:[
  {id:'place',kind:'calls',source:'Controller.Submit',target:'Checkout.PlaceOrder',order:20},
  {id:'user',kind:'calls',source:'Controller.Submit',target:'Identity.GetCurrentUser',order:10},
  {id:'find',kind:'calls',source:'Identity.GetCurrentUser',target:'Users.FindUser',order:3}
 ]};
 const execution=buildExecutionPipeline(source,'Controller.Submit');
 assert.deepEqual(execution.steps.map(step=>step.id),['Controller.Submit','Identity.GetCurrentUser','Users.FindUser','Checkout.PlaceOrder']);
 assert.deepEqual(execution.edgeIds,['user','find','place']);
});

test('interface calls continue into a single concrete implementation',()=>{
 const source={classes:[
  {id:'Caller',name:'Caller',methods:[{id:'caller',name:'Run'}]},
  {id:'Contract',name:'IMetrics',kind:'interface',methods:[{id:'contract',name:'Record'}]},
  {id:'Metrics',name:'Metrics',methods:[{id:'concrete',name:'Record'},{id:'next',name:'Write'}]}
 ],edges:[
  {id:'call',kind:'calls',source:'caller',target:'contract',order:1},
  {id:'dispatch',kind:'calls',dispatch:'implementation',source:'contract',target:'concrete',targetClass:'Metrics'},
  {id:'body',kind:'calls',source:'concrete',target:'next',order:2}
 ]};
 const execution=buildExecutionPipeline(source,'caller');
 assert.deepEqual(execution.steps.map(step=>step.id),['caller','contract','concrete','next']);
 assert.deepEqual(execution.edgeIds,['call','dispatch','body']);
});

test('ambiguous interface call expands every implementation and can be narrowed by selection',()=>{
 const source={classes:[
  {id:'Caller',name:'Caller',methods:[{id:'caller',name:'Run'}]},
  {id:'Contract',name:'IWriter',kind:'interface',methods:[{id:'contract',name:'Write'}]},
  {id:'File',name:'FileWriter',methods:[{id:'file',name:'Write'}]},
  {id:'Cloud',name:'CloudWriter',methods:[{id:'cloud',name:'Write'}]}
 ],edges:[
  {id:'call',kind:'calls',source:'caller',target:'contract'},
  {id:'file-edge',kind:'calls',dispatch:'implementation',source:'contract',target:'file',targetClass:'File'},
  {id:'cloud-edge',kind:'calls',dispatch:'implementation',source:'contract',target:'cloud',targetClass:'Cloud'}
 ]};
 assert.deepEqual(buildExecutionPipeline(source,'caller').steps.map(step=>step.id),['caller','contract','cloud','file']);
 assert.deepEqual(buildExecutionPipeline(source,'caller',{implementationChoices:{contract:'cloud'}}).steps.map(step=>step.id),['caller','contract','cloud']);
});
