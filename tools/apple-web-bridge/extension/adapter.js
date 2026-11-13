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
  const coverUrl=(value,size='256')=>{try{const u=new URL(typeof value==='string'?value.replace('{w}',size).replace('{h}',size).replace('{c}','bb').replace('{f}','jpg'):'');return u.protocol==='https:'&&/(^|\.)mzstatic\.com$/.test(u.hostname)&&!u.username?u.href:'';}catch{return '';}};
  const idOk=v=>typeof v==='string'&&/^[A-Za-z0-9._:-]{1,100}$/.test(v);
  const isEnded=state=>['ended','completed'].some(k=>MusicKit.PlaybackStates?.[k]!==undefined&&state===MusicKit.PlaybackStates[k]);
  if(globalThis.__dangoAppleCompletion?.player!==player) {
    const completion=globalThis.__dangoAppleCompletion={player,id:''};
    if(typeof player.addEventListener==='function'&&MusicKit.Events?.playbackStateDidChange) {
      player.addEventListener(MusicKit.Events.playbackStateDidChange,event=>{
        if(isEnded(event.state)) {
          // MusicKit clears nowPlayingItem before publishing the end state.
          const id=event.nowPlayingItem?.id||player.nowPlayingItem?.id;
          if(idOk(id))completion.id=id;
        } else if(event.state===MusicKit.PlaybackStates?.playing) {
          completion.id='';
        }
      });
    }
  }
  const completion=globalThis.__dangoAppleCompletion;
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
    const catalogOperation=['search','charts','resourceTracks','discover','genres','genreCharts','liveRadio','webDiscover','webRadio','favoriteState','favoriteAdd','favoriteRemove'].includes(op);
    if(catalogOperation&&!storefront&&typeof player.api?.music==='function') {
      try {const region=await player.api.music('/v1/me/storefront');const payload=region?.data?.data||region?.data;const id=Array.isArray(payload)?payload[0]?.id:'';if(/^[a-z]{2}$/.test(id))storefront=id;}catch{}
    }
    if(catalogOperation&&!storefront)return {items:[],hasMore:false,error:'Apple Music region unavailable; sign in and retry'};
    if(op==='webDiscover'||op==='webRadio') {
      const route=op==='webDiscover'?'new':'radio';
      const locale=/^[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/.test(document.documentElement.lang)?document.documentElement.lang:'';
      const key=storefront+'/'+route+'?l='+locale;
      const cache=globalThis.__dangoEditorialCache||(globalThis.__dangoEditorialCache=new Map());
      try {
        let snapshot=cache.get(key);
        if(!snapshot||Date.now()-snapshot.loadedAt>300000){
          const response=await fetch('/'+key,{credentials:'omit',signal:AbortSignal.timeout(5000)});
          if(!response.ok)throw {status:response.status};
          const html=await response.text();
          if(html.length>5000000)throw Error('Editorial page too large');
          const data=/<script\b[^>]*\bid="serialized-server-data"[^>]*>([\s\S]*?)<\/script>/i.exec(html);
          const sections=data&&JSON.parse(data[1]).data?.find(entry=>Array.isArray(entry.data?.sections))?.data?.sections;
          if(!sections?.length)throw Error('Editorial sections missing');
          const items=[];
          const layouts={flowcaseLockup:'hero',trackLockup:'tracks',horizontalLockup:'rows',
            squareLockup:'squares',liveRadioGridLockup:'squares',verticalVideoLockup:'squares',linkComponentModel:'links'};
          const types={album:'albums',playlist:'playlists',song:'songs',radioStation:'stations'};
          for(const [sectionIndex,section] of sections.slice(0,30).entries()){
            const layout=layouts[section.itemKind];
            if(!layout||!Array.isArray(section.items))continue;
            for(const item of section.items.slice(0,25)){
              const descriptor=item.contentDescriptor||item.segue?.destination?.contentDescriptor||
                item.segue?.destination?.intent?.contentDescriptor||item.titleLinks?.[0]?.segue?.destination?.contentDescriptor;
              const title=text(item.title||item.titleLinks?.[0]?.title);
              if(!title||item.isDisabled===true)continue;
              const rawId=descriptor?.identifiers?.storeAdamID;
              const kind=types[descriptor?.kind];
              const playable=kind&&idOk(rawId);
              let link='';
              try {
                const url=new URL(descriptor?.url||item.url,location.origin);
                if(url.origin===location.origin&&url.pathname.startsWith('/'+storefront+'/')&&!url.username&&!url.password)
                  link=url.href.slice(0,700);
              }catch{}
              if(!playable&&!link)continue;
              const crop=/^[a-z]{1,4}$/.test(item.artwork?.cropStyle)?item.artwork.cropStyle:'bb';
              const artwork=item.artwork?.dictionary?.url?.replace('{c}',crop).replace('{f}','jpg');
              items.push({
                id:playable?rawId:'web-'+items.length,kind:playable?kind:'link',link,layout,sectionIndex,
                sectionTitle:text(section.header?.item?.titleLink?.title||section.header?.item?.title),
                title,artist:text(item.artistName||item.subtitle||item.subtitleLinks?.map(x=>x.title).filter(Boolean).join(' / ')),
                heading:text(item.heading),cover:coverUrl(artwork).slice(0,700),
                featuredCover:layout==='hero'?coverUrl(artwork?.replace('{h}','432'),'768').slice(0,700):'',
                durationMs:typeof item.duration==='number'?item.duration*1000:0
              });
            }
          }
          if(!items.length)throw Error('No usable editorial items');
          snapshot={items,loadedAt:Date.now()};
          if(cache.size>=4)cache.clear();
          cache.set(key,snapshot);
        }
        const items=snapshot.items.slice(offset,offset+40);
        const nextOffset=offset+items.length;
        return {items,hasMore:nextOffset<snapshot.items.length,nextOffset};
      }catch(e){return failure(e,op);}
    }
    const params={limit:25,offset};let path,legacy;
    if(['favoriteState','favoriteAdd','favoriteRemove'].includes(op)) {
      if(!/^(?:[0-9]{1,20}|i\.[A-Za-z0-9._-]{1,95})$/.test(v.id||''))throw Error('Invalid favorite song');
      const library=v.id.startsWith('i.');
      const songPath=(library?'/v1/me/library/songs/':'/v1/catalog/'+storefront+'/songs/')+v.id;
      try {
        const read=async()=>{
          const response=await player.api.music(songPath,{extend:'inFavorites'});
          const item=(response?.data?.data||response?.data)?.[0];
          if(!item)throw Error('Song unavailable');
          return item.attributes?.inFavorites===true;
        };
        if(op==='favoriteState')return {favorite:await read()};
        if(library)throw Error('Catalog song ID required');
        const desired=op==='favoriteAdd';
        const method=desired?'POST':'DELETE';
        // The Apple Music web player sends favorite writes to its AMP host.
        // Keep authentication and the request inside the existing MusicKit session.
        const response=await player.api.music('/v1/me/favorites',{'ids[songs]':v.id,platform:'web'},
          {url:'https://amp-api.music.apple.com/v1/me/favorites',method,fetchOptions:{method}});
        if(response?.errors?.length||response?.data?.errors?.length)throw response.data?.errors?response.data:response;
        let favorite=await read();
        // The write can be accepted before the catalog reflects it.
        for(const delay of [300,600]){
          if(favorite===desired)break;
          await new Promise(resolve=>setTimeout(resolve,delay));
          favorite=await read();
        }
        return {favorite};
      }catch(e){return failure(e,op);}
    }
    if(op==='search'||op==='librarySearch') {
      if(typeof v.term!=='string'||!v.term.trim()||v.term.length>200)throw Error('Invalid search');
      path=op==='search'?'/v1/catalog/'+storefront+'/search':'/v1/me/library/search';
      params.term=v.term;params.types=op==='search'?'songs':'library-songs';params.limit=25;
      legacy=()=>player.api.search(params);
    } else if(op==='charts') {path='/v1/catalog/'+storefront+'/charts';params.types='songs';legacy=()=>player.api.charts(params);}
    else if(op==='discover'||op==='genreCharts') {
      path='/v1/catalog/'+storefront+'/charts';params.types=op==='discover'?'albums':'songs';
      if(op==='genreCharts') {
        if(!/^\d{1,10}$/.test(v.id||''))throw Error('Invalid genre');
        params.genre=v.id;
      }
    }
    else if(op==='genres') {path='/v1/catalog/'+storefront+'/genres';}
    else if(op==='radio') {path='/v1/me/recent/radio-stations';}
    else if(op==='liveRadio') {
      path='/v1/catalog/'+storefront+'/stations';
      delete params.limit;
      delete params.offset;
      params['filter[featured]']='apple-music-live-radio';
    }
    else if(op==='recentlyAdded') {path='/v1/me/library/recently-added';}
    else if(op==='libraryAlbums') {path='/v1/me/library/albums';params.include='catalog';}
    else if(op==='playlists') {path='/v1/me/library/playlists';legacy=()=>player.api.library.playlists(params);}
    else if(op==='songs'||op==='favoriteSongs') {
      path='/v1/me/library/songs';params.extend='inFavorites';legacy=()=>player.api.library.songs(params);
    }
    else if(op==='recent') {
      path='/v1/me/recent/played';params.limit=10;
      params.types='albums,library-albums,playlists,library-playlists,stations';
    }
    else if(op==='heavyRotation') {path='/v1/me/history/heavy-rotation';params.limit=10;}
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
    let page=data;
    if(op==='search'||op==='librarySearch')page=data?.results?.[params.types]||data?.[params.types];
    else if(['charts','discover','genreCharts'].includes(op)) {
      const charts=data?.results?.[params.types];
      page=Array.isArray(charts)?charts[0]:charts;
    }
    const baseRows=Array.isArray(page)?page:page?.data||[];
    const rows=op==='recommend'?baseRows.flatMap(group=>{
      const title=group.attributes?.title;
      const sectionTitle=text(title?.stringForDisplay||title?.string||title);
      return (group.relationships?.contents?.data||[]).map(row=>({...row,sectionTitle}));
    }):baseRows;
    const cards=['recent','recommend','heavyRotation','playlists','radio','liveRadio','recentlyAdded','libraryAlbums','discover','genres'].includes(op);
    const kinds=cards?['albums','playlists','library-albums','library-playlists','stations','genres']:['songs','library-songs'];
    const items=rows.filter(row=>kinds.includes(row.type)&&(op!=='favoriteSongs'||row.attributes?.inFavorites===true)).slice(0,op==='recommend'?40:25).map(row=>{
      const a=row.attributes||{},catalog=a.playParams?.catalogId||row.relationships?.catalog?.data?.[0]?.id;
      return {id:text(row.id),kind:text(row.type),playId:text(catalog||a.playParams?.id||row.id),library:!catalog&&String(row.type).startsWith('library-'),
        title:text(a.name),artist:text(a.artistName),album:text(a.albumName),
        cover:coverUrl(a.artwork?.url||row.relationships?.catalog?.data?.[0]?.attributes?.artwork?.url),
        ...(op==='recommend'?{sectionTitle:row.sectionTitle}:{}),
        ...(op==='discover'?{featuredCover:coverUrl(a.artwork?.url,'768'),releaseDate:text(a.releaseDate)}:{}),
        genre:text(a.genreNames?.[0]), compilation:a.isCompilation===true||row.relationships?.catalog?.data?.[0]?.attributes?.isCompilation===true,
        favoriteList:Array.isArray(a.tags)&&a.tags.includes('favorited'),
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
    completion.id='';
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
    const station=v.id.startsWith('station:');
    let descriptor=station?{station:v.id.slice(8)}:{song:v.id};
    if(v.library===true) {
      descriptor={librarySong:v.id};
    }
    if(!station&&typeof player.autoplayEnabled==='boolean')player.autoplayEnabled=false;
    let stage='queue';
    try {
    globalThis.__dangoAppleLoadStage='queue';
    await player.setQueue(descriptor);
    if(globalThis.__dangoAppleGeneration!==generation)return {cancelled:true};
    globalThis.__dangoAppleStation=station;
    completion.id='';
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
      completion.id='';
      let timer;
      try {
        await Promise.race([
          player[method](...(command.name==='seek'?[command.value]:[])),
          new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('Player control timed out')),4000);})
        ]);
      } finally {clearTimeout(timer);}
      if(command.name==='stop')globalThis.__dangoAppleStation=false;
    }
  }
  const readiness=await sessionReadiness();
  const item=player.nowPlayingItem, a=item?.attributes||{};
  const playing=player.isPlaying===true;
  const endedId=!playing&&(!item||completion.id===item.id)?completion.id:'';
  const cover=coverUrl(a.artwork?.url);
  const queue=globalThis.__dangoAppleStation===true?player.queue?.items:null;
  const queueStart=Math.max(0,(player.nowPlayingItemIndex||0)-10);
  const stationQueue=Array.isArray(queue)
    ?queue.slice(queueStart,queueStart+40)
      .filter(entry=>idOk(entry?.id)&&text(entry.attributes?.name||entry.title))
      .map(entry=>{
        const attrs=entry.attributes||{};
        return {id:entry.id,title:text(attrs.name||entry.title),artist:text(attrs.artistName||entry.artistName),
          album:text(attrs.albumName),cover:coverUrl(attrs.artwork?.url),
          durationMs:Number.isFinite(attrs.durationInMillis)?attrs.durationInMillis:0};
      }):[];
  const capabilities=Object.keys(methods).filter(k=>typeof player[methods[k]]==='function');
  if(typeof player.volume==='number')capabilities.push('volume');
  return {available:true,previewOnly:player.previewOnly===true,loadingStage:globalThis.__dangoAppleLoadStage||'',error:globalThis.__dangoAppleMediaError||readiness.error||'',authorized:readiness.authorized,authState:readiness.authState,ended:!playing&&(!!endedId||isEnded(player.playbackState)),title:text(a.name||item?.title),artist:text(a.artistName||item?.artistName),album:text(a.albumName),id:text(endedId||item?.id),cover,stationQueue,
    playing,positionMs:Number.isFinite(player.currentPlaybackTime)?player.currentPlaybackTime*1000:0,
    durationMs:player.currentPlaybackDuration>0&&Number.isFinite(player.currentPlaybackDuration)?player.currentPlaybackDuration*1000:a.durationInMillis||0,capabilities};
}
if(typeof module!=='undefined')module.exports={appleAdapter};
