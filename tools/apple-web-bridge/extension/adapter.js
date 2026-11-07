// This function is serialized by chrome.scripting; keep it self-contained.
async function appleAdapter(command) {
  if(location.origin!=='https://music.apple.com')throw Error('Not the official Apple Music page');
  let player;
  try{player=globalThis.MusicKit?.getInstance();}catch{}
  if(!player)return {available:false,error:'Official page exposes no compatible player. Adapter needs verification.'};
  const text=v=>typeof v==='string'?v.slice(0,300):'';
  const coverUrl=value=>{try{const u=new URL(typeof value==='string'?value.replace('{w}','256').replace('{h}','256'):'');return u.protocol==='https:'&&/(^|\.)mzstatic\.com$/.test(u.hostname)&&!u.username?u.href:'';}catch{return '';}};
  const idOk=v=>typeof v==='string'&&/^[A-Za-z0-9._:-]{1,100}$/.test(v);
  if(command?.name==='query') {
    const v=command.value||{},op=v.op,offset=Number(v.offset||0);
    if(!Number.isInteger(offset)||offset<0||offset>10000)throw Error('Invalid page');
    let storefront=/^[a-z]{2}$/.test(player.storefrontId)?player.storefrontId:'';
    if(op==='search'&&!storefront&&typeof player.api?.music==='function') {
      try {const region=await player.api.music('/v1/me/storefront');const payload=region?.data?.data||region?.data;const id=Array.isArray(payload)?payload[0]?.id:'';if(/^[a-z]{2}$/.test(id))storefront=id;}catch{}
    }
    if(op==='search'&&!storefront)return {items:[],hasMore:false,error:'Apple Music region unavailable; sign in and retry'};
    const params={limit:25,offset};let path,legacy;
    if(op==='search') {
      if(typeof v.term!=='string'||!v.term.trim()||v.term.length>200)throw Error('Invalid search');
      path='/v1/catalog/'+storefront+'/search';params.term=v.term;params.types='songs';params.limit=25;
      legacy=()=>player.api.search(params);
    } else if(op==='playlists') {path='/v1/me/library/playlists';legacy=()=>player.api.library.playlists(params);}
    else if(op==='songs') {path='/v1/me/library/songs';legacy=()=>player.api.library.songs(params);}
    else if(op==='playlistTracks'&&idOk(v.id)) {path='/v1/me/library/playlists/'+v.id+'/tracks';legacy=()=>player.api.library.playlistRelationship(v.id,'tracks',params);}
    else throw Error('Unsupported browse operation');
    let response;
    try {response=typeof player.api?.music==='function'?await player.api.music(path,params):await legacy();}
    catch {return {items:[],hasMore:false,error:'Apple Music request failed; check login, region and connection'};}
    const data=response?.data&&!Array.isArray(response.data)?response.data:response;
    const page=op==='search'?(data?.results?.songs||data?.songs):data;
    const rows=Array.isArray(page)?page:page?.data||[];
    const items=rows.slice(0,25).filter(row=>op==='playlists'||['songs','library-songs'].includes(row.type)).map(row=>{
      const a=row.attributes||{},catalog=a.playParams?.catalogId;
      return {id:text(row.id),playId:text(catalog||a.playParams?.id||row.id),library:!catalog&&String(row.type).startsWith('library-'),
        title:text(a.name),artist:text(a.artistName),album:text(a.albumName),cover:coverUrl(a.artwork?.url),
        durationMs:Number.isFinite(a.durationInMillis)?a.durationInMillis:0};
    }).filter(row=>idOk(row.id));
    return {items,hasMore:!!page?.next||rows.length===params.limit,nextOffset:offset+rows.length};
  }
  if(command?.name==='login') {
    if(player.isAuthorized!==true&&typeof player.authorize==='function')Promise.resolve(player.authorize()).catch(()=>{});
  } else if(command?.name==='load') {
    const v=command.value||{};if(!idOk(v.id)||typeof player.setQueue!=='function')throw Error('Song loading unavailable');
    const generation=(globalThis.__dangoAppleGeneration||0)+1;globalThis.__dangoAppleGeneration=generation;
    let descriptor={song:v.id};
    if(v.library===true) {
      if(typeof MusicKit.MediaItem!=='function')throw Error('Library playback unavailable');
      descriptor={items:[new MusicKit.MediaItem({id:v.id,type:'library-songs',attributes:{playParams:{id:v.id,kind:'song',isLibrary:true}}})]};
    }
    if(typeof player.autoplayEnabled==='boolean')player.autoplayEnabled=false;
    await player.setQueue(descriptor);
    if(globalThis.__dangoAppleGeneration!==generation)return {cancelled:true};
    await player.play();
  }
  const methods={play:'play',pause:'pause',stop:'stop',next:'skipToNextItem',previous:'skipToPreviousItem',seek:'seekToTime'};
  if(command && !['query','load','login'].includes(command.name)){
    if(['stop','pause'].includes(command.name))globalThis.__dangoAppleGeneration=(globalThis.__dangoAppleGeneration||0)+1;
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
  const capabilities=Object.keys(methods).filter(k=>typeof player[methods[k]]==='function');
  if(typeof player.volume==='number')capabilities.push('volume');
  return {available:true,authorized:player.isAuthorized===true,ended:['completed','ended'].some(k=>MusicKit.PlaybackStates?.[k]!==undefined&&player.playbackState===MusicKit.PlaybackStates[k]),title:text(a.name||item?.title),artist:text(a.artistName||item?.artistName),album:text(a.albumName),id:text(item?.id),cover,
    playing:player.isPlaying===true,positionMs:Number.isFinite(player.currentPlaybackTime)?player.currentPlaybackTime*1000:0,
    durationMs:Number.isFinite(player.currentPlaybackDuration)?player.currentPlaybackDuration*1000:0,capabilities};
}
if(typeof module!=='undefined')module.exports={appleAdapter};
