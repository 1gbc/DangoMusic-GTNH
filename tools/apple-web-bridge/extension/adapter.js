// This function is serialized by chrome.scripting; keep it self-contained.
async function appleAdapter(command) {
  if(location.origin!=='https://music.apple.com')throw Error('Not the official Apple Music page');
  let player;
  try{player=globalThis.MusicKit?.getInstance();}catch{}
  if(!player)return {available:false,error:'Official page exposes no compatible player. Adapter needs verification.'};
  const methods={play:'play',pause:'pause',stop:'stop',next:'skipToNextItem',previous:'skipToPreviousItem',seek:'seekToTime'};
  if(command){
    if(command.name==='volume') {
      if(!Number.isFinite(command.value)||command.value<0||command.value>1)throw Error('Invalid volume');
      if(typeof player.volume!=='number')throw Error('Volume control unavailable');
      player.volume=command.value;
    }else{
      const method=methods[command.name];
      if(!method||typeof player[method]!=='function')throw Error('Control unavailable: '+command.name);
      if(command.name==='seek'&&(!Number.isFinite(command.value)||command.value<0||command.value>86400))throw Error('Invalid seek');
      let timer;
      try {
        await Promise.race([
          player[method](...(command.name==='seek'?[command.value]:[])),
          new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('Player control timed out')),4000);})
        ]);
      } finally {clearTimeout(timer);}
    }
  }
  const item=player.nowPlayingItem, a=item?.attributes||{};
  const artwork=a.artwork?.url;
  let cover='';
  try{const u=new URL(typeof artwork==='string'?artwork.replace('{w}','256').replace('{h}','256'):'');if(u.protocol==='https:'&&/(^|\.)mzstatic\.com$/.test(u.hostname))cover=u.href;}catch{}
  const text=v=>typeof v==='string'?v.slice(0,300):'';
  const capabilities=Object.keys(methods).filter(k=>typeof player[methods[k]]==='function');
  if(typeof player.volume==='number')capabilities.push('volume');
  return {available:true,title:text(a.name||item?.title),artist:text(a.artistName||item?.artistName),album:text(a.albumName),id:text(item?.id),cover,
    playing:player.isPlaying===true,positionMs:Number.isFinite(player.currentPlaybackTime)?player.currentPlaybackTime*1000:0,
    durationMs:Number.isFinite(player.currentPlaybackDuration)?player.currentPlaybackDuration*1000:0,capabilities};
}
if(typeof module!=='undefined')module.exports={appleAdapter};
