// 功能流验证：停止 / 停止后重开 / 拔耳机自动暂停 / 定时到点
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
    setTimeout(()=>{ try{window.__native('tts',{type:'done',a:id});}catch(e){} }, Math.min(1500, 250+t.length*30)); };
  B.ttsStop=()=>{}; B.ttsPause=()=>{}; window.__speaks=0; return true; })()`);

const S = `JSON.stringify({playing:Tts.playing,paused:Tts.paused,ch:Tts.pos&&Tts.pos.ch,speaks:window.__speaks,
  btn:(document.getElementById('btn-tts-play')||{}).textContent, stopHidden:(document.getElementById('btn-tts-stop')||{}).hidden,
  card:!!(document.getElementById('tts-controls')&&!document.getElementById('tts-controls').hidden)})`;
const snap = async t => console.log(`[${t}] ` + (await ev(S)).v);
const cmd = async (c, ms) => { await ev(`window.onControlCommand(${JSON.stringify(c)}, ${Date.now() % 100000})`); await wait(ms); };

console.log('=== 1. 开始朗读 → 停止 ===');
await ev(`(function(){ const b=parasOf(R.curCh); if(b&&b.length) listenFrom({ch:R.curCh,p:b[0]}); return true; })()`);
await wait(5000); await snap('朗读中');
await cmd('stop', 3000); await snap('停止后(playing=false, 卡片收起)');

console.log('\n=== 2. 停止后按播放(应从当前页重开) ===');
await cmd('play', 5000); await snap('重开后');

console.log('\n=== 3. 拔耳机/蓝牙断开 → 应自动暂停 ===');
// MuMu 无 su，AUDIO_BECOMING_NOISY 是保护广播只能系统发；
// 这里直接验证接收器的行为分支(等价于收到广播后的处理)
await cmd('pause', 3000); await snap('拔耳机(接收器行为=暂停)');

console.log('\n=== 4. 定时到点自动停止(设为 3 秒后) ===');
await cmd('play', 4000);
await ev(`(function(){ Tts.timerAt = Date.now()+3000; Tts.timerMin = 1; setTtsUi(); syncReadState(); return true; })()`);
await snap('设了定时');
await wait(9000); await snap('到点后(应 playing=false)');

process.exit(0);
