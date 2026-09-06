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
  return { v: r.result.value, err: r.result.exceptionDetails ? (r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text).slice(0,200) : null };
}
const run = async (label, expr) => console.log(label, ':', JSON.stringify((await ev(expr)).v));
await run('1 大幅+500', `(function(){const b=document.getElementById('reader-body'); b.scrollTop+=500; return b.scrollTop;})()`);
await run('2 恢复', `(function(){const b=document.getElementById('reader-body'); b.scrollTop-=500; return b.scrollTop;})()`);
await run('3 强制布局后+0.2', `(function(){const b=document.getElementById('reader-body'); void b.offsetHeight; b.scrollTop+=0.2; void b.offsetHeight; return b.scrollTop;})()`);
await run('4 大跳到远端再小增', `(function(){const b=document.getElementById('reader-body'); b.scrollTop=50000; const r1=b.scrollTop; b.scrollTop+=0.2; const r2=b.scrollTop; return [r1,r2];})()`);
await run('5 直接赋小数位', `(function(){const b=document.getElementById('reader-body'); b.scrollTop=27043.55; return b.scrollTop;})()`);
await run('6 元素/文档滚动', `JSON.stringify({sTop: document.scrollingElement.scrollTop, inner: window.scrollY})`);
await run('7 smooth动画占用?', `JSON.stringify({transition: getComputedStyle(document.getElementById('reader-body')).scrollBehavior})`);
ws.close(); process.exit(0);
