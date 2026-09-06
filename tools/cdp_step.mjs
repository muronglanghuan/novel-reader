// 目录跳转步骤的深度探针复现：抓 console 异常 + 元素 fired 标记 + 状态快照
const list = await (await fetch('http://127.0.0.1:9222/json/list')).json();
const t = list.find(x => x.type === 'page' && /appassets/.test(x.url || ''));
const ws = new WebSocket(t.webSocketDebuggerUrl);
let seq = 0; const pend = new Map();
const logs = [];
ws.onmessage = ev => {
  const m = JSON.parse(ev.data);
  if (m.id && pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); }
  else if (m.method === 'Runtime.consoleAPICalled') logs.push('[console] ' + m.params.args.map(a => a.value ?? a.description ?? '').join(' ').slice(0, 160));
  else if (m.method === 'Runtime.exceptionThrown') logs.push('[EXC] ' + (m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text).slice(0, 200));
};
const send = (method, params) => new Promise((res, rej) => { const id = ++seq; pend.set(id, m => m.error ? rej(new Error(m.error.message)) : res(m.result)); ws.send(JSON.stringify({ id, method, params: params || {} })); });
await new Promise(r => ws.onopen = r);
await send('Runtime.enable');
const wait = ms => new Promise(r => setTimeout(r, ms));
async function ev(expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true });
  if (r.result?.exceptionDetails) return { err: r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text };
  return { v: r.result.value };
}
async function clickAtXY(x, y) {
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
  await wait(80);
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
  await wait(150);
}
const snap = async label => {
  const s = await ev(`JSON.stringify((function(){
    return { t: ${JSON.stringify(label)},
      chrome: typeof chromeOn!=='undefined'?chromeOn:null,
      curCh: (typeof R!=='undefined'&&R.book)?R.curCh:null,
      tocHidden: document.getElementById('drawer-toc').hidden,
      tocItems: document.querySelectorAll('.toc-item').length,
      fired: window.__lastTocFire || null,
      holderSections: document.querySelectorAll('#content-holder section').length }; })())`);
  console.log('SNAP', s.v);
};
// 确保在书架 → 点开书
await send('Page.navigate', { url: 'https://appassets.androidplatform.net/assets/index.html' });
await wait(3500);
if (!(await ev(`document.querySelectorAll('.book-item').length===1`)).v) { console.log('书架异常'); process.exit(1); }
const bk = await ev(`(function(){const b=document.querySelector('.book-item').getBoundingClientRect();return{x:b.x+b.width/2,y:b.y+b.height/2};})()`);
await clickAtXY(bk.v.x, bk.v.y);
let ok = false;
for (let i = 0; i < 80 && !ok; i++) { await wait(300); ok = (await ev(`!document.getElementById('screen-reader').hidden && R.book && R.book.total>0`)).v === true; }
console.log('进入阅读器:', ok);
await snap('reader');
// 唤起工具条
const on = (await ev(`chromeOn===true`)).v;
if (!on) {
  const rb = await ev(`(function(){const b=document.getElementById('reader-body').getBoundingClientRect();return{x:b.x+b.width/2,y:Math.max(90,b.y+40)};})()`);
  await clickAtXY(rb.v.x, rb.v.y);
  await wait(300);
}
await snap('chrome=' + (await ev(`chromeOn`)).v);
// 目录
const toc = await ev(`(function(){const b=document.getElementById('btn-toc').getBoundingClientRect();return{x:b.x+b.width/2,y:b.y+b.height/2};})()`);
await clickAtXY(toc.v.x, toc.v.y);
await wait(500);
await snap('toc-opened');
const targetCh = (await ev(`Math.min(R.curCh+12, R.book.total-1)`)).v;
console.log('targetCh =', targetCh);
// 挂 fired 标记
await ev(`document.querySelectorAll('.toc-item').forEach((el,i)=>{ el.__old=el.onclick; }); true`);
await ev(`window.__lastTocFire = null; document.querySelectorAll('.toc-item')[${targetCh}].addEventListener('click', function(){ window.__lastTocFire = ${targetCh}; }, {once:true}); true`);
// 滚动到目标项
await ev(`document.querySelectorAll('.toc-item')[${targetCh}].scrollIntoView({block:'center'}); true`);
await wait(700);
const item = await ev(`(function(){ const el=document.querySelectorAll('.toc-item')[${targetCh}];
  const b=el.getBoundingClientRect();
  const inView = b.top>=0 && b.bottom<=640;
  // 探测该坐标处的元素
  const hit = document.elementFromPoint(b.x+b.width/2, b.y+b.height/2);
  return {x:b.x+b.width/2, y:b.y+b.height/2, top:b.top, bottom:b.bottom, inView,
    hitTag: hit? (hit.className||hit.tagName):null, hitText: hit? (hit.textContent||'').slice(0,20):null,
    itemText: el.textContent.slice(0,20)}; })()`);
console.log('item:', JSON.stringify(item.v));
await clickAtXY(item.v.x, item.v.y);
await wait(1000);
await snap('after-click-1s');
const cur = (await ev(`R.curCh`)).v;
console.log('curCh =', cur, '期望', targetCh);
console.log('--- 页面 console/异常(如有) ---');
logs.slice(-12).forEach(l => console.log(l));
ws.close(); process.exit(0);
