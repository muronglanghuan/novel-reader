// 端到端：走真实 UI（点书架 → 开书 → 点朗读键），验证按钮文案/停止键/暂停恢复
import { connect } from './cdp_lib.mjs';
import { execSync } from 'node:child_process';
const ADB = 'G:/android-tools/sdk/platform-tools/adb';
const D = '-s 127.0.0.1:16384';
const sh = c => execSync(`${ADB} ${D} shell ${JSON.stringify(c)}`, { encoding: 'utf8' }).replace(/\r/g, '');

const c = await connect();
const { ev, wait, clickSel } = c;
await c.waitFor(`typeof Tts!=='undefined'`, 20000, 'page ready');

// 模拟引擎（模拟器无中文 TTS）；点真实按钮，其余链路都是真的
await ev(`(function(){
  B.ttsState = () => Promise.resolve({ok:true, reason:'', busy:false, engines:['mock'], default:'mock'});
  B.ttsSpeak = (t,id) => { window.__speaks=(window.__speaks||0)+1;
    setTimeout(()=>{ try{window.__native('tts',{type:'done',a:id});}catch(e){} }, Math.min(1500, 250+t.length*30)); };
  B.ttsStop=()=>{}; B.ttsPause=()=>{}; window.__speaks=0; return true; })()`);

const S = `JSON.stringify({screen:(document.getElementById('screen-reader').hidden?'shelf':'reader'),
  book:R.book&&R.book.title, playing:Tts.playing, paused:Tts.paused, speaks:window.__speaks,
  playBtn:(document.getElementById('btn-tts-play')||{}).textContent,
  stopBtn:(document.getElementById('btn-tts-stop')||{}).hidden===false?'可见':'隐藏',
  status:(document.getElementById('tts-status')||{}).textContent})`;
const snap = async t => console.log(`[${t}]\n   ` + (await ev(S)).v);

console.log('— 进入书架并开书 —');
await ev('exitReader(); true').catch(() => {});
await wait(1500);
await ev(`(function(){ if(shelfBooks.length) enterBook(shelfBooks[0]); return true; })()`);
await wait(4000);
await ev(`setChrome(true); true`);
await snap('已进入阅读器');

console.log('\n— 点真实「开始朗读」按钮 —');
await ev(`setChrome(true); true`);
await clickSel('#btn-tts-play');
await wait(5000);
await snap('点朗读后');

console.log('\n— 再点同一按钮（应变成暂停，不是停止）—');
await clickSel('#btn-tts-play');
await wait(2500);
await snap('暂停后');

console.log('\n— 点「停止」按钮 —');
await clickSel('#btn-tts-stop');
await wait(2500);
await snap('停止后');

console.log('\n— 重新朗读（从未读过的位置重开）—');
await ev(`setChrome(true); true`);
await clickSel('#btn-tts-play');
await wait(4000);
await snap('重开朗读');

process.exit(0);
