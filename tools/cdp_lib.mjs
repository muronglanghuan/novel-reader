// CDP 公共库：连接 WebView 调试端，提供 ev/wait/click/手势/截图等
export async function connect(port = 9222) {
  const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  const t = list.find(x => x.type === 'page' && /appassets/.test(x.url || ''));
  if (!t) throw new Error('no webview target');
  const ws = new WebSocket(t.webSocketDebuggerUrl);
  let seq = 0; const pend = new Map();
  ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); } };
  await new Promise(r => ws.onopen = r);
  const send = (method, params) => new Promise((res, rej) => {
    const id = ++seq;
    pend.set(id, m => m.error ? rej(new Error(m.error.message)) : res(m.result));
    ws.send(JSON.stringify({ id, method, params: params || {} }));
  });
  const wait = ms => new Promise(r => setTimeout(r, ms));

  async function ev(expression) {
    const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) {
      return { err: r.exceptionDetails.exception?.description || r.exceptionDetails.text };
    }
    return { v: r.result.value };
  }
  async function waitFor(expr, timeoutMs, label) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeoutMs) {
      const r = await ev(expr);
      if (r.v === true || (typeof r.v === 'string' && r.v !== 'false' && r.v !== '')) return r.v;
      if (r.err) { console.log('  err[' + label + ']: ' + r.err); return null; }
      await wait(250);
    }
    console.log('  超时: ' + label);
    return null;
  }
  async function rectOf(sel) {
    const r = await ev(`(function(){const el=document.querySelector(${JSON.stringify(sel)});
      if(!el) return null; const b=el.getBoundingClientRect();
      return {x:b.x+b.width/2,y:b.y+b.height/2,top:b.top,bottom:b.bottom,left:b.left,right:b.right,w:b.width,h:b.height};})()`);
    return r.v || null;
  }
  async function clickAt(x, y) {
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
    await wait(70);
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
    await wait(150);
  }
  // CDP 的 Input.dispatch* 坐标是 CSS 像素（页面视口坐标系），不要乘 dpr ——
  // 乘了会点到别处（表现为"命中预检通过但按钮无反应"）
  let dpr = 1;
  async function clickSel(sel, opts = {}) {
    let rc = await rectOf(sel);
    if (!rc) { console.log('  no element: ' + sel); return false; }
    const vh = (await ev('innerHeight')).v;
    for (let k = 0; k < 3 && (rc.top < 0 || rc.bottom > vh); k++) {
      await ev(`document.querySelector(${JSON.stringify(sel)}).scrollIntoView({block:'center'});true`);
      await wait(400); rc = await rectOf(sel);
    }
    if (!rc || rc.w === 0) { console.log('  invisible: ' + sel); return false; }
    // 命中预检：坐标处应就是目标元素（防止样式/遮挡导致的误点）
    const hit = await ev(`(function(){const el=document.querySelector(${JSON.stringify(sel)});
      const b=el.getBoundingClientRect();const h=document.elementFromPoint(b.x+b.width/2,b.y+b.height/2);
      return !!(h&&(h===el||el.contains(h)));})()`);
    if (hit.v !== true) { console.log('  命中预检失败: ' + sel); return false; }
    await clickAt(rc.x * dpr, rc.y * dpr);
    return true;
  }
  async function tapSel(sel) {
    const rc = await rectOf(sel);
    if (!rc) return false;
    const x = rc.x * dpr, y = rc.y * dpr;
    await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
    await wait(60);
    await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await wait(150);
    return true;
  }
  return { send, ev, wait, waitFor, rectOf, clickAt, clickSel, tapSel, ws, setDpr: d => { dpr = d; } };
}
