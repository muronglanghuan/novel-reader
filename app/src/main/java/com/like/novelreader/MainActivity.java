package com.like.novelreader;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

/**
 * 唯一 Activity：全屏 WebView 承载整站 UI，本地资源经 WebViewAssetLoader 以
 * https://appassets.androidplatform.net/assets/ 提供（避免 file:// 限制）。
 */
public final class MainActivity extends Activity implements Bridge.Host {

    private static final int REQ_IMPORT = 4242;
    private static final int REQ_RESTORE = 4243;

    /** 命令重试：WebView 从冷启动到可执行 JS 有几百毫秒空窗，必须重投 */
    private static final int CMD_RETRIES = 25;
    private static final int CMD_COLD_RETRIES = 60;   // 冷启动：约 7 秒
    private static final long CMD_RETRY_MS = 120L;

    private static WebView webView;          // 静态引用：供命令投递使用（Activity 销毁时置空）
    private static String pendingCmd = null;
    private static long pendingCmdAt = 0L;
    private static long pendingSeq = 0L;      // 同一条命令的所有重投共用一个序号
    private static long cmdSeq = 0L;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WebView wv;
    private Bridge bridge;
    private WebViewAssetLoader assetLoader;

    /** WebView 是否还在（进程未被回收）：命令投递前据此决定要不要拉起界面 */
    public static boolean hasWebView() {
        return webView != null;
    }

    /**
     * 把一条控制命令投给 WebView，直到 JS 侧执行完毕（JS 执行后调
     * {@link Bridge#controlAck} 回执）或重试次数用尽。持锁是必要的：通知按钮
     * 与耳机按键可能同时到达，两条命令不能交叉重投。
     */
    public static void deliverCommand(final Context ctx, final String cmd) {
        if (cmd == null || cmd.isEmpty()) return;
        final long seq;
        synchronized (MainActivity.class) {
            // 新命令总是覆盖旧的：媒体控制以最后一次操作为准，
            // 若沿用"仅在上一条已确认时才接受"，用户在 120ms 内连按两次就会丢键
            pendingCmd = cmd;
            pendingCmdAt = System.currentTimeMillis();
            pendingSeq = ++cmdSeq;
            seq = pendingSeq;
        }
        // WebView 不存在（进程刚被拉起）时给足冷启动时间：首帧要几秒才可执行 JS
        int retries = webView == null ? CMD_COLD_RETRIES : CMD_RETRIES;
        for (int n = 0; n < retries; n++) {
            if (n == 0) { pushCommand(cmd, seq); continue; }
            mainHandler.postDelayed(() -> pushCommand(cmd, seq), n * CMD_RETRY_MS);
        }
    }

    /** 命令已被 JS 执行（Bridge.controlAck）：停掉后续重投 */
    public static void onCommandAcked(String cmd) {
        synchronized (MainActivity.class) {
            if (cmd != null && cmd.equals(pendingCmd)) pendingCmd = null;
        }
    }

    /**
     * 投递一次。带序号是必需的：重试间隔 120ms 可能短于"JS 执行+回执"的往返，
     * 同一条命令会被投两次 —— 表现为按一次"下一章"跳两章、按一次暂停变继续。
     * JS 侧按序号去重（同序号只执行一次，重复的仍回执），这里只负责带上序号。
     */
    private static void pushCommand(final String cmd, final long seq) {
        synchronized (MainActivity.class) {
            if (!cmd.equals(pendingCmd)) return;      // 已被执行/被新命令覆盖 → 不再重投
        }
        final WebView v = webView;
        if (v == null) return;
        try {
            v.evaluateJavascript("window.onControlCommand&&onControlCommand("
                    + JSONObject.quote(cmd) + "," + seq + ");", null);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 调试构建开启 WebView 远程调试（便于 adb + CDP 自动化验证）
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true);
        }

        wv = new WebView(this);
        webView = wv;                 // 供锁屏卡片/耳机按键命令投递
        setContentView(wv);

        // 朗读链跑在 WebView 渲染进程里：切后台/息屏时避免渲染进程被降级/冻结。
        // 第二个参数必须是 false —— 传 true 表示“WebView 不可见时按 WAIVED 处理”，
        // 恰好抵消 IMPORTANT，息屏/切后台后渲染进程立刻变成 OOM 候选被回收(朗读悄悄停止)。
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try {
                wv.setRendererPriorityPolicy(
                        android.webkit.WebView.RENDERER_PRIORITY_IMPORTANT, false);
            } catch (Throwable ignored) {}
        }

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setBlockNetworkLoads(true);        // 纯本地应用，禁网(自动更新走原生层)
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return assetLoader.shouldInterceptRequest(Uri.parse(url));
            }
        });

        bridge = new Bridge(this, wv, this);
        wv.addJavascriptInterface(bridge, "NovelBridge");

        wv.setBackgroundColor(0xFF101318);
        wv.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    // ---------- Bridge.Host ----------

    @Override
    public void launchImportPicker() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/plain");
            startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) {
            bridge.toast("无法打开文件选择器");
        }
    }

    @Override
    public void launchRestorePicker() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_RESTORE);
        } catch (Throwable t) {
            bridge.toast("无法打开文件选择器");
        }
    }

    @Override
    public void setKeepScreenOn(boolean on) {
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void onBridgeDestroy() {
        // 预留
    }

    // ---------- 导入结果 ----------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (data == null || data.getData() == null) return;
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK) {
            bridge.handleImportResult(data.getData());
        } else if (requestCode == REQ_RESTORE && resultCode == RESULT_OK) {
            bridge.handleRestoreResult(data.getData());
        }
    }

    // ---------- 自更新（启动后延迟静默检查一次） ----------

    private boolean updateCheckedOnce;

    private void maybeAutoCheckUpdate() {
        if (updateCheckedOnce) return;
        updateCheckedOnce = true;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            UpdateChecker.check(this, false, (tag, err) -> {
                // 有新版：UpdateChecker 内部已自动后台下载并拉起安装页
                if (tag != null) {
                    android.widget.Toast.makeText(this,
                            "发现新版本 v" + tag + "，正在后台下载更新…", android.widget.Toast.LENGTH_LONG).show();
                }
            });
        }, 2500);
    }

    // ---------- 生命周期 ----------

    @Override
    protected void onPause() {
        super.onPause();
        // 注意：这里不调用 wv.onPause() —— WebView.onPause 会暂停 JS 定时器，
        // 而朗读链(逐句推进的 watchdog/章节预载/滚动事件)全靠 JS 定时器驱动；
        // 转后台/锁屏时暂停定时器会导致朗读在章节末尾悄悄停止、唤醒后乱跳。
        // 不暂停 WebView：朗读链在后台继续由事件驱动(引擎一句句回调推进)。
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 未调用 wv.onPause()，这里无需 wv.onResume()（调了也无副作用）
        maybeAutoCheckUpdate();
    }

    @Override
    public void onBackPressed() {
        // 单页 SPA：返回键先询问 JS（关闭目录/设置面板/退出阅读页），返回 "true" 表示已处理
        if (wv != null) {
            wv.evaluateJavascript(
                    "window.__onAndroidBack&&__onAndroidBack()? 'true':'false'",
                    value -> {
                        boolean handled = value != null && value.contains("true");
                        if (!handled) MainActivity.super.onBackPressed();
                    });
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        webView = null;
        stopService(new Intent(this, ReadAloudService.class));
        if (bridge != null) {
            bridge.destroy();
            if (wv != null) wv.removeJavascriptInterface("NovelBridge");
        }
        if (wv != null) {
            wv.setVisibility(View.GONE);
            wv.destroy();
        }
        super.onDestroy();
    }
}
