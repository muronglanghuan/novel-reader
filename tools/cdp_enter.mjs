// 点击进入书 → 滚动到较深章节 → 等待镜像
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
// 点书进入
const bk = await ev(`(function(){const b=document.querySelector('.book-item').getBoundingClientRect();return{x:b.x+b.width/2,y:b.y+b.height/2};})()`);
await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: bk.v.x, y: bk.v.y, button: 'left', clickCount: 1 });
await wait(70);
await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: bk.v.x, y: bk.v.y, button: 'left', clickCount: 1 });
for (let i = 0; i < 60; i++) { await wait(300); if ((await ev(`R.book && R.book.total>0`)).v) break; }
console.log('已进入, 章节总数:', (await ev(`R.book.total`)).v);
// 跳到 60% 处章节
await ev(`jumpToChapter(Math.floor(R.book.total*0.6), 3); true`);
await wait(2500);
const st = JSON.parse((await ev(`JSON.stringify({ch: R.curCh, scroll: Math.round(document.getElementById('reader-body').scrollTop)})`)).v);
console.log('目标位置 ch=' + st.ch + ' scroll=' + st.scroll);
// 等镜像节流写盘(≤16s)
console.log('等待镜像…');
await wait(17000);
console.log('lastBook:', (await ev(`NovelBridge.loadLastBook()`)).v);
ws.close(); process.exit(0);
