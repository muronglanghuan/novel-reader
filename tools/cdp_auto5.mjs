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
  return { v: r.result.value, err: r.result.exceptionDetails ? r.result.exceptionDetails.exception?.description?.slice(0,200) : null };
}
// 纯 JS 高频自增测试（绕开 autoStep）
console.log('A. interval 自增 0.2px/16ms:',
  JSON.stringify((await ev(`(function(){ return new Promise(res=>{
    const b=document.getElementById('reader-body');
    const st=[]; b.scrollTop+=0.2; 
    const iv=setInterval(()=>{ st.push(Math.round(b.scrollTop*10)/10); if(st.length>=20){clearInterval(iv); res(JSON.stringify(st.slice(0,6))+ ' ... ' + JSON.stringify(st.slice(-2)));} },100);
  }); })()`)).v));
// auto 运行中高频采样
console.log('B. auto 运行采样:',
  JSON.stringify((await ev(`(function(){ return new Promise(res=>{
    stopAuto();
    const b=document.getElementById('reader-body');
    Auto.last=performance.now()-1000; startAuto();
    const st=[];
    const iv=setInterval(()=>{ st.push(Math.round(b.scrollTop*10)/10); if(st.length>=16){clearInterval(iv); stopAuto(); res(JSON.stringify(st));} },120);
  }); })()`)).v));
ws.close(); process.exit(0);
