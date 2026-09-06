package com.like.novelreader;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

/**
 * 唯一 Activity：全屏 WebView 承载整站 UI，本地资源经 WebViewAssetLoader 以
 * https://appassets.androidplatform.net/assets/ 提供（避免 file:// 限制）。
 */
public final class MainActivity extends Activity implements Bridge.Host {

    public static final String ACTION_STOP_READING = "com.like.novelreader.STOP_READING";

    private static final int REQ_IMPORT = 4242;
    private static final int REQ_RESTORE = 4243;

    private WebView wv;
    private Bridge bridge;
    private WebViewAssetLoader assetLoader;

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 通知栏「停止朗读」/ 点通知回 App
        if (intent != null && ACTION_STOP_READING.equals(intent.getAction()) && wv != null) {
            try {
                wv.evaluateJavascript("window.stopTts&&stopTts(false);", null);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 调试构建开启 WebView 远程调试（便于 adb + CDP 自动化验证）
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true);
        }

        wv = new WebView(this);
        setContentView(wv);

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
