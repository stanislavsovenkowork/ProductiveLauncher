export function routeConnection(start,end,boxes,sourceSide,targetSide,index){
 const gap=20+(index%4)*6;
 const vertical=sourceSide===targetSide;
 const clearCurve=(c1,c2)=>{const steps=Math.ceil((Math.abs(end[0]-start[0])+Math.abs(end[1]-start[1])+400)/3);for(let i=1;i<steps;i++){const t=i/steps,u=1-t,x=u*u*u*start[0]+3*u*u*t*c1[0]+3*u*t*t*c2[0]+t*t*t*end[0],y=u*u*u*start[1]+3*u*u*t*c1[1]+3*u*t*t*c2[1]+t*t*t*end[1];if(boxes.some(r=>x>r.left+.25&&x<r.right-.25&&y>r.top+.25&&y<r.bottom-.25))return false}return true};
 const reach=vertical?Math.min(155,65+Math.abs(end[1]-start[1])*.24):Math.max(65,Math.abs(end[0]-start[0])*.48);
 for(const bend of [reach,reach*.7,reach*1.35,60,220,340]){const c1=[start[0]+sourceSide*bend,start[1]],c2=[end[0]+targetSide*bend,end[1]];if(clearCurve(c1,c2))return `M${start.join(' ')} C${c1.join(' ')},${c2.join(' ')},${end.join(' ')}`;}
 const entry=[start[0]+sourceSide*gap,start[1]],exit=[end[0]+targetSide*gap,end[1]];
 const obstacles=boxes.map(r=>({left:r.left-gap,right:r.right+gap,top:r.top-gap,bottom:r.bottom+gap}));
 const xs=[...new Set([entry[0],exit[0],...obstacles.flatMap(r=>[r.left,r.right])])].sort((a,b)=>a-b);
 const ys=[...new Set([entry[1],exit[1],...obstacles.flatMap(r=>[r.top,r.bottom])])].sort((a,b)=>a-b);
 const blocked=(p,q)=>obstacles.some(r=>p[0]===q[0]?p[0]>r.left&&p[0]<r.right&&Math.max(p[1],q[1])>r.top&&Math.min(p[1],q[1])<r.bottom:p[1]>r.top&&p[1]<r.bottom&&Math.max(p[0],q[0])>r.left&&Math.min(p[0],q[0])<r.right);
 const width=xs.length,point=n=>[xs[n%width],ys[Math.floor(n/width)]],node=p=>ys.indexOf(p[1])*width+xs.indexOf(p[0]);
 const begin=node(entry),finish=node(exit),queue=[{n:begin,dir:0,cost:0,key:begin*2}],distance=new Map([[begin*2,0]]),previous=new Map();let last=null;
 while(queue.length){queue.sort((a,b)=>b.cost-a.cost);const current=queue.pop();if(current.cost!==distance.get(current.key))continue;if(current.n===finish){last=current.key;break}const x=current.n%width,y=Math.floor(current.n/width);
 for(const [dx,dy,dir] of [[-1,0,0],[1,0,0],[0,-1,1],[0,1,1]]){const nx=x+dx,ny=y+dy;if(nx<0||nx>=width||ny<0||ny>=ys.length)continue;const next=ny*width+nx,p=point(current.n),q=point(next);if(blocked(p,q))continue;const cost=current.cost+Math.abs(p[0]-q[0])+Math.abs(p[1]-q[1])+(dir!==current.dir?24:0),key=next*2+dir;if(cost>=(distance.get(key)??Infinity))continue;distance.set(key,cost);previous.set(key,current.key);queue.push({n:next,dir,cost,key})}}
 if(last===null)return ''; // Never fall back to a line through an obstacle.
 const points=[];for(let key=last;key!==undefined;key=previous.get(key))points.push(point(Math.floor(key/2)));points.reverse();points.unshift(start);points.push(end);
 const compact=[];for(const p of points){if(compact.length&&p[0]===compact.at(-1)[0]&&p[1]===compact.at(-1)[1])continue;while(compact.length>1){const a=compact.at(-2),b=compact.at(-1);if((a[0]===b[0]&&b[0]===p[0])||(a[1]===b[1]&&b[1]===p[1]))compact.pop();else break}compact.push(p)}
 let path='M'+compact[0].join(' ');
 for(let i=1;i<compact.length-1;i++){const prev=compact[i-1],p=compact[i],next=compact[i+1],before=Math.hypot(p[0]-prev[0],p[1]-prev[1]),after=Math.hypot(next[0]-p[0],next[1]-p[1]),radius=Math.min(gap-3,before/2,after/2);const enter=[p[0]+(prev[0]-p[0])*radius/before,p[1]+(prev[1]-p[1])*radius/before],leave=[p[0]+(next[0]-p[0])*radius/after,p[1]+(next[1]-p[1])*radius/after];path+=` L${enter.join(' ')} Q${p.join(' ')} ${leave.join(' ')}`;}
 return path+' L'+compact.at(-1).join(' ');
}