// 后台/息屏场景：切后台后朗读是否继续、耳机键是否仍生效
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
    setTimeout(()=>{ try{window.__native('tts',{type:'done',a:id});}catch(e){} }, Math.min(1800, 250+t.length*35)); };
  B.ttsStop=()=>{}; B.ttsPause=()=>{}; window.__speaks=0; return true; })()`);

const S = `JSON.stringify({playing:Tts.playing,paused:Tts.paused,ch:Tts.pos&&Tts.pos.ch,speaks:window.__speaks,vis:document.visibilityState})`;
const snap = async t => console.log(`[${t}] ` + (await ev(S)).v);

await ev(`(function(){ const b=parasOf(R.curCh); if(b&&b.length) listenFrom({ch:R.curCh,p:b[0]}); return true; })()`);
await wait(5000);
await snap('前台');

console.log('\n--- 切到后台 ---');
sh('input keyevent 3'); await wait(2000);
for (let i = 0; i < 3; i++) { await wait(6000); await snap('后台 +' + (i + 1) * 6 + 's'); }

console.log('\n--- 后台按耳机键 ---');
sh('input keyevent 85'); await wait(3500);
await snap('后台单击(应暂停)');
sh('input keyevent 85'); await wait(3500);
await snap('后台再单击(应继续)');
sh('input keyevent 87'); await wait(4000);
await snap('后台双击(应下一章)');

process.exit(0);
