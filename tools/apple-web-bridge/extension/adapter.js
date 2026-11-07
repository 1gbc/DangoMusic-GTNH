// This function is serialized by chrome.scripting; keep it self-contained.
async function appleAdapter(command) {
  if(location.origin!=='https://music.apple.com')throw Error('Not the official Apple Music page');
  let player;
  try{player=globalThis.MusicKit?.getInstance();}catch{}
  if(!player)return {available:false,error:'Official page exposes no compatible player. Adapter needs verification.'};
  async function sessionReadiness() {
    if(player.isAuthorized===true)return {authorized:true,authState:'ready'};
    const cached=globalThis.__dangoAppleAuth;
    if(cached&&Date.now()-cached.checkedAt<(cached.authorized?15000:3000))return cached;
    let result={authorized:false,authState:'unavailable',checkedAt:Date.now()};
    let timer;
    try {
      let read;
      if(typeof player.api?.music==='function')read=player.api.music('/v1/me/library/songs',{limit:1});
      else if(typeof player.api?.library?.songs==='function')read=player.api.library.songs({limit:1});
      if(read){
        const response=await Promise.race([read,new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('timeout')),2500);})]);
        const rows=Array.isArray(response)?response:response?.data?.data||response?.data;
        if(Array.isArray(rows)&&!response?.errors&&!response?.data?.errors)result={authorized:true,authState:'ready',checkedAt:Date.now()};
        else {
          result.authState='restoring';
          const shape=v=>Array.isArray(v)?'array':v===null?'null':typeof v;
          result.error='Apple Music authorization response [root='+shape(response)+' data='+shape(response?.data)+' nested='+shape(response?.data?.data)+']';
        }
      }
    } catch(e) {
      const code=Number(e?.status||e?.statusCode||e?.response?.status||e?.data?.status||e?.errors?.[0]?.status||0);
      const reason=e?.reason||e?.errorCode||e?.name;
      result.authState=[401,403].includes(code)||['AUTHORIZATION_ERROR','ACCESS_DENIED','UNAUTHORIZED_ERROR','TOKEN_EXPIRED'].includes(reason)?'login':'restoring';
      result.error=failure(e,'authorization').error;
    }
    finally {clearTimeout(timer);}
    globalThis.__dangoAppleAuth=result;return result;
  }
  const text=v=>typeof v==='string'?v.slice(0,300):'';
  const coverUrl=value=>{try{const u=new URL(typeof value==='string'?value.replace('{w}','256').replace('{h}','256'):'');return u.protocol==='https:'&&/(^|\.)mzstatic\.com$/.test(u.hostname)&&!u.username?u.href:'';}catch{return '';}};
  const idOk=v=>typeof v==='string'&&/^[A-Za-z0-9._:-]{1,100}$/.test(v);
  if(command?.name==='region') {
    if(typeof player.api?.music!=='function')return {storefront:''};
    try {
      const response=await player.api.music('/v1/me/storefront');
      const payload=response?.data?.data||response?.data||response;
      const id=Array.isArray(payload)?payload[0]?.id:'';
      if(!/^[a-z]{2}$/.test(id))return {storefront:'',aligned:false};
      // Public MusicKit setter: keep its normal storefront checks and the current
      // authorized session, rather than reloading the page and losing that session.
      player.storefrontId=id;
      return {storefront:id,aligned:player.storefrontId===id};
    }catch{return {storefront:''};}
  }
  const failure=(e,stage)=>{
    const raw=e?.errors?.[0]||e?.response?.data?.errors?.[0]||e;
    const status=Number(raw?.status||raw?.statusCode||e?.response?.status||0);
    const sdkReason=['CONTENT_EQUIVALENT','CONTENT_UNAVAILABLE','CONTENT_UNSUPPORTED','CONTENT_RESTRICTED','AUTHORIZATION_ERROR','ACCESS_DENIED','GEO_BLOCK','DEVICE_LIMIT','MEDIA_CERTIFICATE','MEDIA_DESCRIPTOR','MEDIA_LICENSE','MEDIA_KEY','MEDIA_PLAYBACK','MEDIA_SESSION','NETWORK_ERROR','NOT_FOUND','PARSE_ERROR','REQUEST_ERROR','SERVER_ERROR','SERVICE_UNAVAILABLE','STREAM_UPSELL','SUBSCRIPTION_ERROR','TOKEN_EXPIRED','UNAUTHORIZED_ERROR','UNKNOWN_ERROR','UNSUPPORTED_ERROR','USER_INTERACTION_REQUIRED','INTERNAL_ERROR','OUTPUT_RESTRICTED','WIDEVINE_CDM_EXPIRED','PLAYREADY_CBC_ENCRYPTION_ERROR']
      .find(reason=>reason===e?.reason||reason===e?.errorCode||reason===e?.name);
    const name=sdkReason||(['NotAllowedError','NotSupportedError','InvalidStateError','TypeError','SecurityError','AbortError','NetworkError','TimeoutError','MusicKitError','PlaybackError','MediaError'].includes(e?.name)?e.name:'SDK');
    const code=Number(e?.errorCode??e?.code??raw?.code);
    // Classify locally; never return exception text, which may contain URLs or tokens.
    const description=String(e?.message||e?.description||raw?.detail||'');
    const reason=/key.?system|drm|decrypt|license|widevine|fairplay|mediakeys/i.test(description)?'DRM':
      /user.?gesture|autoplay/i.test(description)?'GESTURE':
      /authori|subscription|membership|user.?token/i.test(description)?'AUTHORIZATION':
      /queue|media.?item|identifier|catalog/i.test(description)?'QUEUE':
      /browser|user.?agent|platform/i.test(description)?'PLATFORM':
      /network|fetch|request|cors/i.test(description)?'NETWORK':
      /codec|decode|format|source|media/i.test(description)?'MEDIA':'UNKNOWN';
    const diagnostic=(Number.isInteger(status)&&status>=100&&status<=599?'HTTP '+status:name)
      +(Number.isInteger(code)&&Math.abs(code)<100000000?' code='+code:'')+' '+reason;
    return {items:[],hasMore:false,error:'Apple Music '+stage+' failed ['+diagnostic+']'};
  };
  if(!globalThis.__dangoAppleMediaErrorHook&&typeof player.addEventListener==='function'&&MusicKit.Events?.mediaPlaybackError) {
    player.addEventListener(MusicKit.Events.mediaPlaybackError,event=>{
      const error=event?.error||event?.detail?.error||event?.detail||event;
      globalThis.__dangoAppleMediaError=failure(error,'media').error;
    });
    globalThis.__dangoAppleMediaErrorHook=true;
  }
  if(command?.name==='query') {
    const v=command.value||{},op=v.op,offset=Number(v.offset||0);
    if(!Number.isInteger(offset)||offset<0||offset>10000)throw Error('Invalid page');
    let storefront=/^[a-z]{2}$/.test(player.storefrontId)?player.storefrontId:'';
    const catalogOperation=['search','charts','resourceTracks'].includes(op);
    if(catalogOperation&&!storefront&&typeof player.api?.music==='function') {
      try {const region=await player.api.music('/v1/me/storefront');const payload=region?.data?.data||region?.data;const id=Array.isArray(payload)?payload[0]?.id:'';if(/^[a-z]{2}$/.test(id))storefront=id;}catch{}
    }
    if(catalogOperation&&!storefront)return {items:[],hasMore:false,error:'Apple Music region unavailable; sign in and retry'};
    const params={limit:25,offset};let path,legacy;
    if(op==='search') {
      if(typeof v.term!=='string'||!v.term.trim()||v.term.length>200)throw Error('Invalid search');
      path='/v1/catalog/'+storefront+'/search';params.term=v.term;params.types='songs';params.limit=25;
      legacy=()=>player.api.search(params);
    } else if(op==='charts') {path='/v1/catalog/'+storefront+'/charts';params.types='songs';legacy=()=>player.api.charts(params);}
    else if(op==='playlists') {path='/v1/me/library/playlists';legacy=()=>player.api.library.playlists(params);}
    else if(op==='songs') {path='/v1/me/library/songs';legacy=()=>player.api.library.songs(params);}
    else if(op==='recent') {
      path='/v1/me/recent/played';params.limit=10;
      params.types='albums,library-albums,playlists,library-playlists,stations';
    }
    else if(op==='recommend') {path='/v1/me/recommendations';}
    else if(op==='resourceTracks'&&idOk(v.id)) {
      const match=/^(albums|playlists|library-albums|library-playlists):([A-Za-z0-9._-]{1,100})$/.exec(v.id);
      if(!match)throw Error('Invalid card resource');
      path=(match[1].startsWith('library-')?'/v1/me/library/'+match[1].slice(8):'/v1/catalog/'+storefront+'/'+match[1])+'/'+encodeURIComponent(match[2])+'/tracks';
    }
    else if(op==='playlistTracks'&&idOk(v.id)) {path='/v1/me/library/playlists/'+v.id+'/tracks';legacy=()=>player.api.library.playlistRelationship(v.id,'tracks',params);}
    else throw Error('Unsupported browse operation');
    let response;
    try {
      response=typeof player.api?.music==='function'?await player.api.music(path,params):await legacy();
      if(response?.errors?.length||response?.data?.errors?.length)throw response.data?.errors?response.data:response;
    }
    catch(e) {const code=Number(e?.status||e?.statusCode||e?.response?.status||e?.errors?.[0]?.status||0);if([401,403].includes(code))globalThis.__dangoAppleAuth={authorized:false,authState:'login',checkedAt:Date.now()};return failure(e,op);}
    const data=response?.data&&!Array.isArray(response.data)?response.data:response;
    const page=op==='search'?(data?.results?.songs||data?.songs):op==='charts'?(Array.isArray(data?.results?.songs)?data.results.songs[0]:data?.results?.songs):data;
    const baseRows=Array.isArray(page)?page:page?.data||[];
    const rows=op==='recommend'?baseRows.flatMap(group=>group.relationships?.contents?.data||[]):baseRows;
    const cards=['recent','recommend','playlists'].includes(op);
    const kinds=cards?['albums','playlists','library-albums','library-playlists','stations']:['songs','library-songs'];
    const items=rows.filter(row=>kinds.includes(row.type)).slice(0,25).map(row=>{
      const a=row.attributes||{},catalog=a.playParams?.catalogId;
      return {id:text(row.id),kind:text(row.type),playId:text(catalog||a.playParams?.id||row.id),library:!catalog&&String(row.type).startsWith('library-'),
        title:text(a.name),artist:text(a.artistName),album:text(a.albumName),cover:coverUrl(a.artwork?.url),
        durationMs:Number.isFinite(a.durationInMillis)?a.durationInMillis:0};
    }).filter(row=>idOk(row.id));
    // Never invent a next page from a full batch: the final page may be full,
    // and filtered resources mean visible song count is not the API offset.
    let nextOffset=0;
    if(op!=='recommend'&&typeof page?.next==='string') {
      try {const next=new URL(page.next,'https://api.music.apple.com');const value=next.searchParams.get('offset');
        if(['api.music.apple.com','amp-api.music.apple.com'].includes(next.hostname)&&next.pathname===path&&/^\d{1,5}$/.test(value||''))nextOffset=Number(value);
      }catch{}
    }
    return {items,hasMore:nextOffset>offset&&nextOffset<=10000,nextOffset};
  }
  if(command?.name==='login') {
    const buttons=globalThis.document?.querySelectorAll?Array.from(document.querySelectorAll('button')):[];
    const signIn=buttons.find(button=>/^(登录|登入|Sign In|サインイン)$/i.test((button.textContent||button.getAttribute('aria-label')||'').trim())&&!button.disabled&&button.getClientRects().length);
    if(signIn){signIn.click();return {loginRoute:'website'};}
    if(player.isAuthorized!==true&&typeof player.authorize==='function')Promise.resolve(player.authorize()).catch(()=>{});
  } else if(command?.name==='load') {
    globalThis.__dangoAppleMediaError='';
    const v=command.value||{};if(!idOk(v.id)||typeof player.setQueue!=='function')throw Error('Song loading unavailable');
    const generation=(globalThis.__dangoAppleGeneration||0)+1;globalThis.__dangoAppleGeneration=generation;
    const session=await sessionReadiness();
    if(globalThis.__dangoAppleGeneration!==generation)return {cancelled:true};
    if(!session.authorized)return failure({reason:'UNAUTHORIZED_ERROR'},'authorization');
    // Use the public full-playback mode: never disguise a playable preview as
    // successful membership playback. The SDK/server still enforce entitlement.
    if(globalThis.MusicKit?.PlaybackMode?.FULL_PLAYBACK_ONLY!==undefined)
      player.playbackMode=MusicKit.PlaybackMode.FULL_PLAYBACK_ONLY;
    let descriptor=v.id.startsWith('station:')?{station:v.id.slice(8)}:{song:v.id};
    if(v.library===true) {
      descriptor={librarySong:v.id};
    }
    if(typeof player.autoplayEnabled==='boolean')player.autoplayEnabled=false;
    let stage='queue';
    try {
    globalThis.__dangoAppleLoadStage='queue';
    await player.setQueue(descriptor);
    if(globalThis.__dangoAppleGeneration!==generation)return {cancelled:true};
    stage='play';globalThis.__dangoAppleLoadStage='play';await player.play();
    globalThis.__dangoAppleLoadStage='done';
    } catch(e) {globalThis.__dangoAppleLoadStage='error';return failure(e,stage);}
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
  const readiness=await sessionReadiness();
  const item=player.nowPlayingItem, a=item?.attributes||{};
  const artwork=a.artwork?.url;
  let cover='';
  try{const u=new URL(typeof artwork==='string'?artwork.replace('{w}','256').replace('{h}','256'):'');if(u.protocol==='https:'&&/(^|\.)mzstatic\.com$/.test(u.hostname))cover=u.href;}catch{}
  const capabilities=Object.keys(methods).filter(k=>typeof player[methods[k]]==='function');
  if(typeof player.volume==='number')capabilities.push('volume');
  return {available:true,previewOnly:player.previewOnly===true,loadingStage:globalThis.__dangoAppleLoadStage||'',error:globalThis.__dangoAppleMediaError||readiness.error||'',authorized:readiness.authorized,authState:readiness.authState,ended:['completed','ended'].some(k=>MusicKit.PlaybackStates?.[k]!==undefined&&player.playbackState===MusicKit.PlaybackStates[k]),title:text(a.name||item?.title),artist:text(a.artistName||item?.artistName),album:text(a.albumName),id:text(item?.id),cover,
    playing:player.isPlaying===true,positionMs:Number.isFinite(player.currentPlaybackTime)?player.currentPlaybackTime*1000:0,
    durationMs:Number.isFinite(player.currentPlaybackDuration)?player.currentPlaybackDuration*1000:0,capabilities};
}
if(typeof module!=='undefined')module.exports={appleAdapter};
