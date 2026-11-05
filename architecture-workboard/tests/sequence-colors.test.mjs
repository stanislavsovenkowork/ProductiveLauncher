import test from 'node:test';
import assert from 'node:assert/strict';
import {sequenceColor,sequenceStepForEdge} from '../src/sequence-colors.js';

test('an execution arrow follows the target method, ignoring unrelated bundled calls',()=>{
 const originals=[{id:'other',source:'a',target:'missing'},{id:'call',source:'a',target:'b'}];
 const directions=new Map([['call','sequence']]);
 const steps=new Map([['a',1],['b',7]]);
 assert.equal(sequenceStepForEdge(originals,directions,steps),7);
 assert.equal(sequenceStepForEdge(originals,new Map(),steps),null);
});

test('short and long traces follow one continuous warm-to-dark direction',()=>{
 const brightness=hex=>{const [r,g,b]=[1,3,5].map(offset=>parseInt(hex.slice(offset,offset+2),16));return .2126*r+.7152*g+.0722*b};
 for(const length of [12,56]){
  const colours=Array.from({length},(_,index)=>sequenceColor(index+1,length));
  assert.equal(colours[0],'#fff1d2');
  assert.equal(colours.at(-1),'#a93e3b');
  for(let index=1;index<colours.length;index++){
   assert.notEqual(colours[index],colours[index-1],`steps ${index} and ${index+1}`);
   assert.ok(brightness(colours[index])<brightness(colours[index-1]),`brightness at step ${index+1}`);
   const [r,g,b]=[1,3,5].map(offset=>parseInt(colours[index].slice(offset,offset+2),16));
   assert.ok(r>=g&&g>=b,`step ${index+1} remains in the warm range`);
  }
 }
});
