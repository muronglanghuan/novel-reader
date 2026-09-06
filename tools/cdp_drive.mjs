// CDP 驱动脚本：连接 adb 转发的 WebView devtools，跑 devtest 并输出文本结果
// 用法: node cdp_drive.mjs [url] [超时ms]
const BASE = 'http://127.0.0.1:9222';
const url = process.argv[2] || 'https://appassets.androidplatform.net/assets/index.html?devtest=1';
const timeoutMs = +(process.argv[3] || 90000);

async function getTarget() {
  for (let i = 0; i < 20; i++) {
    try {
      const list = await (await fetch(BASE + '/json/list')).json();
      const t = list.find(x => x.type === 'page' && /appassets/.test(x.url || ''));
      if (t) return t;
    } catch (e) {}
    await new Promise(r => setTimeout(r, 500));
  }
  throw new Error('找不到 WebView target');
}

const target = await getTarget();
console.log('target:', target.url);
const ws = new WebSocket(target.webSocketDebuggerUrl);
let seq = 0;
const pend = new Map();
ws.onmessage = ev => {
  const m = JSON.parse(ev.data);
  if (m.id && pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); }
};
const send = (method, params) => new Promise((res, rej) => {
  const id = ++seq;
  pend.set(id, m => m.error ? rej(new Error(m.error.message)) : res(m.result));
  ws.send(JSON.stringify({ id, method, params: params || {} }));
});
await new Promise(r => ws.onopen = r);

await send('Page.enable');
await send('Runtime.enable');

// 导航到带 devtest 参数的页面
await send('Page.navigate', { url });
const wait = ms => new Promise(r => setTimeout(r, ms));

let done = false;
for (let i = 0; i < timeoutMs / 1000; i++) {
  await wait(1000);
  const r = await send('Runtime.evaluate', {
    expression: `document.title || ''`, returnByValue: true,
  }).catch(() => null);
  const title = r && r.result && r.result.value ? String(r.result.value) : '';
  if (/^DEV(PASS|FAIL)/.test(title)) { done = true; break; }
}
const out = await send('Runtime.evaluate', {
  expression: `(() => { const p = document.getElementById('devtest-out');
    return { title: document.title, text: p ? p.textContent : '(no node)', html: document.body ? document.body.innerHTML.length : -1 }; })()`,
  returnByValue: true,
}).catch(() => null);
console.log('RESULT title =', out && out.result && out.result.value ? out.result.value.title : '?');
const text = out && out.result && out.result.value ? out.result.value.text : '(无法读取)';
console.log('--- devtest-out ---');
console.log(text);
console.log('done =', done);
ws.close();
process.exit(0);
