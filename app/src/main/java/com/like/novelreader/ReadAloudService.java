package com.like.novelreader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

/**
 * 朗读前台服务：朗读期间以媒体播放前台服务保活 + 部分唤醒锁。
 * 没有它，手机息屏/切后台一段时间后进程会被系统冻结（Doze/厂商省电），
 * TTS 回调无法送达 → 朗读悄悄停止；回到前台才续读。
 * 前台服务 + 唤醒锁保证息屏期间 CPU 保持可用，朗读持续到手动/定时停止。
 */
public final class ReadAloudService extends Service {

    public static final String ACTION_START = "com.like.novelreader.readaloud.START";
    public static final String ACTION_STOP = "com.like.novelreader.readaloud.STOP";
    public static final String EXTRA_TITLE = "title";

    private static final String CHANNEL_ID = "reading";
    private static final int NOTIF_ID = 7;
    private PowerManager.WakeLock wakeLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String act = intent.getAction();
        if (ACTION_STOP.equals(act)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // ACTION_START（可重复调用刷新标题）
        String title = intent.getStringExtra(EXTRA_TITLE);
        if (title == null || title.isEmpty()) title = "正在朗读…";
        acquireWakeLock();
        startForegroundInternal(title);
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
        super.onDestroy();
    }

    private void startForegroundInternal(String title) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "朗读", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        // 点通知打开 App；「停止」按钮直接停止朗读
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, MainActivity.class);
        stop.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        stop.setAction(MainActivity.ACTION_STOP_READING);
        PendingIntent stopPi = PendingIntent.getActivity(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_play)
                .setContentTitle("小说有声阅读")
                .setContentText(title)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(0, "停止朗读", stopPi)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, notif);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
