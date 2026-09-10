package com.like.novelreader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.KeyEvent;

/**
 * 耳机/蓝牙媒体按键兜底入口。
 *
 * <p>朗读期间按键由 ReadAloudService 的 MediaSession 优先接管（能识别单击/双击/
 * 三击），本接收器收不到；只有在没有活跃会话时（朗读已停止/被系统回收）系统才会
 * 派发到这里。此时只有耳机上"播放"键的单击是有意义的——用来把朗读重新拉起来，
 * 继续上次暂停的位置；其余按键忽略，避免误触发。
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
        if (ke.getKeyCode() != KeyEvent.KEYCODE_HEADSETHOOK
                && ke.getKeyCode() != KeyEvent.KEYCODE_MEDIA_PLAY
                && ke.getKeyCode() != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return;

        SharedPreferences sp = context.getSharedPreferences("pending", Context.MODE_PRIVATE);
        String last = sp.getString("novelreader.pendingCmd", "");
        long at = sp.getLong("novelreader.pendingAtCmd", 0L);
        if (!"pause".equals(last)) return;                        // 不是"暂停中"，不续读
        if (System.currentTimeMillis() - at > RESUME_TTL_MS) return;

        ReadAloudService.start(context);
    }
}
