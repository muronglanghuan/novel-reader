package com.like.novelreader;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/**
 * JS 桥（window.NovelBridge）。
 * JS 侧方法均在 WebView 后台线程执行，涉及 UI/TTS 的操作一律投递主线程；
 * 事件统一走 window.__native(type, payloadObject)。
 */
public final class Bridge {

    public interface Host {
        void launchImportPicker();
        void launchRestorePicker();
        void setKeepScreenOn(boolean on);
        void onBridgeDestroy();
    }

    private static final int MAX_IMPORT_BYTES = 80 * 1024 * 1024;

    private final Context appCtx;
    private final Activity activity;
    private final WebView wv;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());

    private TtsEngine tts;
    private boolean ttsInitRequested;

    private final Object bookLock = new Object();
    private String currentBookId;
    private ChapterParser.Parsed currentParsed;   // 已解码+已解析的当前书
    private String currentTitle;

    public Bridge(Activity activity, WebView wv, Host host) {
        this.activity = activity;
        this.appCtx = activity.getApplicationContext();
        this.wv = wv;
        this.host = host;
    }

    // ---------------- 工具 ----------------

    private void post(Runnable r) { main.post(r); }

    private void fireEvent(String type, JSONObject payload) {
        String code = "window.__native&&window.__native("
                + JSONObject.quote(type) + "," + payload.toString() + ");";
        post(() -> wv.evaluateJavascript(code, null));
    }

    private void fireEvent(String type, String a, String b) {
        try {
            JSONObject o = new JSONObject();
            o.put("a", a == null ? JSONObject.NULL : a);
            o.put("b", b == null ? JSONObject.NULL : b);
            fireEvent(type, o);
        } catch (JSONException ignored) {}
    }

    private JSONObject err(String key, String msg) {
        JSONObject o = new JSONObject();
        try { o.put("ok", false); o.put(key, msg); } catch (JSONException ignored) {}
        return o;
    }

    // ---------------- 书库 ----------------

    @JavascriptInterface
    public String getBookList() {
        try {
            List<BookStore.Book> books = BookStore.list(appCtx);
            JSONArray arr = new JSONArray();
            for (BookStore.Book b : books) {
                JSONObject o = new JSONObject();
                o.put("id", b.id);
                o.put("title", b.title);
                o.put("builtin", b.builtin);
                arr.put(o);
            }
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("books", arr);
            return r.toString();
        } catch (Throwable t) {
            return err("error", t.toString()).toString();
        }
    }

    /** 打开书（解码+切章+缓存）。已打开的书直接返回缓存（须含章节列表，勿返回空）。 */
    @JavascriptInterface
    public String openBook(String id) {
        try {
            synchronized (bookLock) {
                if (id != null && id.equals(currentBookId) && currentParsed != null) {
                    return chaptersJson(true, currentTitle, currentParsed);
                }
                byte[] data = BookStore.readBook(appCtx, id);
                if (data.length == 0) return err("error", "空文件").toString();
                String text = ChapterParser.decode(data);
                ChapterParser.Parsed p = ChapterParser.parse(text);
                currentBookId = id;
                currentParsed = p;
                currentTitle = titleOf(id);
                return chaptersJson(true, currentTitle, p);
            }
        } catch (Throwable t) {
            return err("error", "打开失败: " + t.getMessage()).toString();
        }
    }

    private String chaptersJson(boolean ok, String title, ChapterParser.Parsed p) {
        JSONObject r = new JSONObject();
        try {
            r.put("ok", ok);
            r.put("title", title);
            if (p != null) {
                JSONArray arr = new JSONArray();
                for (int i = 0; i < p.count(); i++) {
                    JSONObject c = new JSONObject();
                    c.put("i", i);
                    c.put("t", p.title[i]);
                    arr.put(c);
                }
                r.put("chapters", arr);
                r.put("total", p.count());
            }
        } catch (JSONException ignored) {}
        return r.toString();
    }

    private String titleOf(String id) {
        if (id.startsWith("a:") || id.startsWith("i:")) {
            return BookStore.displayTitle(id.substring(2));
        }
        return id;
    }

    /** 取章节正文（须先 openBook）。 */
    @JavascriptInterface
    public String getChapter(String id, int idx) {
        synchronized (bookLock) {
            if (currentParsed == null || !id.equals(currentBookId)) {
                return err("error", "请先打开书籍").toString();
            }
            if (idx < 0 || idx >= currentParsed.count()) {
                return err("error", "章节越界").toString();
            }
            JSONObject r = new JSONObject();
            try {
                r.put("ok", true);
                r.put("i", idx);
                r.put("t", currentParsed.title[idx]);
                r.put("text", currentParsed.chapterText(idx));
            } catch (JSONException ignored) {}
            return r.toString();
        }
    }

    // ---------------- 进度 / 设置 ----------------

    @JavascriptInterface
    public void saveProgress(String bookId, String progressJson) {
        try {
            JSONObject o = new JSONObject(progressJson);
            ProgressStore.saveBook(appCtx, bookId, o);
        } catch (JSONException ignored) {}
        maybeMirrorBackup();   // 节流镜像到外部目录
    }

    @JavascriptInterface
    public String loadProgress(String bookId) {
        String s = ProgressStore.loadBook(appCtx, bookId);
        return s == null ? "" : s;
    }

    // ---------------- 最近打开的书（启动直达续读） ----------------

    @JavascriptInterface
    public void saveLastBook(String bookId) {
        ProgressStore.setLastBook(appCtx, bookId);
        maybeMirrorBackup();
    }

    @JavascriptInterface
    public String loadLastBook() {
        String s = ProgressStore.getLastBook(appCtx);
        return s == null ? "" : s;
    }

    @JavascriptInterface
    public void clearLastBook() {
        ProgressStore.clearLastBook(appCtx);
    }

    // ---------------- 外部镜像备份 / 恢复 ----------------

    private static final long MIRROR_GAP_MS = 15000L;
    private long lastMirrorTs = 0L;
    private long lastSaveTs = 0L;
    private boolean mirrorPending = false;

    /** 节流 + 延迟补写：保存太频繁时合并写盘，间隔到点后若有新数据再追写一次 */
    private void maybeMirrorBackup() {
        long now = System.currentTimeMillis();
        lastSaveTs = now;
        if (now - lastMirrorTs < MIRROR_GAP_MS) {
            if (!mirrorPending) {
                mirrorPending = true;
                main.postDelayed(() -> {
                    mirrorPending = false;
                    if (lastSaveTs > lastMirrorTs) {
                        lastMirrorTs = System.currentTimeMillis();
                        BackupManager.write(appCtx, ProgressStore.exportAll(appCtx));
                    }
                }, MIRROR_GAP_MS);
            }
            return;
        }
        lastMirrorTs = now;
        BackupManager.write(appCtx, ProgressStore.exportAll(appCtx));
    }

    /** JS 在退出/隐藏时强制刷新镜像 */
    @JavascriptInterface
    public void exportProgress() {
        BackupManager.write(appCtx, ProgressStore.exportAll(appCtx));
    }

    /** 用户手动恢复进度：弹出文件选择器选「小说有声阅读-阅读进度.json」镜像 */
    @JavascriptInterface
    public void restoreProgress() {
        post(host::launchRestorePicker);
    }

    /** MainActivity 回调：读取所选备份 JSON 并导入（覆盖式） */
    public void handleRestoreResult(Uri uri) {
        if (uri == null) return;
        try (InputStream is = appCtx.getContentResolver().openInputStream(uri)) {
            if (is == null) throw new Exception("无法读取所选文件");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            long total = 0;
            int r;
            while ((r = is.read(buf)) > 0) {
                total += r;
                if (total > 4 * 1024 * 1024) throw new Exception("文件过大");
                bos.write(buf, 0, r);
            }
            JSONObject data = new JSONObject(bos.toString("UTF-8"));
            int n = ProgressStore.importAll(appCtx, data);
            toast("进度恢复完成，共 " + n + " 本书");
            fireEvent("booksChanged", new JSONObject());
        } catch (Exception e) {
            toast("恢复失败：" + e.getMessage());
        }
    }

    /** 临时诊断：App 视角下 MediaStore Downloads 可见性 */
    @JavascriptInterface
    public String readDebug() {
        JSONObject r = new JSONObject();
        try {
            android.content.ContentResolver cr = appCtx.getContentResolver();
            android.net.Uri col = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            int a = 0, b = 0;
            try (android.database.Cursor c = cr.query(col,
                    new String[]{android.provider.MediaStore.MediaColumns._ID},
                    null, null, null)) {
                if (c != null) a = c.getCount();
            }
            try (android.database.Cursor c = cr.query(col,
                    new String[]{android.provider.MediaStore.MediaColumns._ID},
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                    new String[]{BackupManager.NAME_PREFIX + "%"}, null)) {
                if (c != null) b = c.getCount();
            }
            r.put("allRows", a);
            r.put("nameRows", b);
        } catch (Throwable t) {
            try { r.put("ex", String.valueOf(t)); } catch (Exception ignored) {}
        }
        return r.toString();
    }

    /**
     * 首次启动调用：本地无任何数据时，尝试从「下载/NovelReader」镜像恢复
     * （覆盖升级不丢数据；卸载重装后也能找回进度）。
     */
    @JavascriptInterface
    public String tryRestoreExternal() {
        JSONObject r = new JSONObject();
        try {
            boolean empty = !ProgressStore.hasAnyData(appCtx);
            r.put("restored", false);
            r.put("count", 0);
            r.put("empty", empty);
            if (empty) {
                JSONObject data = BackupManager.read(appCtx);
                if (data != null) {
                    int n = ProgressStore.importAll(appCtx, data);
                    r.put("restored", n > 0 || data.has("lastBook") || data.has("settings"));
                    r.put("count", n);
                    StringBuilder sb = new StringBuilder();
                    java.util.Iterator<String> it = data.keys();
                    while (it.hasNext()) sb.append(it.next()).append(',');
                    r.put("dataKeys", sb.toString());
                } else {
                    r.put("readNull", true);
                }
            }
        } catch (JSONException ignored) {}
        return r.toString();
    }

    @JavascriptInterface
    public void saveSettings(String settingsJson) {
        try {
            JSONObject o = new JSONObject(settingsJson);
            ProgressStore.saveSettings(appCtx, o);
        } catch (JSONException ignored) {}
    }

    @JavascriptInterface
    public String loadSettings() {
        String s = ProgressStore.loadSettings(appCtx);
        return s == null ? "" : s;
    }

    // ---------------- 导入 ----------------

    @JavascriptInterface
    public void importBook() {
        post(host::launchImportPicker);
    }

    /** 删除导入的书（含其进度）；内置书不可删。 */
    @JavascriptInterface
    public String deleteBook(String id) {
        JSONObject r = new JSONObject();
        try {
            if (id == null || !id.startsWith("i:")) {
                r.put("ok", false);
                r.put("error", "内置书不可删除");
                return r.toString();
            }
            String name = id.substring(2);
            // 防目录穿越：仅允许普通文件名
            if (name.contains("/") || name.contains("\\") || name.contains("..") || name.isEmpty()) {
                r.put("ok", false);
                r.put("error", "非法文件名");
                return r.toString();
            }
            File f = new File(new File(appCtx.getFilesDir(), "imports"), name);
            if (!f.isFile()) {
                r.put("ok", false);
                r.put("error", "文件不存在或已删除");
                return r.toString();
            }
            synchronized (bookLock) {
                if (id.equals(currentBookId)) {
                    currentBookId = null;
                    currentParsed = null;   // 释放缓存
                    currentTitle = null;
                }
            }
            boolean ok = f.delete();
            ProgressStore.removeBook(appCtx, id);
            r.put("ok", ok);
            if (!ok) r.put("error", "删除文件失败");
            else fireEvent("booksChanged", new JSONObject());
        } catch (Exception e) {
            try {
                r.put("ok", false);
                r.put("error", "删除失败: " + e.getMessage());
            } catch (JSONException ignored) {}
        }
        return r.toString();
    }

    /** MainActivity 的 onActivityResult 回调：读取所选文件并复制入应用目录 */
    public void handleImportResult(Uri uri) {
        if (uri == null) return;
        String displayName = queryName(uri);
        if (displayName == null) displayName = "导入书";
        if (displayName.toLowerCase().endsWith(".txt")) {
            // ok
        } else {
            displayName = displayName + ".txt";
        }
        File target = BookStore.importTarget(appCtx, displayName);
        try (InputStream is = appCtx.getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(target)) {
            byte[] buf = new byte[65536];
            long total = 0;
            int r;
            while ((r = is.read(buf)) > 0) {
                total += r;
                if (total > MAX_IMPORT_BYTES) throw new Exception("文件过大(>80MB)");
                fos.write(buf, 0, r);
            }
            // 通知 JS 刷新书单
            JSONObject o = new JSONObject();
            o.put("msg", "导入成功：" + BookStore.displayTitle(target.getName()));
            fireEvent("booksChanged", o);
            toast("已导入：" + displayName);
        } catch (Throwable t) {
            if (target.exists()) target.delete();
            toast("导入失败：" + t.getMessage());
        }
    }

    private String queryName(Uri uri) {
        ContentResolver cr = appCtx.getContentResolver();
        try (Cursor c = cr.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ci = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ci >= 0) return c.getString(ci);
            }
        } catch (Throwable ignored) {}
        String last = uri.getLastPathSegment();
        return last == null ? null : last.replaceAll(".*/", "");
    }

    // ---------------- 提示 ----------------

    @JavascriptInterface
    public void toast(final String msg) {
        post(() -> Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show());
    }

    // ---------------- 屏幕常亮 ----------------

    @JavascriptInterface
    public void keepScreenOn(boolean on) {
        post(() -> host.setKeepScreenOn(on));
    }

    // ---------------- TTS ----------------

    /** TTS 引擎只在主线程创建/初始化（TextToSpeech 构造要求线程带 Looper） */
    private void ensureTts() {
        if (tts != null) return;
        post(() -> {
            if (tts != null) return;
            tts = new TtsEngine(appCtx, ev -> {
                // 引擎回调（binder/主线程不定）统一回到主线程再推给 JS
                try {
                    String type = ev.getString("type");
                    switch (type) {
                        case "start":
                        case "done":
                        case "error":
                            fireEvent("tts", ev);
                            break;
                        case "ready": {
                            JSONObject o = new JSONObject();
                            o.put("type", "state");
                            o.put("a", ev.optString("a"));
                            o.put("b", ev.optString("b"));
                            fireEvent("tts", o);
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            });
            tts.init();
        });
    }

    /** 重建引擎（安装/启用新引擎后无需重启 App） */
    @JavascriptInterface
    public void ttsRetry() {
        post(() -> {
            if (tts != null) tts.destroy();
            tts = null;
            ensureTts();
        });
    }

    @JavascriptInterface
    public void ttsInit() {
        ensureTts();
    }

    /** 手动检查更新（忽略限频）；结果以 Toast 反馈，新版自动后台下载并安装 */
    @JavascriptInterface
    public void checkForUpdate() {
        UpdateChecker.check(appCtx, true, (tag, err) -> {
            if (tag != null) {
                // UpdateChecker 内部下载完成后会自动拉起安装页
            } else if ("none".equals(err)) {
                toast("已是最新版本（v" + currentVersionName() + "）");
            } else if ("no-asset".equals(err)) {
                toast("仓库中未找到可安装的正式版 APK");
            } else if ("ok-skip".equals(err)) {
                toast("刚刚已检查过，请稍后再试");
            } else {
                toast("检查更新失败（网络不可用？），请稍后重试");
            }
        });
    }

    private String currentVersionName() {
        try {
            return appCtx.getPackageManager()
                    .getPackageInfo(appCtx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** 打开系统文字转语音设置页（引导用户检查/切换引擎） */
    @JavascriptInterface
    public void openTtsSettings() {
        post(() -> {
            // 无公开常量：AOSP 与多数 ROM 的 TTS 设置页都响应此 action
            try {
                activity.startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
            } catch (Throwable t) {
                try {
                    Intent i = new Intent("com.android.settings.TTS_SETTINGS");
                    i.setPackage("com.android.settings");
                    activity.startActivity(i);
                } catch (Throwable t2) {
                    toast("无法打开系统语音设置，请到 系统设置→文字转语音");
                }
            }
        });
    }

    /** JS 查询 TTS 状态：{ok, reason, engines:[包名], default:[包名], busy} */
    @JavascriptInterface
    public String ttsState() {
        JSONObject o = new JSONObject();
        try {
            if (tts == null) {
                // 尚未初始化（可能正排主线程）→ 交由 JS 侧稍后轮询，不在此阻塞
                o.put("ok", false);
                o.put("busy", true);
                o.put("reason", "");
                o.put("engines", new JSONArray());
                o.put("default", "");
                return o.toString();
            }
            o.put("ok", tts.isUsable());
            o.put("busy", tts.isBusy());
            o.put("reason", tts.stateReason());
            JSONArray arr = new JSONArray();
            for (String e : TtsEngine.engines(appCtx)) arr.put(e);
            o.put("engines", arr);
            String def = TtsEngine.defaultEnginePkg(appCtx);
            o.put("default", def == null ? "" : def);
        } catch (JSONException ignored) {}
        return o.toString();
    }

    @JavascriptInterface
    public void ttsSpeak(final String text, final String id) {
        ensureTts();
        post(() -> {
            if (tts != null) {
                String reason = tts.speak(text, id);
                if (reason != null) {
                    fireEvent("tts", "speakError", reason + "|" + id);
                }
            }
        });
    }

    @JavascriptInterface
    public void ttsStop() {
        ensureTts();
        post(() -> { if (tts != null) tts.stop(); });
    }

    @JavascriptInterface
    public void ttsPause() {
        ensureTts();
        post(() -> { if (tts != null) tts.pause(); });
    }

    /** 继续朗读由 JS 重读当前句实现（新代次 utterance），无需原生动作 */
    @JavascriptInterface
    public void ttsResume() {
        // no-op（见 TtsEngine.pause 说明）
    }

    @JavascriptInterface
    public void ttsSetRate(double rate) {
        ensureTts();
        post(() -> { if (tts != null) tts.setRate((float) rate); });
    }

    // ---------------- 生命周期 ----------------

    public void destroy() {
        if (tts != null) tts.destroy();
        tts = null;
    }
}
