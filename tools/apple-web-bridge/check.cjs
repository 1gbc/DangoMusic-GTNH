const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
test('authenticated local bridge rejects stale playback after stop and reconnect', async () => {
  assert.ok(fs.existsSync(__dirname + '/bridge.cjs'), 'The official-web bridge must exist');
  const {createBridge} = require('./bridge.cjs');
  const bridge = await createBridge();
  const origin = 'chrome-extension://' + 'a'.repeat(32);
  const post = async (path, key, data={}, from=origin) => {
    const response = await fetch(bridge.url + path, {method:'POST', headers:{Origin:from, Authorization:'Bearer '+key,'Content-Type':'application/json'}, body:JSON.stringify(data)});
    return {code:response.status, data:await response.json()};
  };
  try {
    assert.equal((await post('/pair', bridge.pairing, {}, 'https://evil.invalid')).code,403);
    assert.equal((await post('/pair', 'wrong')).code,403);
    const paired = await post('/pair', bridge.pairing);
    assert.equal(paired.code,200);
    const key=paired.data.session;
    assert.equal((await post('/pair',bridge.pairing)).code,403);
    const telemetry={ack:0,state:{title:'Song',artist:'Singer',playing:true,positionMs:65000,durationMs:190000,cookie:'SECRET',cover:'https://evil.invalid/a'}};
    assert.equal((await post('/sync',key,telemetry)).code,200);
    assert.equal(bridge.status().state.title,'Song');
    assert.equal(bridge.status().state.cookie,undefined);
    assert.equal(bridge.status().state.cover,'');
    bridge.command('stop');
    assert.equal(bridge.status().state,null);
    const stop=await post('/sync',key,telemetry);
    assert.equal(stop.data.command.name,'stop');
    assert.equal(bridge.status().state,null,'Unacknowledged telemetry cannot revive stopped lyrics');
    await post('/sync',key,{ack:stop.data.command.id,state:telemetry.state});
    assert.equal(bridge.status().state,null,'Even late telemetry with matching ack cannot revive stopped lyrics');
    bridge.command('play');
    const play=await post('/sync',key,{...telemetry,ack:stop.data.command.id});
    await post('/sync',key,{ack:play.data.command.id,state:telemetry.state});
    assert.equal(bridge.status().state.positionMs,65000);
    assert.throws(()=>bridge.command('seek',-3));
    assert.throws(()=>bridge.command('load','https://evil.invalid/'));
    assert.equal((await post('/sync',key,telemetry,'chrome-extension://'+'b'.repeat(32))).code,403);
    bridge.disconnect();
    assert.equal((await post('/sync',key,telemetry)).code,403);
  } finally {await bridge.close();}
});
test('page adapter reads the actual player clock and validates controls without reading credentials', async()=>{
  const vm=require('node:vm');
  const source=fs.readFileSync(__dirname+'/extension/adapter.js','utf8');
  let paused=false,seek=-1;
  const player={nowPlayingItem:{id:'123',attributes:{name:'Track',artistName:'Artist',artwork:{url:'https://is1-ssl.mzstatic.com/a/{w}x{h}.jpg'}}},currentPlaybackTime:83,currentPlaybackDuration:240,isPlaying:true,volume:0.4,
    pause:async()=>{paused=true;},seekToTime:async v=>{seek=v;}};
  Object.defineProperty(player,'developerToken',{get(){throw Error('Credentials must never be read');}});
  const context=vm.createContext({location:{origin:'https://music.apple.com'},MusicKit:{getInstance:()=>player},URL,setTimeout,clearTimeout});
  vm.runInContext(source,context);
  let state=await context.appleAdapter(null);
  assert.equal(state.positionMs,83000);
  assert.equal(state.title,'Track');
  player.currentPlaybackTime=21;
  state=await context.appleAdapter({name:'pause'});
  assert.equal(state.positionMs,21000,'Clock follows webpage seek, not elapsed local time');
  assert.equal(paused,true);
  await context.appleAdapter({name:'seek',value:92});assert.equal(seek,92);
  await assert.rejects(context.appleAdapter({name:'seek',value:-1}));
  await assert.rejects(context.appleAdapter({name:'unknown'}));
  context.MusicKit=null;
  assert.equal((await context.appleAdapter(null)).available,false);
});
