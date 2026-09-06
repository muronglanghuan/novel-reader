const list = await (await fetch('http://127.0.0.1:9222/json/list')).json();
const t = list.find(x => x.type === 'page' && /appassets/.test(x.url || ''));
const ws = new WebSocket(t.webSocketDebuggerUrl);
let seq = 0; const pend = new Map();
ws.onmessage = ev => { const m = JSON.parse(ev.data); if (m.id && pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); } };
const send = (method, params) => new Promise((res, rej) => { const id = ++seq; pend.set(id, m => m.error ? rej(new Error(m.error.message)) : res(m.result)); ws.send(JSON.stringify({ id, method, params: params || {} })); });
await new Promise(r => ws.onopen = r);
const wait = ms => new Promise(r => setTimeout(r, ms));
async function ev(expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  return { v: r.result.value, err: r.result.exceptionDetails ? r.result.exceptionDetails.text : null };
}
const base = await ev(`JSON.stringify({vis: document.visibilityState, hidden: document.hidden})`);
console.log('base:', base.v);
// 1) rAF 在 auto 运行期间是否计数
const raf = await ev(`(function(){ return new Promise(res=>{
  let n=0; const t0=performance.now();
  (function f(ts){ n++; if(ts-t0>=2000) res(n); else requestAnimationFrame(f); })(t0);
}); })()`);
console.log('rAF 2s 帧数:', raf.v);
// 2) auto 内部每秒自检
await ev(`window.__autoTicks=[]; const __orig=window.requestAnimationFrame;
  let cnt=0; (function(){ if(!Auto.on){startAuto();} })();
  const iv=setInterval(()=>{ window.__autoTicks.push(Math.round(document.getElementById('reader-body').scrollTop)); }, 1000);
  setTimeout(()=>{ clearInterval(iv); stopAuto(); }, 6000); true`);
await wait(7500);
console.log('auto ticks:', (await ev(`JSON.stringify(window.__autoTicks)`)).v);
ws.close(); process.exit(0);
