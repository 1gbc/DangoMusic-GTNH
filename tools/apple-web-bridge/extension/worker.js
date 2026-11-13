importScripts('adapter.js');
let pending=Promise.resolve(),ticking=false;
const official=url=>{try{return new URL(url).origin==='https://music.apple.com';}catch{return false;}};
async function run(tabId,command) {
  const result=await chrome.scripting.executeScript({target:{tabId},world:'MAIN',func:appleAdapter,args:[command||null]});
  return result[0]?.result||{available:false,error:'No player response'};
}
async function post(config,path,key,data){
  const response=await fetch(config.url+path,{method:'POST',headers:{Authorization:'Bearer '+key,'Content-Type':'application/json'},body:JSON.stringify(data),signal:AbortSignal.timeout(2500)});
  if(!response.ok)throw Error('Local session rejected; reconnect and pair again');
  return response.json();
}
chrome.runtime.onMessage.addListener((message,sender,reply)=>{
  // Serialize pairing/disconnect with in-flight telemetry and commands. Old sync
  // completion must never restore a session that the user just disconnected.
  const tick=message.type==='tick';
  if(tick&&ticking){reply({});return false;}
  if(tick)ticking=true;
  const work=pending.then(async()=>{
    if(message.type==='pair'&&!sender.tab){
      const url=new URL(message.link);
      if(url.protocol!=='http:'||url.hostname!=='127.0.0.1'||url.pathname!=='/'||url.search||url.username||url.password||!/^#[a-f0-9]{64}$/.test(url.hash))throw Error('Paste the local pairing link from bridge console');
      const [tab]=await chrome.tabs.query({active:true,currentWindow:true});
      if(!tab?.id||!official(tab.url))throw Error('Select the official Apple Music tab first');
      const prior=(await chrome.storage.session.get('bridge')).bridge;
      if(prior){try{await run(prior.tabId,{name:'pause'});}catch{}}
      const config={url:url.origin,tabId:tab.id};
      const pair=await post(config,'/pair',url.hash.slice(1),{});
      await chrome.storage.session.set({bridge:{...config,key:pair.session,ack:pair.ack,lastOk:Date.now(),error:''}});
      return {ok:true};
    }
    if(message.type==='status'&&!sender.tab){const c=(await chrome.storage.session.get('bridge')).bridge;return {paired:!!c,error:c?.error||'',lastOk:c?.lastOk||0};}
    if(message.type==='disconnect'&&!sender.tab){const c=(await chrome.storage.session.get('bridge')).bridge; if(c){try{await run(c.tabId,{name:'pause'});}catch{}}await chrome.storage.session.remove('bridge');return {ok:true};}
    if(message.type!=='tick'||!sender.tab||!official(sender.tab.url))return {};
    const config=(await chrome.storage.session.get('bridge')).bridge;
    if(!config||config.tabId!==sender.tab.id)return {};
    try{
      const state=await run(config.tabId);
      const response=await post(config,'/sync',config.key,{ack:config.ack,state,commandError:config.commandError||'',commandResult:config.commandResult||null});
      config.commandResult=null;
      config.commandError='';
      if(response.command&&response.command.id>config.ack){
        try{const outcome=await run(config.tabId,response.command);if(response.command.name==='query')config.commandResult={...outcome,id:response.command.id};else if(outcome.error)config.commandError=outcome.error;}
        catch{const error='Official player rejected control: '+response.command.name;if(response.command.name==='query')config.commandResult={id:response.command.id,error};else config.commandError=error;}
        config.ack=response.command.id;
      }
      config.lastOk=Date.now();config.error=state.error||config.commandError||'';
      await chrome.storage.session.set({bridge:config});
    }catch{
      config.error='Bridge disconnected; restart console and pair again';
      if(Date.now()-config.lastOk>10000){try{await run(config.tabId,{name:'pause'});}catch{}await chrome.storage.session.remove('bridge');}
      else await chrome.storage.session.set({bridge:config});
    }
    return {};
  });
  pending=work.catch(()=>{});
  work.then(reply).catch(()=>reply({error:'Connection failed. Check the official tab, local console and pairing link.'})).finally(()=>{if(tick)ticking=false;});
  return true;
});
