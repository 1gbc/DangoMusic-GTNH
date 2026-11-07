'use strict';
const http=require('node:http');
const crypto=require('node:crypto');
const readline=require('node:readline');
const random=()=>crypto.randomBytes(32).toString('hex');
const extensionOrigin=/^chrome-extension:\/\/[a-p]{32}$/;
const names=new Set(['play','pause','stop','next','previous','seek','volume']);
function sanitize(input={}) {
  const text=(v,max=300)=>typeof v==='string'?v.slice(0,max):'';
  const number=v=>Number.isFinite(v)?Math.max(0,Math.min(v,86400000)):0;
  let cover='';
  try {const u=new URL(input.cover); if(u.protocol==='https:' && /(^|\.)mzstatic\.com$/.test(u.hostname)) cover=u.href.slice(0,2048);}catch{}
  return {title:text(input.title),artist:text(input.artist),album:text(input.album),id:text(input.id,100),cover,
    playing:input.playing===true,positionMs:number(input.positionMs),durationMs:number(input.durationMs),
    available:input.available===true,error:text(input.error),capabilities:Array.isArray(input.capabilities)?input.capabilities.filter(x=>names.has(x)):[]};
}
async function createBridge() {
  let pairing=random(),born=Date.now(),session=null,owner=null,lastSync=0,state=null,seq=0,queue=[],stopped=false,lastAck=0;
  const server=http.createServer(async(req,res)=>{
    const send=(code,data)=>{res.writeHead(code,{'Content-Type':'application/json','Cache-Control':'no-store'});res.end(JSON.stringify(data));};
    const origin=req.headers.origin;
    if(req.headers.host!==new URL(api.url).host || !extensionOrigin.test(origin||'')) return send(403,{error:'Rejected origin or host'});
    if(req.method!=='POST' || !['/pair','/sync'].includes(req.url)) return send(405,{error:'POST required'});
    const bearer=req.headers.authorization;
    if(req.url==='/pair') {
      if(session || Date.now()-born>120000 || bearer!=='Bearer '+pairing) return send(403,{error:'Pairing expired or used'});
      session=random(); owner=origin;pairing=null;lastSync=Date.now();
      return send(200,{session,ack:seq});
    }
    if(!session || Date.now()-lastSync>10000 || owner!==origin || bearer!=='Bearer '+session) return send(403,{error:'Session expired; pair again'});
    if(!req.headers['content-type']?.startsWith('application/json')) return send(415,{error:'JSON required'});
    try {
      let bytes=0,chunks=[];
      for await(const chunk of req){bytes+=chunk.length;if(bytes>16384){send(413,{error:'Report too large'});req.destroy();return;}chunks.push(chunk);}
      const report=JSON.parse(Buffer.concat(chunks).toString('utf8'));
      if(!Number.isSafeInteger(report.ack)||report.ack<lastAck||report.ack>seq) return send(400,{error:'Invalid acknowledgement'});
      lastSync=Date.now();lastAck=report.ack;
      queue=queue.filter(x=>x.id>lastAck);
      if(!stopped && lastAck===seq) state=sanitize(report.state);
      if(typeof report.commandError==='string' && report.commandError) state={...state,error:report.commandError.slice(0,300)};
      send(200,{command:queue[0]||null});
    }catch{if(!res.headersSent)send(400,{error:'Invalid report'});}
  });
  server.requestTimeout=3000;server.headersTimeout=3000;server.maxHeadersCount=20;
  const api={url:'',get pairing(){return pairing;},status(){return {connected:!!session&&Date.now()-lastSync<=10000,stopped,state:Date.now()-lastSync<=10000?state:null,pending:queue.length};},
    command(name,value){
      if(!names.has(name))throw Error('Unsupported command');
      if(name==='seek'&&(!Number.isFinite(value)||value<0||value>86400))throw Error('Seek must be 0..86400 seconds');
      if(name==='volume'&&(!Number.isFinite(value)||value<0||value>1))throw Error('Volume must be 0..1');
      if(!session||Date.now()-lastSync>10000)throw Error('Browser disconnected; pair again');
      if(name==='stop'){stopped=true;state=null;queue=[];}
      if(['play','next','previous'].includes(name))stopped=false;
      if(queue.length>=16)throw Error('Wait for browser to acknowledge pending controls');
      queue.push({id:++seq,name,value});
    },disconnect(){session=null;owner=null;state=null;queue=[];stopped=true;pairing=random();born=Date.now();lastAck=seq;},
    async close(){await new Promise(resolve=>{server.close(resolve);server.closeAllConnections();});}};
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  api.url='http://127.0.0.1:'+server.address().port;
  return api;
}
module.exports={createBridge,sanitize};
if(require.main===module)(async()=>{
  const bridge=await createBridge();
  function showPair(){console.log('Pair in extension (expires in 2 minutes):\n'+bridge.url+'/#'+bridge.pairing);}
  showPair();console.log('Commands: status, play, pause, stop, next, previous, seek <seconds>, volume <0..1>, reconnect, quit');
  const rl=readline.createInterface({input:process.stdin,output:process.stdout});
  rl.on('line',line=>{const [name,value]=line.trim().split(/\s+/);try{
    if(name==='status')console.log(JSON.stringify(bridge.status()));
    else if(name==='reconnect'){bridge.disconnect();showPair();}
    else if(name==='quit')rl.close();
    else bridge.command(name,value===undefined?undefined:Number(value));
  }catch(e){console.log(e.message);}});
  rl.on('close',async()=>{await bridge.close();});
})().catch(()=>{console.error('Bridge startup failed');process.exitCode=1;});
