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
console.log('+2 十次:', JSON.stringify((await ev(`(function(){return new Promise(res=>{
  const b=document.getElementById('reader-body'); const seq=[];
  let n=0; const iv=setInterval(()=>{ b.scrollTop+=2; seq.push(Math.round(b.scrollTop)); if(++n>=10){clearInterval(iv);res(seq);} },120);
})})()`)).v));
console.log('+1 十次:', JSON.stringify((await ev(`(function(){return new Promise(res=>{
  const b=document.getElementById('reader-body'); const seq=[];
  let n=0; const iv=setInterval(()=>{ b.scrollTop+=1; seq.push(Math.round(b.scrollTop)); if(++n>=10){clearInterval(iv);res(seq);} },120);
})})()`)).v));
ws.close(); process.exit(0);
