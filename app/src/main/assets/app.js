'use strict';
/* =====================================================================
 * 小说有声阅读 —— 前端逻辑（单文件）
 * 视图：书架 / 阅读器（卷轴正文 + 目录抽屉 + 设置）
 * 桥：window.NovelBridge（安卓原生）/ 内联 Dev 后端（桌面浏览器调试）
 * =================================================================== */
const $ = id => document.getElementById(id);
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const sleep = ms => new Promise(r => setTimeout(r, ms));

/* =====================================================================
 * 桥后端
 * =================================================================== */
const isNative = typeof window.NovelBridge !== 'undefined';
const fire = (type, payload) => { try { window.__native && window.__native(type, payload); } catch (e) {} };

window.__native = (type, payload) => {
  if (type === 'tts') onTtsEvent(payload);
  else if (type === 'booksChanged') refreshShelf();
};

/* ---------- Dev 后端（桌面调试：fetch 本地书 + speechSynthesis） ---------- */
const Dev = (() => {
  let meta = null;
  const HEAD = /^\s*第([0-9０-９〇零一二三四五六七八九十百千万两]+)[章节回卷部篇集]/;
  const dnum = s => {
    s = s.trim().replace(/[０-９]/g, c => String.fromCharCode(c.charCodeAt(0) - 0xFEE0))
         .replace(/[〇零]/g, '0').replace(/两/g, '2');
    if (/^\d+$/.test(s)) { const n = parseInt(s, 10); return n > 0 ? n : -1; }
    const map = {一:1,二:2,三:3,四:4,五:5,六:6,七:7,八:8,九:9,十:10,百:100,千:1000};
    let total = 0, cur = 0;
    for (const ch of s) {
      const v = map[ch];
      if (!v) return -1;
      if (v >= 10) { if (!cur) cur = 1; total += cur * v; cur = 0; } else cur = v;
    }
    return total + cur;
  };
  function parse(text) {
    const offs = [], lines = text.split('\n');
    let off = 0;
    for (const ln of lines) { offs.push(off); off += ln.length + 1; }
    const st = [], hs = [];
    lines.forEach((ln, i) => { const m = HEAD.exec(ln.trim()); if (m) { st.push(offs[i]); hs.push(ln.trim()); } });
    const cb = [], ct = [];
    for (let i = 0; i < st.length; i++) {
      let s = st[i]; const e = i + 1 < st.length ? st[i + 1] : text.length;
      const seg = text.substring(s, Math.min(e, s + 80));
      const nl = seg.indexOf('\n');
      const fl = (nl < 0 ? seg : seg.substring(0, nl)).trim();
      const fm = HEAD.exec(fl);
      if (fm && dnum(fm[1]) === dnum(HEAD.exec(hs[i].trim())[1])) s += fl.length + 1;
      cb.push([s, e]); ct.push(hs[i]);
    }
    if (st.length && st[0] > 0 && text.substring(0, st[0]).trim()) { cb.unshift([0, st[0]]); ct.unshift('开头'); }
    if (!ct.length) { cb.push([0, text.length]); ct.push('正文'); }
    const out = [];
    for (let i = 0; i < cb.length; i++) {
      if (text.substring(cb[i][0], cb[i][1]).trim()) out.push({ t: ct[i], s: cb[i][0], e: cb[i][1] });
    }
    return out;
  }
  // 桌面浏览器调试用：把想测试的 txt 放进 assets 同级本地目录即可；真机 APK 不走此路径
  const BOOKS = [];
  async function load(id) {
    if (meta && meta.id === id) return meta;
    const r = await fetch('books/' + encodeURIComponent(id.slice(2)));
    if (!r.ok) throw new Error('取书失败');
    const text = await r.text();
    const chapters = parse(text).map((c, i) => ({ i, t: c.t, s: c.s, e: c.e }));
    meta = { id, title: id.slice(2).replace(/\.txt$/i, '').replace(/\{.*?\}|【.*?】/g, '').trim(), text, chapters };
    return meta;
  }
  let voicesReady = false;
  if (typeof speechSynthesis !== 'undefined') {
    speechSynthesis.onvoiceschanged = () => { voicesReady = true; };
    setTimeout(() => { voicesReady = true; }, 800);
  }
  const voiceOk = () => typeof speechSynthesis !== 'undefined' && voicesReady
        && speechSynthesis.getVoices().some(v => /zh|cmn/i.test(v.lang || ''));
  return {
    async listBooks() {
      const books = [];
      for (const n of BOOKS) {
        try { await fetch('books/' + encodeURIComponent(n)); books.push({ id: 'a:' + n, title: n.replace(/\.txt$/i, ''), builtin: true }); }
        catch (e) {}
      }
      return { ok: true, books };
    },
    async openBook(id) {
      const m = await load(id);
      return { ok: true, title: m.title, chapters: m.chapters.map(c => ({ i: c.i, t: c.t })), total: m.chapters.length };
    },
    async getChapter(id, i) {
      const m = await load(id);
      const c = m.chapters[i];
      return c ? { ok: true, i, t: c.t, text: m.text.substring(c.s, c.e) } : { ok: false, error: '越界' };
    },
    saveProgress(id, o) { localStorage.setItem('p:' + id, JSON.stringify(o)); },
    loadProgress(id) { const s = localStorage.getItem('p:' + id); return s ? JSON.parse(s) : null; },
    saveSettings(o) { localStorage.setItem('settings', JSON.stringify(o)); },
    loadSettings() { const s = localStorage.getItem('settings'); return s ? JSON.parse(s) : null; },
    importBook() { console.log('[dev] 导入请用安卓版'); },
    deleteBook(id) { return { ok: false, error: '内置书不可删除(桌面调试)' }; },
    saveLastBook(id) { localStorage.setItem('lastBook', id); },
    loadLastBook() { return localStorage.getItem('lastBook') || ''; },
    clearLastBook() { localStorage.removeItem('lastBook'); },
    exportProgress() {},
    tryRestoreExternal() { return { restored: false, count: 0 }; },
    restoreProgress() { console.log('[dev] 恢复请用安卓版'); },
    ttsState() { return { ok: voiceOk(), reason: voiceOk() ? '' : '桌面无中文语音，朗读走模拟进度' }; },
    ttsSpeak(t, id) {
      if (voiceOk()) {
        const u = new SpeechSynthesisUtterance(t);
        const v = speechSynthesis.getVoices().filter(x => /zh|cmn/i.test(x.lang || ''));
        if (v.length) u.voice = v[0];
        u.rate = (Dev._rate || 1);
        u.onstart = () => fire('tts', { type: 'start', a: id });
        u.onend = () => fire('tts', { type: 'done', a: id });
        u.onerror = () => fire('tts', { type: 'error', a: id, b: 'E' });
        speechSynthesis.speak(u);
      } else {
        // 无语音：定时器模拟，供流程调试
        setTimeout(() => fire('tts', { type: 'done', a: id }), Math.min(2000, 120 + t.length * 18));
      }
    },
    ttsStop() { speechSynthesis && speechSynthesis.cancel(); },
    ttsPause() { speechSynthesis && speechSynthesis.pause(); },
    ttsResume() { speechSynthesis && speechSynthesis.resume(); },
    ttsSetRate(r) { Dev._rate = r; },
    keepScreenOn() {},
    toast(m) { console.log('[toast]', m); },
  };
})();

const B = {
  listBooks: () => Promise.resolve(isNative ? JSON.parse(NovelBridge.getBookList()) : Dev.listBooks()),
  openBook: id => Promise.resolve(isNative ? JSON.parse(NovelBridge.openBook(id)) : Dev.openBook(id)),
  getChapter: (id, i) => Promise.resolve(isNative ? JSON.parse(NovelBridge.getChapter(id, i)) : Dev.getChapter(id, i)),
  saveProgress: (id, o) => (isNative ? NovelBridge.saveProgress(id, JSON.stringify(o)) : Dev.saveProgress(id, o)),
  loadProgress: id => Promise.resolve(isNative
      ? (NovelBridge.loadProgress(id) || null) && JSON.parse(NovelBridge.loadProgress(id))
      : Dev.loadProgress(id)),
  saveSettings: o => (isNative ? NovelBridge.saveSettings(JSON.stringify(o)) : Dev.saveSettings(o)),
  loadSettings: () => Promise.resolve(isNative
      ? (NovelBridge.loadSettings() || null) && JSON.parse(NovelBridge.loadSettings())
      : Dev.loadSettings()),
  importBook: () => (isNative ? NovelBridge.importBook() : Dev.importBook()),
  deleteBook: id => Promise.resolve(isNative ? JSON.parse(NovelBridge.deleteBook(id)) : Dev.deleteBook(id)),
  saveLastBook: id => (isNative ? NovelBridge.saveLastBook(id) : Dev.saveLastBook(id)),
  loadLastBook: () => Promise.resolve(isNative ? (NovelBridge.loadLastBook() || '') : Dev.loadLastBook()),
  clearLastBook: () => (isNative ? NovelBridge.clearLastBook() : Dev.clearLastBook()),
  exportProgress: () => (isNative ? NovelBridge.exportProgress() : Dev.exportProgress()),
  tryRestoreExternal: () => Promise.resolve(isNative
      ? JSON.parse(NovelBridge.tryRestoreExternal()) : Dev.tryRestoreExternal()),
  restoreProgress: () => (isNative ? NovelBridge.restoreProgress() : Dev.restoreProgress()),
  ttsState: () => Promise.resolve(isNative ? JSON.parse(NovelBridge.ttsState()) : Dev.ttsState()),
  openTtsSettings: () => { if (isNative) NovelBridge.openTtsSettings(); },
  ttsSpeak: (t, id) => (isNative ? NovelBridge.ttsSpeak(t, id) : Dev.ttsSpeak(t, id)),
  ttsStop: () => (isNative ? NovelBridge.ttsStop() : Dev.ttsStop()),
  ttsPause: () => (isNative ? NovelBridge.ttsPause() : Dev.ttsPause()),
  ttsResume: () => (isNative ? NovelBridge.ttsResume() : Dev.ttsResume()),
  ttsSetRate: r => (isNative ? NovelBridge.ttsSetRate(r) : Dev.ttsSetRate(r)),
  keepScreenOn: b => (isNative ? NovelBridge.keepScreenOn(b) : Dev.keepScreenOn(b)),
  toast: m => (isNative ? NovelBridge.toast(m) : Dev.toast(m)),
};

/* =====================================================================
 * 设置
 * =================================================================== */
const Settings = {
  defaults: { font: 19, lineH: 1.9, theme: 'sepia', rate: 1.0, autoCpm: 300 },
  cur: null,
  async load() {
    let s = null;
    try { s = await B.loadSettings(); } catch (e) {}
    this.cur = Object.assign({}, this.defaults, s || {});
  },
  save() { B.saveSettings(this.cur); },
  apply() {
    document.documentElement.dataset.theme = this.cur.theme;
    const h = $('content-holder');
    h.style.setProperty('--fs', this.cur.font + 'px');
    h.style.setProperty('--lh', this.cur.lineH);
    $('set-font').value = this.cur.font;
    $('set-font-val').textContent = this.cur.font + 'px';
    $('set-lineh').value = this.cur.lineH;
    $('set-lineh-val').textContent = this.cur.lineH.toFixed(1);
    $('tts-rate').value = this.cur.rate;
    $('tts-rate-val').textContent = this.cur.rate.toFixed(2) + '×';
    $('auto-speed').value = this.cur.autoCpm;
    $('auto-speed-val').textContent = this.cur.autoCpm + '字/分';
    document.querySelectorAll('.theme-btn').forEach(b => b.classList.toggle('on', b.dataset.theme === this.cur.theme));
  },
};

/* =====================================================================
 * 书架
 * =================================================================== */
let shelfBooks = [];            // 当前书单（boot 直达续读用）
async function refreshShelf() {
  let r;
  try { r = await B.listBooks(); } catch (e) { r = { ok: false }; }
  if (!r || !r.ok) { $('book-list').textContent = ''; return; }
  const list = $('book-list');
  list.textContent = '';
  const books = r.books || [];
  shelfBooks = books;
  if (!books.length) { $('shelf-hint').hidden = false; return; }
  $('shelf-hint').hidden = true;
  for (const b of books) {
    let prog = null;
    try { prog = await B.loadProgress(b.id); } catch (e) {}
    const item = document.createElement('div');
    item.className = 'book-item';
    const head = document.createElement('div');
    head.className = 'book-item-head';
    const title = document.createElement('div');
    title.className = 'book-title';
    title.textContent = b.title;
    head.appendChild(title);
    if (!b.builtin) {
      const del = document.createElement('button');
      del.className = 'btn-del';
      del.textContent = '删除';
      del.addEventListener('click', e => { e.stopPropagation(); showDeleteDialog(b); });
      head.appendChild(del);
    }
    item.appendChild(head);
    const meta = document.createElement('div');
    meta.className = 'book-meta';
    meta.textContent = (prog && prog.ch != null)
        ? '读到：' + (prog.t || ('第' + (prog.ch + 1) + '章')) + ' · 点击继续'
        : '未读' + (b.builtin ? ' · 内置' : ' · 已导入');
    item.appendChild(meta);
    if (prog && prog.ch != null) {
      const track = document.createElement('div');
      track.className = 'prog-track';
      const fill = document.createElement('div');
      fill.className = 'prog-bar';
      fill.style.width = clamp((prog.ch + 1) / Math.max(1, prog.total || 617) * 100, 2, 100) + '%';
      track.appendChild(fill);
      item.appendChild(track);
    }
    item.addEventListener('click', () => enterBook(b));
    list.appendChild(item);
  }
}

/* ---------- 删除确认弹窗 ---------- */
let pendingDeleteBook = null;
function showDeleteDialog(b) {
  pendingDeleteBook = b;
  $('dlg-delete-title').textContent = '删除《' + b.title + '》？';
  $('dlg-delete-text').textContent = '将从本机删除该书文件与阅读进度，不可恢复。';
  $('dlg-delete').hidden = false;
}
function closeDeleteDialog() {
  $('dlg-delete').hidden = true;
  pendingDeleteBook = null;
}
async function confirmDeleteBook() {
  const b = pendingDeleteBook;
  closeDeleteDialog();
  if (!b) return;
  const r = await B.deleteBook(b.id);
  if (!r || !r.ok) { B.toast((r && r.error) || '删除失败'); }
  else { B.toast('已删除《' + b.title + '》'); }
  refreshShelf();
}

/* =====================================================================
 * 阅读器渲染
 * =================================================================== */
const R = {
  book: null,          // {id,title,chapters:[{i,t}],total}
  loaded: new Map(),   // idx -> {idx, el, paras:[p]}
  inflight: new Map(), // idx -> Promise（并发拉取去重）
  holder: null, body: null,
  curCh: 0,
  saveTimer: 0,
};
const PAD_TOP = 26;
const viewTop = () => R.body.scrollTop;
const viewH = () => R.body.clientHeight;

/* 已加载章节按 DOM 文档顺序返回（视口几何判断必须以文档顺序为准） */
function sectsInDom() {
  const out = [];
  if (!R.holder) return out;
  for (const el of R.holder.children) {
    if (el.classList && el.classList.contains('chapter-body')) {
      const rec = R.loaded.get(Number(el.dataset.ch));
      if (rec) out.push(rec);
    }
  }
  return out;
}

async function fetchChapter(idx) {
  if (idx < 0 || !R.book || idx >= R.book.total) return null;
  if (R.loaded.has(idx)) return R.loaded.get(idx);
  if (R.inflight.has(idx)) return R.inflight.get(idx);   // 并发去重
  const task = (async () => {
    const r = await B.getChapter(R.book.id, idx);
    if (!r.ok) { B.toast('章节加载失败'); return null; }
    const sect = document.createElement('section');
    sect.className = 'chapter-body';
    sect.dataset.ch = idx;
    const head = document.createElement('div');
    head.className = 'chapter-head';
    head.textContent = r.t;
    sect.appendChild(head);
    const paras = [];
    const lines = String(r.text || '').split('\n');
    for (const raw of lines) {
      const t = raw.replace(/^[　\t ]+/, '').replace(/[　\t ]+$/, '');
      if (!t) continue;
      const p = document.createElement('p');
      p.textContent = t;
      p._raw = t;
      sect.appendChild(p);
      paras.push(p);
    }
    // 按章节序号插入，保持 DOM 严格升序（跨章跳转时可避免乱序）
    let before = null;
    for (const s of sectsInDom()) {
      if (s.idx > idx) { before = s.el; break; }
    }
    if (before) R.holder.insertBefore(sect, before);
    else R.holder.appendChild(sect);
    const rec = { idx, el: sect, paras };
    R.loaded.set(idx, rec);
    return rec;
  })();
  R.inflight.set(idx, task);
  try {
    return await task;
  } finally {
    R.inflight.delete(idx);
  }
}

/* 确保视口章节 ±1 已加载；回收远在视口上方的章节 */
function ensureWindow(ch) {
  for (const i of [ch - 1, ch, ch + 1]) {
    if (i >= 0 && R.book && i < R.book.total && !R.loaded.has(i)) {
      fetchChapter(i).catch(() => {});
    }
  }
  prune();
}
function prune() {
  const sects = sectsInDom();
  for (const s of sects) {
    if (s.idx >= R.curCh - 1) continue;
    const rc = s.el.getBoundingClientRect();
    if (rc.bottom > -viewH() * 1.2) continue;
    const h = s.el.offsetHeight || 0;
    if (h <= 0) continue;
    R.body.scrollTop -= h;      // 补偿：内容上移高度，视觉无跳
    s.el.remove();
    R.loaded.delete(s.idx);
  }
}

function parasOf(ch) {
  const s = R.loaded.get(ch);
  return s ? s.paras : null;
}

/* 视口顶部第一个段落：{ch,p,idx}（按文档顺序扫描） */
function topParagraph() {
  const sects = sectsInDom();
  for (const s of sects) {
    if (s.el.getBoundingClientRect().bottom <= 4) continue;
    for (let i = 0; i < s.paras.length; i++) {
      const r = s.paras[i].getBoundingClientRect();
      if (r.bottom > 4) return { ch: s.idx, p: s.paras[i], idx: i };
    }
    return { ch: s.idx, p: null, idx: -1 };
  }
  return null;
}

/* =====================================================================
 * 对齐：从当前页第一个完整段落开始
 * =================================================================== */
let alignMarkTimer = 0;
function clearAlignMarks() {
  document.querySelectorAll('p.align-mark').forEach(p => p.classList.remove('align-mark'));
}
function scheduleClearMark() {
  if (alignMarkTimer) clearTimeout(alignMarkTimer);
  alignMarkTimer = setTimeout(clearAlignMarks, 3000);
}

/* 返回对齐目标段；opts.listen=true 时开始朗读 */
function alignToPageStart(opts) {
  opts = opts || {};
  const vh = viewH(), vt = viewTop();
  const sects = sectsInDom();
  const cands = [];
  for (const s of sects) {
    const sr = s.el.getBoundingClientRect();
    if (sr.bottom <= 4) continue;
    if (sr.top >= vh) break;
    for (const p of s.paras) {
      const r = p.getBoundingClientRect();
      if (r.bottom <= 4) continue;
      if (r.top >= vh) break;
      cands.push({ p, ch: s.idx, top: r.top, bottom: r.bottom });
    }
  }
  if (!cands.length) return null;
  let target = cands[0];
  if (target.top <= 4) {
    // 当前首段被切头 → 若切得很少(顶部35%屏内)退回该段首，否则用下一完整段
    const cutH = -target.top;
    if (cands.length > 1 && cutH > vh * 0.35) target = cands[1];
  }
  R.body.scrollTo({ top: vt + target.top - PAD_TOP, behavior: opts.smooth ? 'smooth' : 'auto' });
  clearAlignMarks();
  target.p.classList.add('align-mark');
  scheduleClearMark();
  if (opts.listen) listenFrom({ ch: target.ch, p: target.p });
  return target;
}

/* =====================================================================
 * 滚动跟踪 / 进度保存
 * =================================================================== */
let lastTick = 0;
function onScroll() {
  if (!R.book) return;
  const now = Date.now();
  if (now - lastTick < 90) return;
  lastTick = now;
  const top = topParagraph();
  if (top && top.p) {
    if (top.ch !== R.curCh) {
      R.curCh = top.ch;
      syncChromeTitle();
      ensureWindow(R.curCh);
    }
    if (R.saveTimer) clearTimeout(R.saveTimer);
    R.saveTimer = setTimeout(() => saveNow(top), 900);
  }
  // 接近已加载末尾 → 预载下一章
  const sects = sectsInDom();
  const last = sects[sects.length - 1];
  if (last && last.el.getBoundingClientRect().bottom - viewH() < viewH() * 1.2) {
    const nx = last.idx + 1;
    if (nx < R.book.total && !R.loaded.has(nx)) fetchChapter(nx).catch(() => {});
  }
}
function saveNow(top) {
  if (!R.book) return;
  const t = top || topParagraph();
  const st = {
    ch: t ? t.ch : R.curCh,
    par: t && t.p ? t.idx : 0,
    t: R.book.chapters[t && t.ch != null ? t.ch : R.curCh] ? R.book.chapters[t ? t.ch : R.curCh].t : '',
    total: R.book.total,
    at: Date.now(),
  };
  B.saveProgress(R.book.id, st);
}

/* =====================================================================
 * 章节跳转 / 进入阅读
 * =================================================================== */
async function enterBook(b) {
  const r = await B.openBook(b.id);
  if (!r.ok || !r.chapters || !r.chapters.length) { B.toast(r.error || '打开失败'); return; }
  R.book = { id: b.id, title: r.title, chapters: r.chapters, total: r.chapters.length };
  R.loaded.clear();
  R.inflight.clear();
  R.holder = $('content-holder');
  R.body = $('reader-body');
  R.holder.textContent = '';
  showScreen('reader');
  let prog = null;
  try { prog = await B.loadProgress(b.id); } catch (e) {}
  let ch = 0, par = 0;
  if (prog && prog.ch != null && prog.ch >= 0 && prog.ch < R.book.total) { ch = prog.ch; par = prog.par || 0; }
  R.curCh = ch;
  syncChromeTitle();
  await jumpToChapter(ch, par);
  setChrome(false);
  // 记住“最近在读的书”：下次冷启动直达续读
  B.saveLastBook(b.id);
}

async function jumpToChapter(ch, par) {
  if (!R.book || ch < 0 || ch >= R.book.total) return;
  R.curCh = ch;
  syncChromeTitle();
  const rec = await fetchChapter(ch);
  if (!rec) return;
  // 等窗口章节(±1)全部就绪后再定位：异步插入会改变上方布局，导致滚动落点偏移
  const neighbors = [ch - 1, ch, ch + 1]
      .filter(i => i >= 0 && i < R.book.total && !R.loaded.has(i));
  await Promise.all(neighbors.map(i => fetchChapter(i).catch(() => null)));
  prune();
  await sleep(1);      // 等布局
  const target = rec.paras[par] || rec.paras[0];
  if (target) R.body.scrollTop = Math.max(0, target.offsetTop - PAD_TOP);
  else R.body.scrollTop = Math.max(0, rec.el.offsetTop - PAD_TOP);
  closeDrawers();
  if (R.saveTimer) clearTimeout(R.saveTimer);
  saveNow(null);
}
function gotoChapter(ch) { jumpToChapter(ch, 0).catch(() => {}); }

function syncChromeTitle() {
  if (!R.book) return;
  const c = R.book.chapters[R.curCh];
  $('reader-title').textContent = (c ? c.t : '') + ' · ' + R.book.total + '章';
}

/* =====================================================================
 * UI 框架
 * =================================================================== */
function showScreen(name) {
  $('screen-shelf').hidden = name !== 'shelf';
  $('screen-reader').hidden = name !== 'reader';
  closeDrawers(true);
}
let chromeOn = false, chromeTimer = 0;
function setChrome(on) {
  chromeOn = on;
  $('reader-topbar').hidden = !on;
  $('reader-bottombar').hidden = !on;
  if (chromeTimer) clearTimeout(chromeTimer);
  if (on && (Tts.playing || Auto.on)) chromeTimer = setTimeout(() => setChrome(false), 4000);
}
function toggleChrome() { setChrome(!chromeOn); }

function openDrawer(which) {
  closeDrawers(true);
  $('scrim').hidden = false;
  if (which === 'toc') { $('drawer-toc').hidden = false; renderToc(); }
  else $('drawer-settings').hidden = false;
}
function closeDrawers(skipScrim) {
  $('drawer-toc').hidden = true;
  $('drawer-settings').hidden = true;
  if (!skipScrim) $('scrim').hidden = true;
}
function renderToc() {
  if (!R.book) return;
  const list = $('toc-list');
  list.textContent = '';
  const frag = document.createDocumentFragment();
  R.book.chapters.forEach((c, i) => {
    const d = document.createElement('div');
    d.className = 'toc-item' + (i === R.curCh ? ' cur' : '');
    d.textContent = c.t;
    d.addEventListener('click', () => gotoChapter(i));
    frag.appendChild(d);
  });
  list.appendChild(frag);
  const cur = list.querySelector('.toc-item.cur');
  if (cur) cur.scrollIntoView({ block: 'center' });
}
function exitReader() {
  saveNow(null);
  stopTts(false);
  stopAuto(false);
  // 主动回到书架 → 下次冷启动落在书架（不再自动跳进正文）
  B.clearLastBook();
  B.exportProgress();
  showScreen('shelf');
  refreshShelf();
}

/* 系统返回键：优先交给面板/工具条处理 */
window.__onAndroidBack = () => {
  if (!$('dlg-delete').hidden) { closeDeleteDialog(); return true; }
  if (!$('drawer-toc').hidden) { closeDrawers(); return true; }
  if (!$('drawer-settings').hidden) { closeDrawers(); return true; }
  if (!$('screen-reader').hidden) {
    if (!chromeOn) { setChrome(true); return true; }
    exitReader();
    return true;
  }
  return false;
};

/* =====================================================================
 * 句切分
 * =================================================================== */
function splitSentences(text) {
  const out = [];
  let buf = '';
  const push = () => { const t = buf.trim(); if (t) out.push(t); buf = ''; };
  const CLOSE = '”』」’）】>》';
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    buf += ch;
    if ('。！？…；!?;'.indexOf(ch) >= 0) {
      let j = i + 1;
      while (j < text.length && CLOSE.indexOf(text[j]) >= 0) { buf += text[j]; j++; }
      i = j - 1;
      push();
    } else if (ch === '\n') {
      push();
    } else if (buf.length >= 180) {
      let cut = -1;
      for (let k = buf.length - 1; k > 30; k--) {
        if ('，、：,'.indexOf(buf[k]) >= 0) { cut = k; break; }
      }
      if (cut > 0) { const rest = buf.slice(cut + 1); buf = buf.slice(0, cut + 1); push(); buf = rest; }
      else push();
    }
  }
  push();
  return out;
}

/* =====================================================================
 * TTS 朗读
 * =================================================================== */
const Tts = {
  playing: false, paused: false,
  pos: null,                 // {ch, parEl, sentIdx}
  gen: 0,                    // 代次
  lastId: '',                // 当前句 utterance id
  watchdog: 0,
  ttsOk: false, reason: '',
};

async function chapterParas(ch) {
  let rec = R.loaded.get(ch);
  if (!rec) rec = await fetchChapter(ch);
  return rec ? rec.paras : null;
}

/* 朗读高亮：仅把当前朗读段拆句包 mark */
function highlightParagraph(parEl, sentIdx) {
  clearHighlights();
  if (!parEl) return;
  const sents = splitSentences(parEl._raw || '');
  const frag = document.createDocumentFragment();
  sents.forEach((s, i) => {
    if (!s.trim()) return;
    const m = document.createElement('mark');
    m.className = 'read-sent' + (i === sentIdx ? ' on' : '');
    m.textContent = s;
    frag.appendChild(m);
  });
  parEl.textContent = '';
  parEl.appendChild(frag);
  parEl._marked = true;
  const cur = parEl.querySelector('mark.on');
  if (cur) scrollSentenceIntoView(cur);
}
function clearHighlights() {
  document.querySelectorAll('mark.read-sent').forEach(m => {
    const p = m.parentElement;
    if (!p || !p._marked) return;
    p.textContent = p._raw;
    p._marked = false;
  });
}
function scrollSentenceIntoView(el) {
  const r = el.getBoundingClientRect();
  const vh = viewH();
  const margin = vh * 0.3;
  if (r.top < margin || r.bottom > vh - margin) {
    R.body.scrollTo({ top: viewTop() + r.top - margin, behavior: 'smooth' });
  }
}

/* 等 TTS 引擎就绪：轮询状态，最多等 INIT_WAIT_MS。
 * 返回 {ok, reason, st}；ok=true 才可朗读。不会把“正在初始化”
 * 误报成“未安装引擎”。 */
const TTS_INIT_WAIT_MS = 10000;
const TTS_POLL_MS = 400;

async function ttsReady() {
  let st;
  const t0 = Date.now();
  while (Date.now() - t0 < TTS_INIT_WAIT_MS) {
    st = await B.ttsState().catch(() => null);
    if (st && st.ok) return { ok: true, st };
    if (st && !st.busy && !st.ok) {
      // 引擎已给出明确失败结果：重建一次再等，仍失败才向用户报错
      if (isNative) { try { NovelBridge.ttsRetry(); } catch (e) {} }
      const t1 = Date.now();
      while (Date.now() - t1 < TTS_INIT_WAIT_MS) {
        await sleep(TTS_POLL_MS);
        st = await B.ttsState().catch(() => null);
        if (st && st.ok) return { ok: true, st };
        if (st && !st.busy && !st.ok) return { ok: false, st };
      }
      return { ok: false, st };
    }
    await sleep(TTS_POLL_MS);   // busy（正在初始化）或引擎对象尚未建好 → 继续等
  }
  return { ok: false, st };
}

/* 从指定段落开始朗读（对齐段首/段落按钮入口） */
async function listenFrom(target) {
  if (!R.book) return;
  stopTts(false);
  const { ok, st } = await ttsReady();
  if (!ok) {
    const reason = st && st.reason ? st.reason
        : '语音引擎不可用，请安装中文语音引擎（讯飞语记、Google 文本转语音等）后重试';
    B.toast(reason);
    return;
  }
  let rec = R.loaded.get(target.ch);
  if (!rec) rec = await chapterParas(target.ch);
  if (!rec) { B.toast('章节尚未就绪'); return; }
  const parEl = target.p || rec.paras[target.idx] || rec.paras[0];
  if (!parEl) { B.toast('本章没有正文段落'); return; }
  if (parEl.getBoundingClientRect().top < -viewH() || parEl.getBoundingClientRect().top > viewH() * 1.2) {
    R.body.scrollTop = Math.max(0, parEl.offsetTop - PAD_TOP);
  }
  Tts.playing = true; Tts.paused = false;
  Tts.gen++;
  const gen = Tts.gen;
  Tts.pos = { ch: target.ch, parEl, sentIdx: 0 };
  B.keepScreenOn(true);
  setTtsUi();
  highlightParagraph(parEl, 0);
  speakCurrent(gen);
}

function setTtsUi() {
  const b = $('btn-tts-play');
  if (Tts.playing) {
    b.textContent = Tts.paused ? '继续' : '暂停';
    $('btn-tts-stop').hidden = false;
    $('tts-controls').hidden = false;
    $('tts-status').textContent = (Tts.paused ? '已暂停' : '朗读中')
        + (Tts.pos && R.book ? ' · ' + R.book.chapters[Tts.pos.ch].t : '');
  } else {
    b.textContent = '开始朗读';
    $('btn-tts-stop').hidden = true;
    $('tts-controls').hidden = true;
  }
  const rate = $('tts-rate');
  if (Tts.playing) { rate.value = Settings.cur.rate; $('tts-rate-val').textContent = Settings.cur.rate.toFixed(2) + '×'; }
}

function speakCurrent(gen) {
  if (gen !== Tts.gen || !Tts.playing || Tts.paused) return;
  const pos = Tts.pos;
  const sents = splitSentences(pos.parEl._raw || '');
  if (!sents.length || pos.sentIdx >= sents.length) { advanceParagraph(gen); return; }
  const text = sents[pos.sentIdx];
  const id = pos.ch + '-' + (Tts.gen) + '-' + pos.sentIdx;
  Tts.lastId = id;
  if (pos.parEl._marked) {
    pos.parEl.querySelectorAll('mark.read-sent').forEach((m, i) => m.classList.toggle('on', i === pos.sentIdx));
  }
  B.ttsSpeak(text, id);
  const expectMs = Math.max(2000, Math.round(text.length * 260 / (Settings.cur.rate || 1)));
  if (Tts.watchdog) clearTimeout(Tts.watchdog);
  Tts.watchdog = setTimeout(() => {
    if (gen === Tts.gen && Tts.playing && !Tts.paused) {
      B.ttsStop();
      advanceSentence(gen);
    }
  }, expectMs + 4000);
}

function advanceSentence(gen) {
  if (gen !== Tts.gen || !Tts.playing) return;
  const pos = Tts.pos;
  const sents = splitSentences(pos.parEl._raw || '');
  if (pos.sentIdx + 1 < sents.length) {
    pos.sentIdx++;
    speakCurrent(gen);
  } else {
    advanceParagraph(gen);
  }
}

async function advanceParagraph(gen) {
  if (gen !== Tts.gen || !Tts.playing) return;
  const pos = Tts.pos;
  const rec = R.loaded.get(pos.ch);
  let parIdx = rec ? rec.paras.indexOf(pos.parEl) : -1;
  parIdx++;
  let ch = pos.ch;
  while (ch < R.book.total) {
    let r = R.loaded.get(ch);
    if (r && parIdx < r.paras.length) {
      Tts.pos = { ch, parEl: r.paras[parIdx], sentIdx: 0 };
      highlightParagraph(r.paras[parIdx], 0);
      speakCurrent(gen);
      return;
    }
    ch++;
    parIdx = 0;
    r = await chapterParas(ch).catch(() => null);
    if (!r) break;
    if (r.paras.length) {
      R.body.scrollTop = Math.max(0, r.paras[0].offsetTop - PAD_TOP);
      Tts.pos = { ch, parEl: r.paras[0], sentIdx: 0 };
      syncChromeTitle();
      highlightParagraph(r.paras[0], 0);
      speakCurrent(gen);
      return;
    }
  }
  B.toast('已到全书末尾');
  stopTts(false);
}

function onTtsEvent(ev) {
  const type = ev.type;
  if (type === 'state') {
    Tts.ttsOk = ev.a === 'ok';
    Tts.reason = ev.b || '';
    // 引擎状态静默记录；需要提示的场景由调用方(开始朗读)统一弹 toast，避免重复打扰
    return;
  }
  if (type === 'speakError') {
    B.toast('朗读失败：' + (ev.b || ''));
    stopTts(false);
    return;
  }
  if (!Tts.playing || Tts.paused) return;
  const gen = Tts.gen;
  if (type === 'done' || type === 'error') {
    if (Tts.watchdog) clearTimeout(Tts.watchdog);
    if (type === 'error' || !ev.a || ev.a === Tts.lastId) {
      // 引擎报错或正常完成才推进；丢弃过期/错乱回调
      if (type === 'error') B.ttsStop();
      advanceSentence(gen);
    }
  }
}

function stopTts(keepUi) {
  Tts.gen++;
  if (Tts.watchdog) clearTimeout(Tts.watchdog);
  Tts.playing = false; Tts.paused = false;
  Tts.pos = null;
  B.ttsStop();
  B.keepScreenOn(Auto.on);
  clearHighlights();
  if (!keepUi) setTtsUi();
}

function toggleTtsPlay() {
  if (!R.book) return;
  if (!Tts.playing) {
    // 核心需求：永远从“当前页第一个完整段落”段首开始朗读
    const t = alignToPageStart({ listen: true, smooth: false });
    if (!t) {
      const ps = parasOf(R.curCh);
      if (ps && ps.length) listenFrom({ ch: R.curCh, p: ps[0] });
      else B.toast('暂无可朗读内容');
    }
  } else if (Tts.paused) {
    // 继续：换新代次重读当前句（兼容无 pause 的引擎；旧回调全部作废）
    Tts.paused = false;
    Tts.gen++;
    const gen = Tts.gen;
    B.ttsStop();
    setTtsUi();
    speakCurrent(gen);
  } else {
    Tts.paused = true;
    B.ttsPause();
    if (Tts.watchdog) clearTimeout(Tts.watchdog);
    setTtsUi();
  }
}

/* =====================================================================
 * 自动卷轴（匀速、字/分可调）
 * =================================================================== */
const Auto = { on: false, raf: 0, last: 0, pxPerSec: 0, acc: 0 };
function autoPxPerSec() {
  const fs = Settings.cur.font, lh = Settings.cur.lineH;
  const cw = Math.max(60, (R.body ? R.body.clientWidth : 400) - 44 - fs * 2);
  const rowChars = Math.max(5, Math.floor(cw / fs));
  return (Settings.cur.autoCpm / 60) * (lh * fs) / rowChars;
}
function startAuto() {
  if (Tts.playing) stopTts(false);
  // 自动从当前页第一个完整段落段首开始卷动（无需手动对齐）
  alignToPageStart({});
  Auto.on = true;
  Auto.pxPerSec = autoPxPerSec();
  Auto.last = performance.now();
  Auto.acc = 0;
  $('auto-controls').hidden = false;
  $('btn-auto').textContent = '停止卷轴';
  Auto.raf = requestAnimationFrame(autoStep);
  B.keepScreenOn(true);
  setChrome(true);
}
function autoStep(ts) {
  if (!Auto.on) return;
  const dt = ts - Auto.last;
  Auto.last = ts;
  const max = R.body.scrollHeight - R.body.clientHeight;
  if (R.body.scrollTop >= max - 2) { stopAuto(); B.toast('已到书末'); return; }
  // 实机观察：WebView 会丢弃亚像素 scrollTop 写入 → 浮点累积、整像素应用
  Auto.acc += Auto.pxPerSec * dt / 1000;
  const step = Math.floor(Auto.acc);
  if (step >= 1) {
    Auto.acc -= step;
    R.body.scrollTop = Math.min(max, R.body.scrollTop + step);
  }
  Auto.raf = requestAnimationFrame(autoStep);
}
function stopAuto(keepBtn) {
  Auto.on = false;
  if (Auto.raf) cancelAnimationFrame(Auto.raf);
  $('btn-auto').textContent = '自动卷轴';
  if (!keepBtn) $('auto-controls').hidden = true;
  B.keepScreenOn(Tts.playing);
}
function toggleAuto() {
  if (R.book) { if (Auto.on) stopAuto(); else startAuto(); }
}

/* =====================================================================
 * 事件绑定
 * =================================================================== */
function bindUI() {
  $('btn-import').addEventListener('click', () => B.importBook());
  $('btn-restore').addEventListener('click', () => { B.toast('请选择备份文件：小说有声阅读-阅读进度.json（通常在 下载/NovelReader/）'); B.restoreProgress(); });

  $('btn-toc').addEventListener('click', () => openDrawer('toc'));
  $('btn-toc-close').addEventListener('click', () => closeDrawers());
  $('btn-settings').addEventListener('click', () => openDrawer('settings'));
  $('btn-settings-close').addEventListener('click', () => closeDrawers());
  $('btn-shelf-back').addEventListener('click', exitReader);
  $('scrim').addEventListener('click', () => closeDrawers());

  $('btn-tts-play').addEventListener('click', toggleTtsPlay);
  $('btn-tts-stop').addEventListener('click', () => stopTts(false));
  $('btn-auto').addEventListener('click', toggleAuto);
  $('btn-tts-settings').addEventListener('click', () => {
    B.openTtsSettings();
    closeDrawers();
  });

  // 删除确认弹窗
  $('dlg-delete-cancel').addEventListener('click', closeDeleteDialog);
  $('dlg-delete-ok').addEventListener('click', confirmDeleteBook);
  $('dlg-delete').addEventListener('click', e => {
    if (e.target === $('dlg-delete')) closeDeleteDialog();
  });

  $('reader-body').addEventListener('scroll', onScroll, { passive: true });
  // 单击正文区（不含工具条/面板）切换工具条显隐
  $('reader-body').addEventListener('click', e => {
    if (e.target.closest('.reader-chrome, .drawer, .scrim')) return;
    toggleChrome();
  });
  // 自动卷轴中，触摸正文即停
  $('reader-body').addEventListener('touchstart', e => {
    if (Auto.on && !e.target.closest('.reader-chrome')) stopAuto();
  }, { passive: true });

  const applyFont = () => {
    Settings.apply();
    Settings.save();
    anchorReflow();
  };
  $('set-font').addEventListener('input', e => { Settings.cur.font = +e.target.value; applyFont(); });
  $('set-lineh').addEventListener('input', e => { Settings.cur.lineH = +e.target.value; applyFont(); });
  $('tts-rate').addEventListener('input', e => {
    Settings.cur.rate = +e.target.value;
    Settings.save();
    $('tts-rate-val').textContent = Settings.cur.rate.toFixed(2) + '×';
    B.ttsSetRate(Settings.cur.rate);
  });
  $('auto-speed').addEventListener('input', e => {
    Settings.cur.autoCpm = +e.target.value;
    Settings.save();
    $('auto-speed-val').textContent = Settings.cur.autoCpm + '字/分';
    if (Auto.on) { Auto.pxPerSec = autoPxPerSec(); Auto.last = performance.now(); }
  });
  document.querySelectorAll('.theme-btn').forEach(b =>
    b.addEventListener('click', () => {
      Settings.cur.theme = b.dataset.theme;
      Settings.apply();
      Settings.save();
    }));

  const flushAll = () => {
    saveNow(null);
    B.exportProgress();      // 退出/切后台时强制刷新外部镜像，防丢进度
  };
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') flushAll();
  });
  window.addEventListener('pagehide', flushAll);
}

/* 字号/行距变化后保持视口段不跳 */
async function anchorReflow() {
  const top = topParagraph();
  if (!top || !top.p) return;
  await sleep(1);
  R.body.scrollTop = Math.max(0, top.p.offsetTop - PAD_TOP);
}

/* =====================================================================
 * 启动
 * =================================================================== */
async function boot() {
  await Settings.load();
  Settings.apply();
  bindUI();
  if (!isNative) document.body.classList.add('dev-mark');
  if (isNative) { try { NovelBridge.ttsInit(); } catch (e) {} }
  // 卸载重装后：本地无数据则从「下载/NovelReader」镜像自动恢复进度与最近书目
  if (isNative) {
    try {
      const rr = await B.tryRestoreExternal();
      if (rr && rr.restored) await Settings.load();   // 设置可能被一并恢复
      Settings.apply();
    } catch (e) {}
  }
  await refreshShelf();
  // 启动直达：最近一次还在阅读某本书（未主动返回书架）→ 直接进正文续读
  let last = '';
  try { last = await B.loadLastBook(); } catch (e) {}
  const target = last ? shelfBooks.find(x => x.id === last) : null;
  if (target) {
    await enterBook(target);
  } else {
    showScreen('shelf');
  }
}
if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
else boot();

/* =====================================================================
 * 开发自检（仅 ?devtest=1 时运行；真机 APK 无参数恒为惰性）
 * =================================================================== */
if (new URLSearchParams(location.search).has('devtest')) {
  (async () => {
    await sleep(600);
    const out = [];
    const log = (k, v) => out.push(k + '=' + v);
    try {
      const listR = await B.listBooks();
      log('shelf-books', !!(listR.books && listR.books.length));
      const b = (listR.books || [])[0];
      await enterBook(b);
      log('total-ch', R.book.total);
      await sleep(500);
      const p0 = ((R.loaded.get(R.curCh) || {}).paras || []);
      log('paras-ch0', p0.length > 0);
      const mid = Math.max(1, Math.floor(R.book.total * 0.3));
      await jumpToChapter(mid, 0);
      await sleep(400);
      const ps = parasOf(mid) || [];
      log('jump-mid', ps.length > 10);
      R.body.scrollTop += R.body.clientHeight * 1.7;
      onScroll();
      await sleep(150);
      const t = alignToPageStart({});
      await sleep(120);
      const tr = t && t.p ? t.p.getBoundingClientRect() : null;
      log('align-top-ok', tr ? Math.abs(tr.top - PAD_TOP) < 8 : false);
      const sample = ps.slice(0, 5).map(p => p._raw || '').join('') || '这是测试。第二句！第三句……完。';
      const sents = splitSentences(sample);
      log('split>=3', sents.length >= 3);
      startAuto();
      await sleep(300);
      stopAuto();
      log('auto-run', true);
      await listenFrom({ ch: mid, p: ps[0] });
      await sleep(3500);
      log('tts-advance', !!(Tts.playing && Tts.pos && !Tts.paused));
      stopTts(false);
      log('curCh>=0', R.curCh >= 0);
    } catch (err) {
      out.push('EXCEPTION=' + String(err && err.stack || err));
    }
    const pre = document.createElement('pre');
    pre.id = 'devtest-out';
    pre.textContent = out.join('\n') + '\n';
    document.body.appendChild(pre);
    document.title = (out.some(o => /false|EXCEPTION/.test(o)) ? 'DEVFAIL' : 'DEVPASS');
  })();
}

