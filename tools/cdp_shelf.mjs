const list = await (await fetch('http://127.0.0.1:9222/json/list')).json();
const t = list.find(x => x.type === 'page' && /appassets/.test(x.url || ''));
const ws = new WebSocket(t.webSocketDebuggerUrl);
let seq = 0; const pend = new Map();
ws.onmessage = ev => { const m = JSON.parse(ev.data); if (m.id && pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); } };
const send = (method, params) => new Promise((res, rej) => { const id = ++seq; pend.set(id, m => m.error ? rej(new Error(m.error.message)) : res(m.result)); ws.send(JSON.stringify({ id, method, params: params || {} })); });
await new Promise(r => ws.onopen = r);
const wait = ms => new Promise(r => setTimeout(r, ms));
const results = [];
function check(name, ok, extra) { results.push((ok ? 'PASS ' : 'FAIL ') + name + (extra !== undefined ? ' [' + extra + ']' : '')); console.log(results[results.length - 1]); }
async function ev(expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true });
  if (r.result?.exceptionDetails) return { err: r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text };
  return { v: r.result.value };
}
async function waitFor(expr, ms, label) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    const r = await ev(expr);
    if (r.v === true || (r.v && r.v !== 'false')) return true;
    await wait(250);
  }
  console.log('   (超时 ' + label + ')');
  return false;
}
async function clickAt(x, y) {
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
  await wait(70);
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
  await wait(150);
}
async function clickSel(sel) {
  const r = await ev(`(function(){ const el=document.querySelector(${JSON.stringify(sel)});
    if(!el) return null; const b=el.getBoundingClientRect();
    const h=document.elementFromPoint(b.x+b.width/2,b.y+b.height/2);
    if(!(h&&(h===el||el.contains(h)))) return {miss:true};
    return {x:b.x+b.width/2, y:b.y+b.height/2}; })()`);
  if (!r.v || r.v.miss) return false;
  await clickAt(r.v.x, r.v.y);
  return true;
}

// 刷新页面到书架
await send('Page.navigate', { url: 'https://appassets.androidplatform.net/assets/index.html' });
await wait(3500);
const s = await waitFor(`document.querySelectorAll('.book-item').length===2`, 8000, '两本书上架');
check('书架显示2本书(内置+导入)', s);
// 内置书无删除按钮
const builtinDel = await ev(`(function(){ const items=[...document.querySelectorAll('.book-item')];
  const b=items.find(it=>it.textContent.includes('爆率'));
  return !!(b && !b.querySelector('.btn-del')); })()`);
check('内置书无删除入口', builtinDel.v === true);
// 导入书有删除按钮，先取消
const hasDel = await ev(`!!document.querySelector('.btn-del')`);
check('导入书有删除入口', hasDel.v === true);
await clickSel('.btn-del');
const dlgOpen = await waitFor(`!document.getElementById('dlg-delete').hidden`, 3000, '弹窗');
check('删除确认弹窗打开', dlgOpen);
const dlgText = (await ev(`document.getElementById('dlg-delete-text').textContent`)).v;
check('弹窗文案正确', /删除该书文件与阅读进度/.test(dlgText || ''));
await clickSel('#dlg-delete-cancel');
const canceled = await waitFor(`document.getElementById('dlg-delete').hidden===true`, 3000, '取消');
check('取消后弹窗关闭且书仍在', canceled && (await ev(`document.querySelectorAll('.book-item').length`)).v === 2);
// 真删除
await clickSel('.btn-del');
await waitFor(`!document.getElementById('dlg-delete').hidden`, 3000, '弹窗2');
await clickSel('#dlg-delete-ok');
const gone = await waitFor(`document.querySelectorAll('.book-item').length===1`, 6000, '删除生效');
check('确认后书被删除(剩1本)', gone);
const leftTitle = (await ev(`document.querySelector('.book-item .book-title').textContent`)).v;
check('剩余为内置书', /爆率/.test(leftTitle || ''));
ws.close();
const fails = results.filter(x => x.startsWith('FAIL'));
console.log('---- 汇总: ' + (results.length - fails.length) + '/' + results.length + ' 通过 ----');
process.exit(fails.length ? 1 : 0);
