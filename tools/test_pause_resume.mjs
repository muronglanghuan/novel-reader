// 针对真机反馈「能暂停、再按不继续」的回归测试
// 覆盖两条通路：MediaSession 回调，以及会话缺席时的 MediaButtonReceiver 兜底
import { connect } from './cdp_lib.mjs';
import { execSync } from 'node:child_process';
const ADB = 'G:/android-tools/sdk/platform-tools/adb';
const D = '-s 127.0.0.1:16384';
// 注意：shell 命令里不要出现嵌套引号，Windows 上经 cmd.exe 会解析失败
const sh = c => {
  try { return execSync(`${ADB} ${D} shell ${c}`, { encoding: 'utf8' }).replace(/\r/g, ''); }
  catch (e) { return '(err) ' + String(e.stderr || '').slice(0, 120); }
};

const c = await connect();
const { ev, wait } = c;

// 新装/清数据后会停在书架，先把书打开
await ev(`(function(){ if(!R.book && shelfBooks.length) enterBook(shelfBooks[0]); return true; })()`);
await wait(3000);
await c.waitFor(`typeof Tts!=='undefined' && !!R.book`, 15000, 'book opened');

await ev(`(function(){
  B.ttsState = () => Promise.resolve({ok:true, reason:'', busy:false, engines:['mock'], default:'mock'});
  B.ttsSpeak = (t,id) => { window.__speaks=(window.__speaks||0)+1;
    setTimeout(()=>{ try{window.__native('tts',{type:'done',a:id});}catch(e){} }, Math.min(1500, 250+t.length*30)); };
  B.ttsStop=()=>{}; B.ttsPause=()=>{}; window.__speaks=0; return true; })()`);

const S = `JSON.stringify({playing:Tts.playing,paused:Tts.paused,ch:Tts.pos&&Tts.pos.ch,
  si:Tts.pos&&Tts.pos.sentIdx,speaks:window.__speaks,
  btn:(document.getElementById('btn-tts-play')||{}).textContent})`;
const show = async t => console.log(`[${t}]\n   ` + (await ev(S)).v);
const key = async (code, ms) => { sh(`input keyevent ${code}`); await wait(ms); };

await ev(`(function(){ const b=parasOf(R.curCh); if(b&&b.length) listenFrom({ch:R.curCh,p:b[0]}); return true; })()`);
await wait(5000);
await show('1 开始朗读');

console.log('>>> 单击耳机 85 应暂停');
await key(85, 3500);
await show('2 暂停后');

console.log('>>> 再单击 85 应继续  <-- 真机卡在这里');
await key(85, 4000);
await show('3 再按后');

console.log('>>> 第三次单击 应再次暂停');
await key(85, 3500);
await show('4 第三次按');

console.log('>>> 第四次单击 应再次继续');
await key(85, 4000);
await show('5 第四次按');

console.log('=== media button session ===');
console.log(sh('dumpsys media_session'));
console.log('=== pending state ===');
console.log(sh('run-as com.like.novelreader cat shared_prefs/pending.xml'));
process.exit(0);
