package com.like.novelreader;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 系统 TextToSpeech 封装（多引擎回退版）。
 * 策略：
 *  - 引擎枚举走 PackageManager（android.intent.action.TTS_SERVICE），与框架
 *    getEngines() 同机制但无绑定时序问题；配合 Manifest <queries> 在
 *    Android 11+ 也能看到已装引擎（含 ROM 内置/无障碍引擎）；
 *  - 若枚举仍为空，读系统“首选引擎”设置 (tts_default_synth) 按包名显式绑定；
 *  - 按序尝试各引擎：new TextToSpeech(.., pkg) 验证 初始化成功 + 中文可用；
 *  - 每个候选带 watchdog：引擎服务无响应/绑定被系统拒（可见性不足）时
 *    8s 后放弃该候选试下一个，绝不无限挂起；
 *  - 首个可用的引擎被采纳，其余销毁；
 *  - 失败原因分级上报：无引擎 / 引擎初始化失败(带包名) / 缺中文语音数据。
 * 桥只允许 JS 一次提交一句(utterance)，JS 端等 onDone 推下一句。
 */
public final class TtsEngine {

    public interface Listener {
        /** type: 'start' | 'done' | 'error' | 'ready' */
        void onEvent(JSONObject ev);
    }

    private static final long BIND_TIMEOUT_MS = 8000L;   // 单个引擎绑定+初始化超时

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextToSpeech tts;            // 已成功的实例
    private TextToSpeech pending;        // 正在等回调的候选（超时要关）
    private boolean initStarted;         // 已开始初始化、尚未收敛
    private boolean initDone;
    private boolean langOk;
    private String failReason = "";      // 综合失败原因（中文，面向用户）
    private String activeEngine = "";    // 当前生效引擎（包名或“系统默认”）
    private float rate = 1.0f;

    private int roundSeq = 0;            // 整轮代次：init()/destroy() 递增
    private int candSeq = 0;             // 候选代次：同轮里试下一个候选时递增
    private List<String> curCands;       // 当前轮候选表（供 watchdog 续跑）
    private int curIndex = 0;            // 当前尝试到第几个候选
    private final Runnable watchdog = this::watchdogFire;

    public TtsEngine(Context ctx, Listener l) {
        this.ctx = ctx.getApplicationContext();
        this.listener = l;
    }

    // ---------------- 引擎枚举（静态，供 JS 诊断也用） ----------------

    /**
     * 枚举已安装且可绑定的 TTS 引擎包名。
     * 查询 intent-filter 为 android.intent.action.TTS_SERVICE 的服务
     * （与框架 TextToSpeech.getEngines() 同机制）。可见性由 Manifest
     * <queries> 决定：已声明的引擎在 Android 11+ 也能枚举到。
     */
    public static List<String> engines(Context c) {
        List<String> out = new ArrayList<>();
        try {
            Intent i = new Intent("android.intent.action.TTS_SERVICE");
            PackageManager pm = c.getPackageManager();
            int flags = PackageManager.MATCH_DISABLED_COMPONENTS;
            List<ResolveInfo> ris = Build.VERSION.SDK_INT >= 33
                    ? pm.queryIntentServices(i, PackageManager.ResolveInfoFlags.of(flags))
                    : pm.queryIntentServices(i, flags);
            for (ResolveInfo ri : ris) {
                if (ri == null || ri.serviceInfo == null || ri.serviceInfo.packageName == null) continue;
                String p = ri.serviceInfo.packageName;
                if (!p.isEmpty() && !out.contains(p)) out.add(p);
            }
        } catch (Throwable t) {
            // 极老设备/厂商限制：退回框架 API（一次轻量探针）
            try {
                TextToSpeech probe = new TextToSpeech(c.getApplicationContext(), status -> { /* noop */ });
                try {
                    for (TextToSpeech.EngineInfo e : probe.getEngines()) {
                        if (e.name != null && !e.name.isEmpty() && !out.contains(e.name)) out.add(e.name);
                    }
                } finally {
                    try { probe.shutdown(); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored2) {
            }
        }
        return out;
    }

    /** 系统设置里用户选定的默认引擎包名（直接读设置，不依赖包可见性） */
    public static String defaultEnginePkg(Context c) {
        try {
            return Settings.Secure.getString(c.getContentResolver(), "tts_default_synth");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JSONObject ev(String type, String a, String b) {
        JSONObject o = new JSONObject();
        try { o.put("type", type); o.put("a", a); o.put("b", b); } catch (Exception ignored) {}
        return o;
    }

    // ---------------- 状态 ----------------

    /** 当前是否可用 */
    public synchronized boolean isUsable() {
        return initDone && langOk && tts != null;
    }

    /** 是否仍在初始化中（JS 据此区分“还没好”与“已失败”，避免误报未安装） */
    public synchronized boolean isBusy() {
        return initStarted && !initDone;
    }

    // ---------------- 初始化 ----------------

    /** 开始（或重新开始）初始化：逐个尝试可用引擎 */
    public synchronized void init() {
        if (tts != null) {
            try { tts.shutdown(); } catch (Throwable ignored) {}
            tts = null;
        }
        initStarted = true;
        initDone = false;
        langOk = false;
        failReason = "";
        roundSeq++;                      // 新一轮：所有旧回调作废
        candSeq = 0;
        curCands = null;
        curIndex = 0;
        if (pending != null) { shutdown(pending); pending = null; }

        // 候选集：系统“首选引擎”优先（用户意图），再并上枚举结果（去重）。
        // 枚举为空但设置里有默认引擎时按包名显式绑定 → Android 11+ 可见性兜底。
        LinkedHashSet<String> seen = new LinkedHashSet<>(engines(ctx));
        String def = defaultEnginePkg(ctx);
        if (def != null && !def.isEmpty()) {
            seen.remove(def);
            LinkedHashSet<String> ordered = new LinkedHashSet<>();
            ordered.add(def);
            ordered.addAll(seen);
            seen = ordered;
        }
        List<String> cands = new ArrayList<>();
        cands.add(null);                 // null = 跟随系统默认（不指定引擎）
        cands.addAll(seen);
        tryNext(roundSeq, cands, 0);
    }

    /** 尝试第 k 个候选（在调用线程已持有锁的前提下递归调用） */
    private void tryNext(final int seq, final List<String> cands, final int k) {
        curCands = cands;
        curIndex = k;
        if (k >= cands.size()) {
            // 全部候选失败 → 上报综合原因（含各候选失败/超时细节）
            main.removeCallbacks(watchdog);
            if (pending != null) { shutdown(pending); pending = null; }
            initStarted = false;
            listener.onEvent(ev("ready", "error", stateReason()));
            return;
        }
        final String pkg = cands.get(k);
        final int myCand = ++candSeq;    // 本候选专属代次
        final TextToSpeech[] box = new TextToSpeech[1];

        try {
            box[0] = new TextToSpeech(ctx, status -> {
                final TextToSpeech me = box[0];
                if (me == null) return;
                synchronized (TtsEngine.this) {
                    if (seq != roundSeq || myCand != candSeq) {
                        // 已被 watchdog / 新一轮取代：只关自己，不碰新候选
                        if (pending == me) pending = null;
                        shutdown(me);
                        return;
                    }
                    main.removeCallbacks(watchdog);
                    if (status != TextToSpeech.SUCCESS) {
                        failReason += (pkg == null ? "(系统默认引擎)" : pkg) + " 初始化失败; ";
                        pending = null;
                        shutdown(me);
                        tryNext(seq, cands, k + 1);
                        return;
                    }
                    pending = null;      // 毕业，交给 tts 管理
                    // 验证中文可用（部分引擎需二次 setLanguage 才报告正确）
                    int r = me.setLanguage(Locale.SIMPLIFIED_CHINESE);
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        r = me.setLanguage(Locale.CHINESE);
                    }
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        failReason += (pkg == null ? "默认引擎" : pkg) + " 缺中文语音数据; ";
                        shutdown(me);
                        tryNext(seq, cands, k + 1);
                        return;
                    }
                    // 成功：挂上事件监听后转正
                    me.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String utteranceId) {
                            listener.onEvent(ev("start", utteranceId, null));
                        }
                        @Override public void onDone(String utteranceId) {
                            listener.onEvent(ev("done", utteranceId, null));
                        }
                        @Deprecated
                        @Override public void onError(String utteranceId) {
                            listener.onEvent(ev("error", utteranceId, "ERROR"));
                        }
                        @Override public void onError(String utteranceId, int errorCode) {
                            listener.onEvent(ev("error", utteranceId, "ERROR_" + errorCode));
                        }
                    });
                    tts = me;
                    initDone = true;
                    langOk = true;
                    activeEngine = pkg == null ? "系统默认" : pkg;
                    try { me.setSpeechRate(rate); } catch (Throwable ignored) {}
                    initStarted = false;
                    listener.onEvent(ev("ready", "ok", activeEngine));
                }
            }, pkg);                     // 指定引擎（null=默认）
            pending = box[0];
        } catch (Throwable t) {
            // 构造即抛（罕见）：显式包名不可见 / 引擎无效。不挂起，试下一个
            pending = null;
            failReason += (pkg == null ? "(系统默认引擎)" : pkg) + " 绑定异常("
                    + t.getClass().getSimpleName() + "); ";
            tryNext(seq, cands, k + 1);
            return;
        }
        // watchdog：本候选 BIND_TIMEOUT_MS 内无回调（服务不响应/被可见性拦）
        // → 作废本候选（candSeq 前移令迟到回调自判出局），继续下一个
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, BIND_TIMEOUT_MS);
    }

    private void watchdogFire() {
        synchronized (this) {
            if (initDone || tts != null) return;      // 已成功
            if (roundSeq == 0) return;                // 从未 init（防御）
            final int seq = roundSeq;
            if (pending != null) { shutdown(pending); pending = null; }
            failReason += (curIndex < curCands.size()
                    ? (curCands.get(curIndex) == null ? "(系统默认引擎)" : curCands.get(curIndex))
                    : "未知引擎") + " 无响应(超时" + (BIND_TIMEOUT_MS / 1000) + "s); ";
            if (curCands != null && curIndex + 1 < curCands.size()) {
                candSeq++;                            // 迟到回调判定自己出局
                tryNext(seq, curCands, curIndex + 1);
            } else {
                // 最后一个候选也超时 → 收敛上报（不再无限等）
                initStarted = false;
                listener.onEvent(ev("ready", "error", stateReason()));
            }
        }
    }

    private static void shutdown(TextToSpeech t) {
        if (t != null) {
            try { t.stop(); } catch (Throwable ignored) {}
            try { t.shutdown(); } catch (Throwable ignored) {}
        }
    }

    // ---------------- 面向用户的原因 ----------------

    /** 面向用户的失败原因；无引擎时给出安装建议 */
    public synchronized String stateReason() {
        if (isUsable()) return "";
        List<String> es = engines(ctx);
        String def = defaultEnginePkg(ctx);
        if (es.isEmpty()) {
            if (def != null && !def.isEmpty()) {
                // 系统设置里配了引擎但 App 仍用不了：多半是该引擎本身状态异常/被停用
                String detail = failReason.isEmpty() ? "" : "（细节：" + failReason + "）";
                return "系统已配置语音引擎(" + def + ")，但它当前不可用。请到 系统设置→文字转语音→首选引擎，确认引擎已启用、语音数据正常后重试。" + detail;
            }
            String detail = failReason.isEmpty() ? "" : "（细节：" + failReason + "）";
            return "未检测到任何语音引擎。请安装一个中文语音引擎后重试：讯飞语记、Google 文本转语音，或在 系统设置→文字转语音 启用手机自带引擎。" + detail;
        }
        String base = failReason.isEmpty() ? "可用引擎均不支持中文：" : failReason;
        return base + "已检测到引擎：" + String.join("、", es)
                + "。若提示缺中文语音数据，请到 系统设置→语言与输入→文字转语音 下载普通话语音后重试。";
    }

    // ---------------- 朗读控制 ----------------

    /** 提交一句。返回 null=成功，否则原因。 */
    public synchronized String speak(String text, String utteranceId) {
        if (tts == null || !isUsable()) return stateReason();
        try {
            int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
            if (r == TextToSpeech.ERROR) return "播放失败";
            return null;
        } catch (Throwable t) {
            return "播放异常: " + t.getMessage();
        }
    }

    public synchronized void stop() {
        if (tts == null) return;
        try { tts.stop(); } catch (Throwable ignored) {}
    }

    /** 暂停：stop（JS 端冻结推进，继续时以新 utterance 重读当前句） */
    public synchronized void pause() { stop(); }

    public synchronized void setRate(float r) {
        rate = Math.max(0.4f, Math.min(2.5f, r));
        if (tts != null && initDone) {
            try { tts.setSpeechRate(rate); } catch (Throwable ignored) {}
        }
    }

    public synchronized void destroy() {
        roundSeq++;                    // 整轮作废：旧回调/旧 watchdog 全部失效
        main.removeCallbacks(watchdog);
        curCands = null;
        curIndex = 0;
        if (pending != null) { shutdown(pending); pending = null; }
        shutdown(tts);
        tts = null;
        initStarted = false;
        initDone = false;
        langOk = false;
    }
}
