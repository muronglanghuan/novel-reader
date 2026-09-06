const list = await (await fetch('http://127.0.0.1:9222/json/list')).json();
const t = list.find(x => x.type === 'page' && /appassets/.test(x.url || ''));
const ws = new WebSocket(t.webSocketDebuggerUrl);
let seq = 0; const pend = new Map();
ws.onmessage = ev => { const m = JSON.parse(ev.data); if (m.id && pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); } };
const send = (method, params) => new Promise((res, rej) => { const id = ++seq; pend.set(id, m => m.error ? rej(new Error(m.error.message)) : res(m.result)); ws.send(JSON.stringify({ id, method, params: params || {} })); });
await new Promise(r => ws.onopen = r);
async function ev(expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true });
  if (r.result?.exceptionDetails) return { err: r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text };
  return { v: r.result.value };
}
console.log('restore:', (await ev(`JSON.stringify(JSON.parse(NovelBridge.tryRestoreExternal()))`)).v);
console.log('lastBook:', JSON.stringify((await ev(`NovelBridge.loadLastBook()`)).v));
console.log('progress len:', JSON.stringify((await ev(`NovelBridge.loadProgress('a:哇！爆率真的很高.txt')`)).v || '').length);
console.log('screens:', (await ev(`JSON.stringify({shelfHidden: document.getElementById('screen-shelf').hidden, readerHidden: document.getElementById('screen-reader').hidden})`)).v);
ws.close(); process.exit(0);
