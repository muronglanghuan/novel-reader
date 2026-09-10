package com.like.novelreader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.KeyEvent;

/**
 * 耳机/蓝牙媒体按键兜底入口。
 *
 * <p>系统只在"没有活跃的媒体按键会话"时才把按键派到这里。朗读中会话是活跃的，
 * 按键由 {@link ReadAloudService} 的 MediaSession 接管；但一旦暂停，播放状态变成
 * PAUSED，部分机型/耳机便不再把会话当作按键目标，转而派发到这里。
 * （真机实测：第一次单击能暂停，第二次单击就收不到 MediaSession 回调了。）
 *
 * <p>所以这个兜底必须能真正驱动朗读——只把服务拉起来是不够的，朗读链在 JS 里，
 * 必须把命令送到 WebView。这里统一转发给服务，复用与通知按钮完全相同的那条通路
 * （服务会处理"WebView 还没起来"的等待与重试）。
 *
 * <p>只在"确实有朗读会话"时动作，避免在桌面误按耳机键就把小说读起来。
 */
public final class MediaButtonReceiver extends BroadcastReceiver {

    private static final long RESUME_TTL_MS = 6 * 60 * 60 * 1000L;   // 6 小时内的暂停可续

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;
        KeyEvent ke = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (ke == null) return;
        // 只认单击的按下沿（长按/抬起一律忽略）
        if (ke.getAction() != KeyEvent.ACTION_DOWN || ke.getRepeatCount() != 0) return;
        int code = ke.getKeyCode();
        if (code != KeyEvent.KEYCODE_HEADSETHOOK
                && code != KeyEvent.KEYCODE_MEDIA_PLAY
                && code != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return;

        SharedPreferences sp = context.getSharedPreferences("pending", Context.MODE_PRIVATE);
        String state = sp.getString("novelreader.pendingCmd", "");
        long at = sp.getLong("novelreader.pendingAtCmd", 0L);
        if (state.isEmpty() || "idle".equals(state)) return;      // 没有朗读会话，不打扰
        if (System.currentTimeMillis() - at > RESUME_TTL_MS) return;

        boolean paused = "pause".equals(state);
        try {
            Intent i = new Intent(context, ReadAloudService.class);
            i.setAction(ReadAloudService.ACTION_START);
            // 暂停中→续读；正在读→按"切换"让它暂停（与 App 内按钮一致）
            i.putExtra("cmd", paused ? "play" : "toggle");
            i.putExtra("cmdAt", System.currentTimeMillis());
            i.putExtra("state", paused ? "paused" : "reading");
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
            else context.startService(i);
        } catch (Throwable ignored) {}
    }
}
