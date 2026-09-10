// 验证 MediaButtonReceiver 兜底：会话不在按键名单里时，耳机键仍能续读
// 用 adb 强制把会话踢出"媒体按键会话"（模拟真机上暂停后蓝牙耳机改派到 receiver 的情形）
import { connect } from './cdp_lib.mjs';
import { execSync } from 'node:child_process';
const ADB = 'G:/android-tools/sdk/platform-tools/adb';
const D = '-s 127.0.0.1:16384';
const sh = c => {
  try { return execSync(`${ADB} ${D} shell ${c}`, { encoding: 'utf8' }).replace(/\r/g, ''); }
  catch (e) { return '(err) ' + String(e.stderr || '').slice(0, 160); }
};

const c = await connect();
const { ev, wait } = c;
await ev(`(function(){ if(!R.book && shelfBooks.length) enterBook(shelfBooks[0]); return true; })()`);
await wait(3000);
await c.waitFor(`typeof Tts!=='undefined' && !!R.book`, 15000, 'book opened');
await ev(`(function(){
  B.ttsState = () => Promise.resolve({ok:true, reason:'', busy:false, engines:['mock'], default:'mock'});
  B.ttsSpeak = (t,id) => { window.__speaks=(window.__speaks||0)+1;
    setTimeout(()=>{ try{window.__native('tts',{type:'done',a:id});}catch(e){} }, Math.min(1500, 250+t.length*30)); };
  B.ttsStop=()=>{}; B.ttsPause=()=>{}; window.__speaks=0; return true; })()`);

const S = `JSON.stringify({playing:Tts.playing,paused:Tts.paused,speaks:window.__speaks,
  btn:(document.getElementById('btn-tts-play')||{}).textContent})`;
const show = async t => console.log(`[${t}] ` + (await ev(S)).v);

await ev(`(function(){ const b=parasOf(R.curCh); if(b&&b.length) listenFrom({ch:R.curCh,p:b[0]}); return true; })()`);
await wait(5000);
await show('朗读中');

console.log('>>> 单击耳机 → 暂停');
sh('input keyevent 85'); await wait(3500);
await show('暂停后');

console.log('>>> 让安卓丢弃按键会话（模拟真机暂停后蓝牙改派到 receiver）');
sh('dumpsys media_session --reset-button-session 2>&1');   // 若无效则忽略
const before = sh('dumpsys media_session').match(/Media button session is (.*)/);
console.log('   会话 = ' + (before ? before[1].trim() : '?'));

console.log('>>> 再单击耳机 → 需由 MediaButtonReceiver 兜底续读');
sh('input keyevent 85'); await wait(4500);
await show('再按后');

console.log('=== 兜底接收器是否被触发 ===');
console.log(sh('logcat -b all -d -s MediaButtonReceiver:I ReadAloudService:I'));
process.exit(0);
