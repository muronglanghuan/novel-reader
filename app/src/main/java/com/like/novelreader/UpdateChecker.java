package com.like.novelreader;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 静默更新检查器：从 GitHub Releases 查询最新版，比对版本号，
 * 发现新版后后台下载 APK，下载完自动拉起系统安装页。
 *
 * 仓库：muronglanghuan/novel-reader（公开仓库，无需鉴权，60 次/小时限额足够个人使用）
 * 所有网络失败都静默降级（不打扰阅读），下次启动或手动检查时重试。
 */
public final class UpdateChecker {

    public interface Listener {
        /** @param tag 已发布的最新版本号，如 "1.7"；null 表示网络/解析失败 */
        void onResult(String tag, String error);
    }

    private static final String REPO = "muronglanghuan/novel-reader";
    private static final String API_URL =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long CHECK_COOLDOWN_MS = 6L * 3600 * 1000;   // 自动检查限频 6h
    private static final String PREFS = "novelreader_update";
    private static final String KEY_LAST_CHECK = "last_auto_check_ms";

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UpdateChecker() {}

    /**
     * 检查更新。
     * @param force true=忽略 6h 限频（手动按钮）；false=限频内直接跳过
     */
    public static void check(final Context ctx, final boolean force, final Listener l) {
        if (!force) {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long last = sp.getLong(KEY_LAST_CHECK, 0L);
            if (System.currentTimeMillis() - last < CHECK_COOLDOWN_MS) {
                if (l != null) MAIN.post(() -> l.onResult(null, "ok-skip"));
                return;
            }
        }
        EXEC.execute(() -> {
            String err = null;
            String tag = null;
            String assetUrl = null;
            HttpURLConnection conn = null;
            try {
                SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                sp.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();

                conn = (HttpURLConnection) new URL(API_URL).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "novel-reader-android");
                if (conn.getResponseCode() != 200) {
                    err = "HTTP " + conn.getResponseCode();
                    return; // finally 中统一回调
                }
                String body = new String(readAll(conn.getInputStream()), StandardCharsets.UTF_8);
                JSONObject rel = new JSONObject(body);
                if (rel.optBoolean("draft") || rel.optBoolean("prerelease")) { err = "none"; return; }
                String tagName = rel.optString("tag_name", "");
                if (!tagName.startsWith("v")) { err = "none"; return; }
                String ver = tagName.substring(1).trim();
                if (compareVer(ver, currentVersion(ctx)) <= 0) { err = "none"; return; }
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.optJSONObject(i);
                        if (a != null && a.optString("name", "").endsWith("-release.apk")) {
                            assetUrl = a.optString("browser_download_url");
                            break;
                        }
                    }
                }
                if (assetUrl == null) { err = "no-asset"; return; }
                tag = ver;
                // 有新版本：后台下载（失败静默，留给下次）
                download(ctx, assetUrl, ver);
            } catch (Exception e) {
                err = String.valueOf(e);
            } finally {
                if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} }
                final String fErr = err;
                final String fTag = tag;
                if (l != null) {
                    MAIN.post(() -> l.onResult(fTag, fErr));
                }
            }
        });
    }

    /** 后台下载到 filesDir/updates/，完成后自动拉起系统安装页 */
    private static void download(final Context ctx, final String url, final String ver) {
        EXEC.execute(() -> {
            HttpURLConnection conn = null;
            try {
                File dir = new File(ctx.getFilesDir(), "updates");
                if (!dir.exists()) dir.mkdirs();
                final File tmp = new File(dir, "novel-" + ver + ".apk.tmp");
                final File out = new File(dir, "novel-" + ver + ".apk");
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(20000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "novel-reader-android");
                if (conn.getResponseCode() != 200) return;
                try (InputStream is = conn.getInputStream();
                     OutputStream os = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[32768];
                    int r;
                    while ((r = is.read(buf)) > 0) os.write(buf, 0, r);
                }
                if (tmp.length() < 100 * 1024) { tmp.delete(); return; }   // 明显非完整 APK
                if (!out.exists() || out.length() != tmp.length()) {
                    out.delete();
                    if (!tmp.renameTo(out)) { tmp.delete(); return; }
                } else {
                    tmp.delete();
                }
                final File apk = out;
                MAIN.post(() -> toast(ctx, "新版本 v" + ver + " 已下载，正在打开安装…"));
                MAIN.post(() -> installApk(ctx, apk));
            } catch (Exception e) {
                // 静默失败：下次启动/手动检查再试
            } finally {
                if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} }
            }
        });
    }

    /** 拉起系统安装页；未授权“安装未知应用”时引导去授权 */
    private static void installApk(Context ctx, File apk) {
        try {
            PackageManager pm = ctx.getPackageManager();
            if (!pm.canRequestPackageInstalls()) {
                toast(ctx, "请允许本应用安装未知应用，再点一次检查更新");
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + ctx.getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (ActivityNotFoundException ignored) {
                }
                return;
            }
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(i);
        } catch (Exception e) {
            toast(ctx, "打开安装失败，请到「下载目录」手动安装新版本");
        }
    }

    // ---------------- 工具 ----------------

    private static void toast(Context ctx, String msg) {
        try { Toast.makeText(ctx.getApplicationContext(), msg, Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
    }

    private static byte[] readAll(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = is.read(buf)) > 0) bos.write(buf, 0, r);
        return bos.toByteArray();
    }

    /** 当前版本号（从包信息读，避免依赖 BuildConfig） */
    private static String currentVersion(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** a>b → 1；相等 → 0；a<b → -1。支持 "1.7" / "1.10.2" 等点分号 */
    private static int compareVer(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? parseNum(pa[i]) : 0;
            int y = i < pb.length ? parseNum(pb[i]) : 0;
            if (x != y) return x > y ? 1 : -1;
        }
        return 0;
    }

    private static int parseNum(String s) {
        int v = 0;
        for (char c : s.toCharArray()) {
            if (c >= '0' && c <= '9') v = v * 10 + (c - '0');
        }
        return v;
    }
}
