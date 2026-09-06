// 自动卷轴独立定量验证
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
  return { v: r.result.value, err: r.result.exceptionDetails ? r.result.exceptionDetails.text : null };
}
// 当前应在阅读器内(上一步骤后)。若不在则回书架重进。
const inReader = (await ev(`!document.getElementById('screen-reader').hidden && R.book`)).v;
if (!inReader) {
  await send('Page.navigate', { url: 'https://appassets.androidplatform.net/assets/index.html' });
  await wait(3000);
  const bk = await ev(`(function(){const b=document.querySelector('.book-item').getBoundingClientRect();return{x:b.x+b.width/2,y:b.y+b.height/2};})()`);
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: bk.v.x, y: bk.v.y, button: 'left', clickCount: 1 });
  await wait(80);
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: bk.v.x, y: bk.v.y, button: 'left', clickCount: 1 });
  for (let i = 0; i < 60; i++) { await wait(300); if ((await ev(`R.book && R.book.total>0`)).v) break; }
}
console.log('状态:', JSON.stringify((await ev(`JSON.stringify({cpm: Settings.cur.autoCpm, fs: Settings.cur.font, px: (typeof Auto!=='undefined'?Auto.pxPerSec:'未启动')})`)).v));
// 直接调用
await ev(`startAuto(); true`);
await wait(400);
const info = await ev(`JSON.stringify({on: Auto.on, px: Auto.pxPerSec, vh: document.getElementById('reader-body').clientHeight})`);
const a0 = await ev(`document.getElementById('reader-body').scrollTop`);
await wait(4000);
const a1 = await ev(`document.getElementById('reader-body').scrollTop`);
console.log('auto info:', info.v);
console.log('scrollTop:', a0.v, '→', a1.v, '  Δ=', Math.round(a1.v - a0.v));
await ev(`stopAuto(); true`);
ws.close(); process.exit(0);
