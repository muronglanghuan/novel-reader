const list = await (await fetch('http://127.0.0.1:9222/json/list')).json();
const t = list.find(x => x.type === 'page' && /appassets/.test(x.url || ''));
const ws = new WebSocket(t.webSocketDebuggerUrl);
let seq = 0; const pend = new Map();
const logs = [];
ws.onmessage = ev => { const m = JSON.parse(ev.data); if (m.id && pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); }
  else if (m.method === 'Runtime.exceptionThrown') logs.push('[EXC] ' + (m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text).slice(0, 300)); };
const send = (method, params) => new Promise((res, rej) => { const id = ++seq; pend.set(id, m => m.error ? rej(new Error(m.error.message)) : res(m.result)); ws.send(JSON.stringify({ id, method, params: params || {} })); });
await new Promise(r => ws.onopen = r);
await send('Runtime.enable');
const wait = ms => new Promise(r => setTimeout(r, ms));
async function ev(expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true });
  if (r.result?.exceptionDetails) return { err: r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text };
  return { v: r.result.value };
}
async function click(x, y) {
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
  await wait(80);
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
  await wait(150);
}
async function c(sel) {
  let r;
  for (let i = 0; i < 60; i++) {
    r = await ev(`(function(){const el=document.querySelector(${JSON.stringify(sel)}); if(!el) return null;
      const b=el.getBoundingClientRect(); return {x:b.x+b.width/2,y:b.y+b.height/2};})()`);
    if (r.v) break;
    await wait(300);
  }
  if (!r.v) { console.log('找不到元素:', sel); return false; }
  await click(r.v.x, r.v.y);
  return true;
}

await send('Page.navigate', { url: 'https://appassets.androidplatform.net/assets/index.html' });
await wait(2500);
await c('.book-item');
for (let i = 0; i < 60; i++) { await wait(300); if ((await ev(`R.book && R.book.total>0`)).v) break; }
// 前置：55% 滚动
await ev(`(function(){ const b=document.getElementById('reader-body'); b.scrollTop = Math.floor((b.scrollHeight - b.clientHeight) * 0.55); })()`);
await wait(900);
console.log('前置 curCh=', (await ev(`R.curCh`)).v);
// chrome 显示
const rbb = await ev(`(function(){const b=document.getElementById('reader-body').getBoundingClientRect();return{x:b.x+b.width/2,y:b.y+40};})()`);
if (!(await ev(`chromeOn===true`)).v) { await click(rbb.v.x, rbb.v.y); await wait(400); }
console.log('chromeOn=', (await ev(`chromeOn`)).v);
// 目录
await c('#btn-toc'); await wait(600);
const targetCh = (await ev(`Math.min(R.curCh+12, R.book.total-1)`)).v;
console.log('targetCh=', targetCh);
await ev(`window.__lastTocFire=null; document.querySelectorAll('.toc-item')[${targetCh}].addEventListener('click',function(){window.__lastTocFire=${targetCh}},{once:true}); true`);
await ev(`document.querySelectorAll('.toc-item')[${targetCh}].scrollIntoView({block:'center'}); true`);
await wait(800);
// 点击前完整快照
const pre = await ev(`JSON.stringify((function(){
  const el=document.querySelectorAll('.toc-item')[${targetCh}];
  const b=el.getBoundingClientRect();
  const hit=document.elementFromPoint(b.x+b.width/2,b.y+b.height/2);
  return {inView:b.top>=0&&b.bottom<=640, hit:(hit&&(hit.className||hit.tagName))||null,
    sc: document.getElementById('reader-body').scrollTop,
    loaded: [...document.querySelectorAll('#content-holder section')].map(s=>+s.dataset.ch).join(',').slice(0,120)};})())`);
console.log('pre:', pre.v);
await c(`.toc-item:nth-of-type(${targetCh+1})`);
await wait(2500);
const post = await ev(`JSON.stringify((function(){
  return { curCh: R.curCh, fired: window.__lastTocFire, drawerClosed: document.getElementById('drawer-toc').hidden,
    sc: document.getElementById('reader-body').scrollTop,
    loaded: [...document.querySelectorAll('#content-holder section')].map(s=>+s.dataset.ch).join(',').slice(0,200) };})())`);
console.log('post:', post.v);
console.log('期望章节所在section存在:', (await ev(`[...document.querySelectorAll('#content-holder section')].some(s=>+s.dataset.ch===${targetCh})`)).v);
logs.slice(-6).forEach(l => console.log(l));
ws.close(); process.exit(0);
