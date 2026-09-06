const BASE = 'http://127.0.0.1:9222';
async function conn() {
  const list = await (await fetch(BASE + '/json/list')).json();
  const t = list.find(x => x.type === 'page' && /appassets/.test(x.url || ''));
  const ws = new WebSocket(t.webSocketDebuggerUrl);
  let seq = 0; const pend = new Map();
  ws.onmessage = ev => { const m = JSON.parse(ev.data); if (m.id && pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); } };
  const send = (method, params) => new Promise((res, rej) => { const id = ++seq; pend.set(id, m => m.error ? rej(new Error(m.error.message)) : res(m.result)); ws.send(JSON.stringify({ id, method, params: params || {} })); });
  await new Promise(r => ws.onopen = r);
  return { send, ws, wait: ms => new Promise(r => setTimeout(r, ms)) };
}
const results = [];
function check(name, ok, extra) { results.push((ok ? 'PASS ' : 'FAIL ') + name + (extra !== undefined ? ' [' + extra + ']' : '')); console.log(results[results.length - 1]); }
const mode = process.argv[2] || 'first';
const c = await conn();
const ws = c.ws;
async function ev(expression) {
  const r = await c.send('Runtime.evaluate', { expression, returnByValue: true });
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
const wait = ms => new Promise(r => setTimeout(r, ms));
if (mode === 'first') {
  // 首次安装：应落在书架
  const shelf = await waitFor(`!document.getElementById('screen-shelf').hidden`, 8000, '书架');
  check('首次启动-落在书架', shelf);
  const items = (await ev(`document.querySelectorAll('.book-item').length`)).v;
  check('书架-有内置书', items === 1, 'items=' + items);
} else if (mode === 'resume') {
  // 杀进程后重启：应直达正文续读
  const reader = await waitFor(`!document.getElementById('screen-reader').hidden && R.book && R.book.total>0`, 15000, '直达正文');
  check('冷启动-直达正文', !!reader);
  const st = JSON.parse((await ev(`JSON.stringify({ch: R.curCh, scroll: document.getElementById('reader-body').scrollTop})`)).v);
  console.log('   恢复位置 ch=' + st.ch + ' scroll=' + Math.round(st.scroll));
  check('续读章节>0', st.ch > 0, 'ch=' + st.ch);
  const saved = JSON.parse((await ev(`JSON.stringify({ch: R.curCh})`)).v);
  check('无引擎朗读按钮存在', (await ev(`!!document.getElementById('btn-tts-play')`)).v === true);
} else if (mode === 'shelfback') {
  // 主动回书架后重启：应落在书架
  const shelf = await waitFor(`!document.getElementById('screen-shelf').hidden`, 8000, '书架');
  check('回书架后重启-落在书架', shelf);
}
c.ws && c.ws.close();
const fails = results.filter(x => x.startsWith('FAIL'));
console.log('---- ' + mode + ' 汇总: ' + (results.length - fails.length) + '/' + results.length + ' ----');
process.exit(fails.length ? 1 : 0);
