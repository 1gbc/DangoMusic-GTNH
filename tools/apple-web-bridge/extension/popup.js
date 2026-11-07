const status=document.querySelector('#status');
document.querySelector('#pair').onclick=async()=>{const result=await chrome.runtime.sendMessage({type:'pair',link:document.querySelector('#link').value.trim()});document.querySelector('#link').value='';status.textContent=result.ok?'已配对。请在本机控制台输入 status 检查真实进度。':result.error;};
document.querySelector('#disconnect').onclick=async()=>{await chrome.runtime.sendMessage({type:'disconnect'});status.textContent='已断开；已尝试暂停网页播放。';};
chrome.runtime.sendMessage({type:'status'}).then(s=>{status.textContent=s.error||(s.paired?'已配对，等待网页状态。':'尚未配对。');});
