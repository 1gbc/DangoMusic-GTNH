// Timers can be throttled in background tabs: real background playback must be tested.
setInterval(()=>chrome.runtime.sendMessage({type:'tick'}).catch(()=>{}),700);
