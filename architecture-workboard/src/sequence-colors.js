// A single warm, progressively darker scale. Its ends stay readable against
// the canvas, and the entire range is used regardless of pipeline length.
const stops=['#fff1d2','#ffdc81','#ffc44b','#ffa23b','#f87c34','#eb5c32','#d94b36','#c34639','#a93e3b'];

export function sequenceColor(step,totalSteps=12){
 const length=Math.max(2,totalSteps);
 const progress=Math.max(0,Math.min(1,(step-1)/(length-1)));
 const position=progress*(stops.length-1),left=Math.floor(position),right=Math.min(stops.length-1,left+1),mix=position-left;
 const first=stops[left],last=stops[right];
 const channel=offset=>Math.round(parseInt(first.slice(offset,offset+2),16)*(1-mix)+parseInt(last.slice(offset,offset+2),16)*mix).toString(16).padStart(2,'0');
 return '#'+channel(1)+channel(3)+channel(5);
}

export function sequenceStepForEdge(originals,directions,steps){
 const selected=originals.filter(edge=>directions.get(edge.id)==='sequence');
 const targets=selected.map(edge=>steps.get(edge.target)).filter(Number.isFinite);
 return targets.length?Math.min(...targets):null;
}
