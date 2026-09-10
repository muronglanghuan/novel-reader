package com.like.novelreader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.KeyEvent;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;
// 注意：androidx.core 的 NotificationCompat 也有个同名 MediaStyle（仅占位、不支持
// 会话），媒体卡片必须用 androidx.media 这一个，故显式导入避免遮蔽。
import androidx.media.app.NotificationCompat.MediaStyle;

import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

/**
 * 朗读前台服务：朗读期间以媒体播放前台服务保活 + 部分唤醒锁 + 媒体会话。
 *
 * <p>保活：前台服务 + PARTIAL_WAKE_LOCK 让 CPU 在息屏期间保持可用；配合
 * MainActivity 的 setRendererPriorityPolicy(IMPORTANT, false)，WebView 渲染进程
 * 在后台/息屏也不会被降级回收，朗读链(引擎回调→下一句)持续运转。
 *
 * <p>媒体卡片：MediaSession + MediaStyle 通知，锁屏/通知栏显示可点的
 * 上一章 / 播放暂停 / 下一章，并把耳机(尤其蓝牙)按键接管过来
 * ——单击播放暂停、双击下一章、三击上一章，由系统 PlaybackState 自动分派。
 *
 * <p>按键路由的前提：朗读时 TTS 引擎在真实出声，系统据此把本应用记为"最近播放的
 * 媒体应用"，媒体按键才会派到本会话(见 MediaSessionStack#updateMediaButtonSessionIfNeeded)。
 * 所以这里必须如实上报 PLAYING/PAUSED；若引擎不出声(静音/被打断)，按键会落到
 * MediaButtonReceiver 兜底。
 *
 * <p>命令通路：所有控制(通知按钮/耳机按键/锁屏)统一发一条"命令"给 WebView，
 * 由 JS 侧 onControlCommand 唯一处理，保证行为与 App 内按钮完全一致。
 */
public final class ReadAloudService extends Service {

    public static final String ACTION_START = "com.like.novelreader.readaloud.START";
    public static final String ACTION_STOP = "com.like.novelreader.readaloud.STOP";
    public static final String EXTRA_TITLE = "title";

    /**
     * 朗读状态持久化（供 {@link MediaButtonReceiver} 在会话缺席时判断该不该响应耳机键）。
     * 值只取下面两个状态常量，不存命令名——{@link #CMD_TOGGLE} 这类没有确定含义的命令
     * 一旦落盘，"当前是否暂停"就丢了。
     */
    private static final String PENDING_KEY = "novelreader.pendingCmd";
    /** 正在朗读 */
    static final String STATE_READING = "reading";
    /** 已暂停（位置保留，可续读） */
    static final String STATE_PAUSED = "paused";
    /** 已结束（停止/退出），耳机键不再响应 */
    static final String STATE_IDLE = "idle";
    private static final String PENDING_AT = "novelreader.pendingAtCmd";
    private static final long PENDING_TTL_MS = 20000L;

    private static final String CMD_PLAY = "play";
    private static final String CMD_PAUSE = "pause";
    private static final String CMD_TOGGLE = "toggle";
    private static final String CMD_STOP = "stop";
    private static final String CMD_PREV = "prev";
    private static final String CMD_NEXT = "next";

    private static final String CHANNEL_ID = "reading";
    private static final int NOTIF_ID = 7;
    private static final int MAX_ACTIONS = 5;      // MediaStyle 最多 5 个动作
    private static final int FOCUS_GAIN_MS = 4000; // 短暂音频焦点时长

    @SuppressWarnings("FieldCanBeLocal")
    private MediaSessionCompat session;
    private PowerManager.WakeLock wakeLock;
    private AudioManager am;
    private AudioFocusRequest focusRequest;
    private final SilentAudioKeepAlive silentAudio = new SilentAudioKeepAlive();

    private String title = "正在朗读…";
    private String chapterName = "";
    /** 当前是否"正在读"：暂停时仍为 true，卡片保留可继续 */
    private boolean reading;
    /** 定时停止的到点时刻(ms，SystemClock.elapsedRealtime)；0=未设置 */
    private long timerAtElapsed;

    // ---------------- 生命周期 ----------------

    @Override
    public void onCreate() {
        super.onCreate();
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        initSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String act = intent.getAction();
        if (ACTION_STOP.equals(act)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // ACTION_START（可重复调用刷新标题 / 状态 / 定时）
        String t = intent.getStringExtra(EXTRA_TITLE);
        if (t != null && !t.isEmpty()) title = t;
        String ch = intent.getStringExtra("chapter");
        if (ch != null) chapterName = ch;
        String state = intent.getStringExtra("state");
        boolean wasReading = reading;
        if (state != null) {
            reading = !"idle".equals(state);
            // 把 JS 确认过的状态落盘：会话缺席时 MediaButtonReceiver 靠它判断
            // 该不该用耳机键把朗读拉起来
            writePending(reading ? STATE_READING : STATE_PAUSED);
        }

        long timerLeftMs = intent.getLongExtra("timerLeftMs", -1L);
        timerAtElapsed = timerLeftMs > 0
                ? SystemClock.elapsedRealtime() + timerLeftMs : 0L;

        acquireWakeLock();
        if (!wasReading && reading) requestFocus();   // 起读/续读才抢焦点
        // 静音音轨：让本应用留在系统的"最近播放音频的应用"名单里，耳机按键与锁屏卡片
        // 才会路由到本会话（详见 SilentAudioKeepAlive）。整个朗读会话期间一直播放，
        // 暂停也不停——一旦退出名单，蓝牙耳机的下一次单击就没着落。
        silentAudio.start();
        startForegroundInternal(buildNotification());
        applyState();

        // 从通知按钮冷启动(服务之前已被回收)：把命令转交给 JS（JS 未就绪则重试）
        String cmd = intent.getStringExtra("cmd");
        if (cmd != null) {
            long at = intent.getLongExtra("cmdAt", 0L);
            if (!expired(at)) sendCommand(cmd);
            else clearPending();
        }
        return START_NOT_STICKY;
    }

    /** 部分唤醒锁：息屏期间 CPU 不睡，朗读链(引擎回调→下一句)持续运转 */
    private void acquireWakeLock() {
        try {
            if (wakeLock == null || !wakeLock.isHeld()) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "novelreader:reading");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Throwable ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {}
        wakeLock = null;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        abandonFocus();
        silentAudio.stop();
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        // 服务被回收说明本次朗读已终止，清掉残留的待执行命令，避免下次误触发
        clearPending();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------- 媒体会话(锁屏卡片 + 耳机按键) ----------------

    private void initSession() {
        try {
            session = new MediaSessionCompat(this, "NovelReader");
            session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new MediaSessionCompat.Callback() {
                // 控制器(锁屏卡片按钮、部分车机/蓝牙)直接调这些方法
                @Override public void onPlay() { onCmd(CMD_PLAY); }

                @Override public void onPause() { onCmd(CMD_PAUSE); }

                @Override public void onStop() { onCmd(CMD_STOP); }

                @Override public void onSkipToNext() { onCmd(CMD_NEXT); }

                @Override public void onSkipToPrevious() { onCmd(CMD_PREV); }

                /**
                 * 耳机(尤其蓝牙)按键的原始 KeyEvent 由系统送到这里，必须自己翻译。
                 *
                 * <p>androidx 的 Callback.onMediaButtonEvent 在 SDK &gt;= 27 上直接
                 * return false，把翻译交给平台；而平台的 MediaSession.Callback 默认
                 * 也是 return false —— 两边都不做，表现就是按键已派发到本会话却毫无反应
                 * (dumpsys 里能看到 "Sending KeyEvent ... to com.like.novelreader")。
                 *
                 * <p>蓝牙耳机的连击由耳机固件翻译成 NEXT/PREVIOUS 键送来，所以按
                 * 键码分派即可覆盖 单击暂停继续 / 双击下一章 / 三击上一章。
                 */
                @Override public boolean onMediaButtonEvent(Intent intent) {
                    KeyEvent ke = intent == null
                            ? null : intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (ke == null) return false;
                    // 只认按下沿：既避免按下+抬起触发两次，也让系统认为事件已处理
                    if (ke.getAction() != KeyEvent.ACTION_DOWN) return true;
                    if (ke.getRepeatCount() > 0) return true;      // 长按不回放
                    switch (ke.getKeyCode()) {
                        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        case KeyEvent.KEYCODE_HEADSETHOOK:
                            // 一律发"切换"，由 JS 按它自己的真实状态决定暂停还是续读。
                            // 原生侧再存一份状态就会有两份真相：通知卡片的动作 Intent 是
                            // 上一轮构建的，带回来的旧状态会把本地状态改错(实测表现为
                            // "按一下没反应、再按一下才暂停"、"暂停后按不回去")。
                            onCmd(CMD_TOGGLE);
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_PLAY:
                            // 蓝牙耳机在"已暂停"时会报 PLAY 而不是 PLAY_PAUSE。
                            // 此时若丢掉会话(暂停即停播)或把它当"切换"，下一次单击就会
                            // 没有着落——真机反馈的"能暂停、再按不继续"正是这么来的。
                            // 所以播放键按"续读"处理：JS 侧有位置就接着读，没有就重开。
                            onCmd(CMD_PLAY);
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_PAUSE:
                            onCmd(CMD_PAUSE);
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_NEXT:
                            onCmd(CMD_NEXT);
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                            onCmd(CMD_PREV);
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_STOP:
                            onCmd(CMD_STOP);
                            return true;
                        default:
                            return false;
                    }
                }
            });

            session.setMetadata(buildMetadata());
            applyState();
            session.setActive(true);

            IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(noisyReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(noisyReceiver, f);
            }
        } catch (Throwable ignored) {}
    }

    /** 拔耳机/蓝牙断开：立刻暂停，避免外放打扰他人 */
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) return;
            if (!reading) return;
            writePending(STATE_PAUSED);
            sendCommand("pause");
        }
    };

    /**
     * 一条来自会话(锁屏卡片/耳机按键)的命令。
     * 这里不落盘状态：状态以 JS 回调过来的为准（见 onStartCommand 里的 writePending），
     * 否则 "toggle" 这类指令会把"当前是否暂停"冲掉。
     */
    private void onCmd(String cmd) {
        sendCommand(cmd);
    }

    /** 播放态：决定锁屏卡片显示"播放"还是"暂停"，也决定双击/三击是否分派到切章 */
    private void applyState() {
        if (session == null) return;
        try {
            long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_STOP
                    | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
            int state = reading ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
            // 朗读是逐句合成的，句与句之间没有连续音频流，此处按“播放中”如实上报。
            // 不能上报 BUFFERING：系统要求按键持有者是播放中的会话，而 POSITION_UNKNOWN
            // 的 BUFFERING 会被 MediaSessionService 判为“在缓冲所以不接按键”整个吃掉。
            // 状态 PAUSED 正是我们暂停时的真实情形（按播放键会被分派回来继续），无需特判。
            session.setPlaybackState(new PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                    .build());
            session.setMetadata(buildMetadata());
        } catch (Throwable ignored) {}
    }

    private MediaMetadataCompat buildMetadata() {
        try {
            return new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, chapterName)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "小说有声阅读")
                    .build();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 音频焦点(被打断自动暂停) ----------------

    private void requestFocus() {
        if (am == null) return;
        try {
            if (focusRequest == null) {
                // 用 MAY_DUCK 而非独占：导航播报/微信语音等会压低我们的音量继续读，
                // 不被抢占；只有真正永久失焦(来电、别的播放器接管)才暂停。
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setOnAudioFocusChangeListener(change -> {
                            // 短暂失焦(导航播报等)由引擎混音处理；永久失焦才暂停并放手
                            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                                abandonFocus();
                                writePending(STATE_PAUSED);
                                sendCommand(CMD_PAUSE);
                            }
                        })
                        .build();
            }
            am.requestAudioFocus(focusRequest);
        } catch (Throwable ignored) {}
    }

    private void abandonFocus() {
        try {
            if (am != null && focusRequest != null) am.abandonAudioFocusRequest(focusRequest);
        } catch (Throwable ignored) {}
    }

    // ---------------- 命令通路(通知/耳机 → JS) ----------------

    /**
     * 记下朗读状态，供 {@link MediaButtonReceiver} 在会话缺席(用户已经停止)时
     * 判断该不该响应耳机键。只写 {@link #STATE_READING}/{@link #STATE_PAUSED}/
     * {@link #STATE_IDLE}——写命令名会让"当前是否暂停"这个信息丢失。
     */
    private void writePending(String state) {
        getSharedPreferences("pending", MODE_PRIVATE).edit()
                .putString(PENDING_KEY, state)
                .putLong(PENDING_AT, System.currentTimeMillis()).apply();
    }

    private void clearPending() {
        getSharedPreferences("pending", MODE_PRIVATE).edit().clear().apply();
    }


    private static boolean expired(long at) {
        return at > 0 && System.currentTimeMillis() - at > PENDING_TTL_MS;
    }

    /**
     * 把命令送到 WebView。
     *
     * <p>WebView 还活着时只投递、<b>不</b>拉起界面 —— 锁屏上点一次暂停就把 App
     * 拽到前台(还要越过锁屏)是不可接受的；渲染进程已按 IMPORTANT 保活，后台的
     * WebView 依然能执行 evaluateJavascript。
     *
     * <p>只有进程被系统回收(WebView 不存在)时才拉起界面重新加载，因为朗读链跑在
     * JS 里，没有 WebView 就没有可执行的上下文。投递本身由
     * {@link MainActivity#deliverCommand} 按 120ms 重试到 JS 回执为止。
     */
    private void sendCommand(String cmd) {
        if (!MainActivity.hasWebView()) {
            try {
                Intent i = new Intent(this, MainActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(i);
            } catch (Throwable ignored) {}
        }
        MainActivity.deliverCommand(getApplicationContext(), cmd);
    }

    // ---------------- 通知 ----------------

    private PendingIntent controlPi(String cmd, int reqCode) {
        Intent i = new Intent(this, ReadAloudService.class);
        i.setAction(ACTION_START);
        i.putExtra("cmd", cmd);
        i.putExtra("cmdAt", System.currentTimeMillis());
        i.putExtra("state", reading ? "reading" : "paused");
        i.putExtra(EXTRA_TITLE, title);
        i.putExtra("chapter", chapterName);
        i.putExtra("timerLeftMs", timerAtElapsed > 0
                ? Math.max(0, timerAtElapsed - SystemClock.elapsedRealtime()) : -1L);
        return PendingIntent.getForegroundService(this, reqCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "朗读", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        // 点通知打开 App
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = reading ? title : "已暂停 · " + title;
        if (timerAtElapsed > 0) {
            long left = Math.max(0, timerAtElapsed - SystemClock.elapsedRealtime());
            text += " · 定时 " + (left / 60000 + 1) + " 分钟";
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_play)
                .setContentTitle("小说有声阅读")
                .setContentText(text)
                .setOngoing(reading)          // 暂停时允许划掉
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPi)
                .addAction(0, "退出朗读", controlPi(CMD_STOP, 9))
                .addAction(R.drawable.ic_stat_prev, "上一章", controlPi(CMD_PREV, 5))
                .addAction(reading
                                ? R.drawable.ic_stat_pause : R.drawable.ic_stat_play,
                        reading ? "暂停朗读" : "继续朗读", controlPi(CMD_TOGGLE, 6))
                .addAction(R.drawable.ic_stat_next, "下一章", controlPi(CMD_NEXT, 7))
                // 第 4 个动作是"暂停"而非"停止"：本应用只有暂停态(位置保留、可续读)，
                // 真正的结束在 App 内。这样卡片始终在线，用户在锁屏上随时能续播。
                .addAction(R.drawable.ic_stat_stop, "暂停朗读", controlPi(CMD_PAUSE, 8));
        if (session != null) {
            b.setStyle(new MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));   // 锁屏只放三个主控制
        }
        return b.build();
    }

    // ---------------- 起前台 / 刷新 ----------------

    private void startForegroundInternal(Notification notif) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, notif);
        }
    }

    /** 供 MediaButtonReceiver 在会话缺席时兜底调用 */
    public static void start(Context ctx) {
        start(ctx, null);
    }

    /**
     * 拉起前台服务。state 非空时同时刷新卡片状态（"reading"/"paused"）；
     * 传 null 表示只保活、不改状态——状态一律以 JS 回调过来的为准，
     * 在原生侧另存一份必然会与通知卡片携带的旧值打架。
     */
    public static void start(Context ctx, String state) {
        try {
            Intent i = new Intent(ctx, ReadAloudService.class);
            i.setAction(ACTION_START);
            if (state != null) i.putExtra("state", state);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable ignored) {}
    }
}
