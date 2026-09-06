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
  if (r.result?.exceptionDetails) return { err: r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text };
  return { v: r.result.value };
}
console.log('before:', (await ev(`JSON.stringify({readerHidden: document.getElementById('screen-reader').hidden, shelfHidden: document.getElementById('screen-shelf').hidden, chrome: chromeOn})`)).v);
console.log('exitReader err:', (await ev(`(function(){ try{ exitReader(); return 'ok'; }catch(e){ return 'EXC '+e; } })()`)).err || (await ev(`(function(){ try{ exitReader(); return 'ok'; }catch(e){ return 'EXC '+e; } })()`)).v);
await wait(600);
console.log('after:', (await ev(`JSON.stringify({readerHidden: document.getElementById('screen-reader').hidden, shelfHidden: document.getElementById('screen-shelf').hidden, books: document.querySelectorAll('.book-item').length})`)).v);
ws.close(); process.exit(0);
