import ELK from 'elkjs/lib/elk-api.js';
import elkWorkerUrl from 'elkjs/lib/elk-worker.min.js?url';
const elk=new ELK({workerUrl:elkWorkerUrl});
import {layoutZones} from './zone-layout.js';
import {layoutVisible} from './architecture-layout.js';
self.onmessage=async({data})=>{try{const result=data.zones?layoutZones(data.nodes,data.edges,data.zones):await layoutVisible(data.nodes,data.edges,data.previous,data.direction,elk);self.postMessage({id:data.id,result})}catch(error){self.postMessage({id:data.id,error:error.message})}};
