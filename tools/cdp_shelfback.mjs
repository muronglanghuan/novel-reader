// 点「书架」退出阅读 → 返回书架
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
  return { v: r.result.value };
}
async function waitFor(expr, ms) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) { const r = await ev(expr); if (r.v === true || (r.v && r.v !== 'false')) return true; await wait(250); }
  return false;
}
// 唤出工具条再点书架
await ev(`(function(){ if(!chromeOn){ /* 点击正文顶部区域显示工具条 */
  const b=document.getElementById('reader-body').getBoundingClientRect();
  const evt=new MouseEvent('click',{bubbles:true,clientX:b.x+b.width/2,clientY:b.y+50});
  document.elementFromPoint(b.x+b.width/2, b.y+50).dispatchEvent(evt);} })(); true`);
await wait(500);
const chrome = (await ev(`chromeOn`)).v;
console.log('chrome:', chrome);
const btn = await ev(`(function(){const b=document.getElementById('btn-shelf-back').getBoundingClientRect();return{x:b.x+b.width/2,y:b.y+b.height/2};})()`);
await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: btn.v.x, y: btn.v.y, button: 'left', clickCount: 1 });
await wait(80);
await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: btn.v.x, y: btn.v.y, button: 'left', clickCount: 1 });
const backShelf = await waitFor(`!document.getElementById('screen-shelf').hidden`, 5000);
console.log('回书架:', backShelf);
console.log('lastBook(应清空):', JSON.stringify((await ev(`NovelBridge.loadLastBook()`)).v));
ws.close(); process.exit(0);
