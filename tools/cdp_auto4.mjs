const list = await (await fetch('http://127.0.0.1:9222/json/list')).json();
const t = list.find(x => x.type === 'page' && /appassets/.test(x.url || ''));
const ws = new WebSocket(t.webSocketDebuggerUrl);
let seq = 0; const pend = new Map();
ws.onmessage = ev => { const m = JSON.parse(ev.data); if (m.id && pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); } };
const send = (method, params) => new Promise((res, rej) => { const id = ++seq; pend.set(id, m => m.error ? rej(new Error(m.error.message)) : res(m.result)); ws.send(JSON.stringify({ id, method, params: params || {} })); });
await new Promise(r => ws.onopen = r);
const wait = ms => new Promise(r => setTimeout(r, ms));
async function ev(expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true });
  return { v: r.result.value, err: r.result.exceptionDetails ? r.result.exceptionDetails.exception?.description?.slice(0,300) : null };
}
console.log('info:', JSON.stringify((await ev(`JSON.stringify({
  max: document.getElementById('reader-body').scrollHeight - document.getElementById('reader-body').clientHeight,
  st: document.getElementById('reader-body').scrollTop, scH: document.getElementById('reader-body').scrollHeight})`)).v));
// 手动喂帧
console.log('手动一帧:', (await ev(`Auto.on===true?0:startAuto();
  Auto.last = performance.now()-1000; autoStep(performance.now()); true`)).err || 'ok');
console.log('手动帧后 scrollTop:', (await ev(`document.getElementById('reader-body').scrollTop`)).v);
// 数 rAF 回调
await ev(`stopAuto(); window.__af=0;
const orig=window.requestAnimationFrame.bind(window);
window.requestAnimationFrame=(cb)=>{ return orig((ts)=>{ if(typeof cb==='function' && cb.name==='autoStep') window.__af++; cb(ts); }); };
startAuto(); true`);
await wait(2500);
console.log('2.5s内 autoStep 调用数:', (await ev(`window.__af`)).v, ' scrollTop:', (await ev(`document.getElementById('reader-body').scrollTop`)).v);
await ev(`stopAuto(); true`);
ws.close(); process.exit(0);
