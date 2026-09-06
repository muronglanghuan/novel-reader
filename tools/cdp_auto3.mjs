const list = await (await fetch('http://127.0.0.1:9222/json/list')).json();
const t = list.find(x => x.type === 'page' && /appassets/.test(x.url || ''));
const ws = new WebSocket(t.webSocketDebuggerUrl);
let seq = 0; const pend = new Map(); const exc = [];
ws.onmessage = ev => { const m = JSON.parse(ev.data);
  if (m.id && pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); }
  else if (m.method === 'Runtime.exceptionThrown') exc.push((m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text).slice(0, 400));
  else if (m.method === 'Runtime.consoleAPICalled') exc.push('[c]' + m.params.args.map(a => a.value ?? '').join(' ').slice(0, 200)); };
const send = (method, params) => new Promise((res, rej) => { const id = ++seq; pend.set(id, m => m.error ? rej(new Error(m.error.message)) : res(m.result)); ws.send(JSON.stringify({ id, method, params: params || {} })); });
await new Promise(r => ws.onopen = r);
await send('Runtime.enable');
const wait = ms => new Promise(r => setTimeout(r, ms));
async function ev(expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true });
  return { v: r.result.value, err: r.result.exceptionDetails ? (r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text).slice(0,300) : null };
}
await ev(`stopAuto(); startAuto(); true`);
await wait(3500);
console.log('auto.on=', (await ev(`Auto.on`)).v, ' scrollTop=', (await ev(`document.getElementById('reader-body').scrollTop`)).v);
console.log('exception/console 捕获:');
exc.slice(0, 10).forEach(e => console.log('  ', e));
await ev(`stopAuto(); true`);
ws.close(); process.exit(0);
