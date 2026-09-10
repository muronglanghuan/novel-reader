// 耳机/蓝牙按键联调：media dispatch 走的正是蓝牙 AVRCP 按键的框架路径
import { connect } from './cdp_lib.mjs';
import { execSync } from 'node:child_process';
const ADB = 'G:/android-tools/sdk/platform-tools/adb';
const D = '-s 127.0.0.1:16384';
const sh = c => execSync(`${ADB} ${D} shell ${JSON.stringify(c)}`, { encoding: 'utf8' }).replace(/\r/g, '');

const c = await connect();
const { ev, wait } = c;
await c.waitFor(`typeof Tts!=='undefined' && !!R.book`, 15000, 'page ready');

await ev(`(function(){
  B.ttsState = () => Promise.resolve({ok:true, reason:'', busy:false, engines:['mock'], default:'mock'});
  B.ttsSpeak = (t,id) => { window.__speaks=(window.__speaks||0)+1;
    setTimeout(()=>{ try{window.__native('tts',{type:'done',a:id});}catch(e){} }, Math.min(2000, 250+t.length*35)); };
  B.ttsStop = () => {}; B.ttsPause = () => {};
  window.__speaks = 0;
  return true; })()`);

const S = `JSON.stringify({playing:Tts.playing,paused:Tts.paused,ch:Tts.pos&&Tts.pos.ch,si:Tts.pos&&Tts.pos.sentIdx,
  speaks:window.__speaks, btn:(document.getElementById('btn-tts-play')||{}).textContent,
  stopHidden:(document.getElementById('btn-tts-stop')||{}).hidden,
  status:(document.getElementById('tts-status')||{}).textContent})`;
const snap = async tag => console.log(`[${tag}]\n   ` + (await ev(S)).v);

// --- 开始朗读
await ev(`(function(){ const b=parasOf(R.curCh); if(b&&b.length) listenFrom({ch:R.curCh,p:b[0]}); return true; })()`);
await wait(5000);
await snap('1. 开始朗读');

// --- 耳机单击：暂停
console.log('\n>>> media dispatch play-pause (耳机单击)');
sh('input keyevent 85'); await wait(2500);
await snap('2. 单击后(应 paused=true, 保留 si)');
const a = JSON.parse((await ev(S)).v);

// --- 再单击：继续
console.log('\n>>> media dispatch play-pause (再单击 = 继续)');
sh('input keyevent 85'); await wait(3500);
await snap('3. 继续后(应 paused=false, 且 si 未被重置为 0)');

// --- 双击 = 下一章
console.log('\n>>> media dispatch next (双击耳机)');
sh('input keyevent 87'); await wait(4000);
await snap('4. 下一章后(应 ch+1)');

// --- 三击/上一章
console.log('\n>>> media dispatch previous');
sh('input keyevent 88'); await wait(4000);
await snap('5. 上一章后(应回到原章节)');

process.exit(0);
