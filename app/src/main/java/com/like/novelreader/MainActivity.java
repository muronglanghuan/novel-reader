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

    private static final int REQ_IMPORT = 4242;
    private static final int REQ_RESTORE = 4243;

    private WebView wv;
    private Bridge bridge;
    private WebViewAssetLoader assetLoader;

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
        s.setBlockNetworkLoads(true);        // 纯本地应用，禁网
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

    // ---------- 生命周期 ----------

    @Override
    protected void onPause() {
        super.onPause();
        if (wv != null) wv.onPause();
        // 不暂停 JS 定时器：朗读链由 JS 驱动，转后台时继续（无前台服务时引擎可持续一句句读）
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wv != null) wv.onResume();
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
