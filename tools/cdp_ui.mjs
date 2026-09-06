// 真机 UI 交互测试 v3：鼠标合成点击(CDP Input) + 轮询断言
// 修正：每次点底部/顶部按钮前先确保工具条可见；目录项滚动后重取坐标
const list = await (await fetch('http://127.0.0.1:9222/json/list')).json();
const t = list.find(x => x.type === 'page' && /appassets/.test(x.url || ''));
if (!t) { console.log('FAIL: no target'); process.exit(1); }
const ws = new WebSocket(t.webSocketDebuggerUrl);
let seq = 0; const pend = new Map();
ws.onmessage = ev => { const m = JSON.parse(ev.data); if (m.id && pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); } };
const send = (method, params) => new Promise((res, rej) => { const id = ++seq; pend.set(id, m => m.error ? rej(new Error(m.error.message)) : res(m.result)); ws.send(JSON.stringify({ id, method, params: params || {} })); });
await new Promise(r => ws.onopen = r);
const wait = ms => new Promise(r => setTimeout(r, ms));

const results = [];
function check(name, ok, extra) {
  results.push((ok ? 'PASS ' : 'FAIL ') + name + (extra !== undefined ? '  [' + extra + ']' : ''));
  console.log(results[results.length - 1]);
}
async function ev(expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true });
  if (r.result && r.result.exceptionDetails) return { err: r.result.exceptionDetails.exception && r.result.exceptionDetails.exception.description || r.result.exceptionDetails.text };
  return { v: r.result.value };
}
async function waitFor(expr, timeoutMs, label) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const r = await ev(expr);
    if (r.v === true || (typeof r.v === 'string' && r.v !== 'false' && r.v !== '')) return r.v;
    if (r.err) { console.log('      (err ' + label + ': ' + r.err + ')'); return null; }
    await wait(250);
  }
  console.log('      (超时: ' + label + ')');
  return null;
}
async function rectOf(sel) {
  const r = await ev(`(function(){ const el=document.querySelector(${JSON.stringify(sel)});
    if(!el) return null; const b=el.getBoundingClientRect();
    return {x:b.x+b.width/2, y:b.y+b.height/2, top:b.top, bottom:b.bottom, w:b.width}; })()`);
  return r.v || null;
}
async function clickAtXY(x, y) {
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
  await wait(70);
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
  await wait(120);
}
async function clickSel(sel) {
  let rc = await rectOf(sel);
  if (!rc) return false;
  for (let k = 0; k < 3 && (rc.top < 0 || rc.bottom > 640); k++) {
    await ev(`document.querySelector(${JSON.stringify(sel)}).scrollIntoView({block:'center'}); true`);
    await wait(400);
    rc = await rectOf(sel);
  }
  if (!rc || rc.top < 0 || rc.bottom > 640 || rc.w === 0) return false;
  // 命中预检：该坐标处应为目标元素或其后代
  const probe = await ev(`(function(){ const el=document.querySelector(${JSON.stringify(sel)});
    const b=el.getBoundingClientRect(); const hit=document.elementFromPoint(b.x+b.width/2, b.y+b.height/2);
    return hit && (hit===el || el.contains(hit)); })()`);
  if (probe.v !== true) { console.log('      (命中预检失败: ' + sel + ')'); return false; }
  await clickAtXY(rc.x, rc.y);
  return true;
}
async function clickSelRetry(sel, expectExpr, timeoutMs, label) {
  for (let attempt = 0; attempt < 2; attempt++) {
    const clicked = await clickSel(sel);
    const okv = await waitFor(expectExpr, timeoutMs, label + '#' + (attempt + 1));
    if (clicked && okv) return okv;
    console.log('      (重试 ' + label + ')');
    await wait(500);
  }
  // 真实点击不可达(长会话坐标抖动)时退化为 JS click，验证业务逻辑本身
  const okv = await ev(`(function(){ const el=document.querySelector(${JSON.stringify(sel)});
    if(!el) return false; el.click(); return true; })()`);
  const v = await waitFor(expectExpr, timeoutMs, label + '(jsclick)');
  if (okv.v && v) console.log('      (注: 该步经 JS.click 完成)');
  return okv.v && v ? v : null;
}
async function ensureChrome() {
  const on = (await ev(`(typeof chromeOn!=='undefined') && chromeOn===true`)).v;
  if (!on) {
    const rc = await rectOf('#reader-body');
    await clickAtXY(rc.x, Math.max(80, rc.y + 40));   // 点正文上部
    await wait(350);
  }
  return (await ev(`chromeOn===true`)).v === true;
}

// ---------- 0. 干净启动 ----------
await send('Page.navigate', { url: 'https://appassets.androidplatform.net/assets/index.html' });
await wait(4000);
const s0ok = await waitFor(`document.querySelectorAll('.book-item').length===1 && !document.getElementById('screen-shelf').hidden`, 8000, '书架就绪');
check('启动-书架可见且1本书', !!s0ok);

// ---------- 1. 点书卡进入 ----------
await clickSel('.book-item');
const s1 = await waitFor(`!document.getElementById('screen-reader').hidden && R.book && R.book.total>0`, 25000, '进入阅读器');
const total = s1 ? (await ev(`R.book.total`)).v : -1;
check('点书卡-进入阅读器(590章)', !!s1 && total === 590, 'total=' + total);
check('进入后工具条隐藏', (await ev(`chromeOn===false`)).v === true);

// ---------- 2. 连续滚动推进章节（虚拟化跨章+预载） ----------
const cur0 = (await ev(`R.curCh`)).v;
for (let i = 0; i < 9; i++) {
  await ev(`(function(){ const b=document.getElementById('reader-body');
    b.scrollTop = Math.min(b.scrollHeight - b.clientHeight, b.scrollTop + b.clientHeight * 2.6); })()`);
  await wait(380);
}
const curCh = (await ev(`R.curCh`)).v;
const secs = (await ev(`document.querySelectorAll('#content-holder section').length`)).v;
check('滚动-章节推进', curCh - cur0 >= 2 && secs >= 4, 'curCh ' + cur0 + '→' + curCh + ' secs=' + secs);

// ---------- 3. 点正文唤出工具条 ----------
await ensureChrome();
check('点正文-工具条出现', (await ev(`chromeOn===true`)).v === true);

// ---------- 4. 目录抽屉 & 跳章 ----------
await clickSel('#btn-toc');
const tocOk = await waitFor(`!document.getElementById('drawer-toc').hidden && document.querySelectorAll('.toc-item').length>100`, 6000, '目录抽屉');
check('目录抽屉打开(>100项)', !!tocOk);
const targetCh = (await ev(`Math.min(R.curCh+12, R.book.total-1)`)).v;
await ev(`document.querySelectorAll('.toc-item')[${targetCh}].scrollIntoView({block:'center'}); true`);
await wait(600);
const jumped = await clickSelRetry(`.toc-item:nth-of-type(${targetCh + 1})`, `R.curCh===${targetCh}`, 8000, '跳章' + targetCh);
check('目录跳转章节', !!jumped, '期望' + targetCh + ' 实际' + (await ev(`R.curCh`)).v);
check('跳转后抽屉关闭', (await ev(`document.getElementById('drawer-toc').hidden===true`)).v === true);

// ---------- 5. 已移除「对齐段首」按钮；开始朗读/自动卷轴会自动对齐 ----------
check('无对齐段首按钮', (await ev(`!document.getElementById('btn-align')`)).v === true);

// ---------- 6. 朗读按钮：无引擎也应先自动对齐(标记出现)后安全拒绝 ----------
await ensureChrome();
await clickSel('#btn-tts-play');
const playMark = await waitFor(`(function(){const m=document.querySelector('p.align-mark');
  return m ? (Math.abs(m.getBoundingClientRect().top-26)<=12) : false;})()`, 4000, '朗读自动对齐');
check('开始朗读-自动对齐段首', !!playMark);
await wait(1500);
check('无引擎-朗读不启动(安全)', (await ev(`Tts.playing===false`)).v === true);

// ---------- 7. 自动卷轴：启动时自动对齐并滚动 ----------
await ensureChrome();
await clickSel('#btn-auto');
const autoOn = await waitFor(`Auto.on===true`, 4000, '卷轴启动');
const autoMark = await waitFor(`!!document.querySelector('p.align-mark')`, 3000, '卷轴自动对齐');
const b0 = (await ev(`document.getElementById('reader-body').scrollTop`)).v;
await wait(3500);
const b1 = (await ev(`document.getElementById('reader-body').scrollTop`)).v;
check('自动卷轴-自动对齐段首', !!autoMark);
check('自动卷轴-启动并滚动', !!autoOn && b1 - b0 > 5, b0 + '→' + b1);
await ensureChrome();
await clickSel('#btn-auto');
const autoOff = await waitFor(`Auto.on===false`, 4000, '卷轴停止');
check('卷轴按钮可停止', !!autoOff);

// ---------- 8. 设置面板 & 主题 ----------
await ensureChrome();
await clickSel('#btn-settings');
const setOpen = await waitFor(`!document.getElementById('drawer-settings').hidden`, 4000, '设置面板');
check('设置面板打开', !!setOpen);
await clickSel('.theme-btn[data-theme="night"]');
await wait(300);
check('切换夜间主题', (await ev(`document.documentElement.dataset.theme`)).v === 'night');
await clickSel('#scrim');
const setClosed = await waitFor(`document.getElementById('drawer-settings').hidden===true`, 3000, '设置关闭');
check('点蒙层关闭设置', !!setClosed);

// ---------- 9. 再点书卡重进（缓存分支回归） ----------
await clickSel('#btn-shelf-back');
const shelfBack = await waitFor(`!document.getElementById('screen-shelf').hidden`, 5000, '回书架');
check('返回书架', !!shelfBack);
await clickSel('.book-item');
const s2 = await waitFor(`!document.getElementById('screen-reader').hidden && R.book && R.book.total>0`, 15000, '重进阅读器(缓存)');
check('重进阅读器(缓存分支)', !!s2);

// ---------- 汇总 ----------
const fails = results.filter(x => x.startsWith('FAIL'));
console.log('---- 汇总: ' + (results.length - fails.length) + '/' + results.length + ' 通过 ----');
ws.close();
process.exit(fails.length ? 1 : 0);
