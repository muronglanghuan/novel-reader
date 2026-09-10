package com.like.novelreader;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * 朗读期间的静音音轨：让系统把本应用认定为"正在播放音频的应用"。
 *
 * <p>为什么必须有：TTS 是引擎进程在出声，系统的
 * {@code MediaSessionStack#updateMediaButtonSessionIfNeeded} 只会把媒体按键派给
 * "最近播放过音频的 UID"（{@code AudioPlayerStateMonitor}）。引擎的 UID 上没有
 * 媒体会话，于是本应用的锁屏卡片和耳机按键都会失联——单击耳机没有任何反应。
 * 自己持有一条循环播放、音量为 0 的音轨后，本应用的 UID 进入该名单，媒体按键
 * 与锁屏卡片的"当前媒体应用"才会落到我们头上。
 *
 * <p>实现为 MODE_STATIC + 循环点，不需要任何回放线程，CPU 开销可忽略。
 * 音频数据取一个极小的非零值：全 0 在个别 ROM 上会被当成"未播放"。
 *
 * <p>暂停朗读时<b>不要</b>停掉它：停了就不再是"播放中的应用"，耳机按键随即失联，
 * 用户没法用耳机把朗读续上。把音量设为 0，人耳听不到，系统仍认我们在播。
 */
final class SilentAudioKeepAlive {

    private static final int SAMPLE_RATE = 8000;
    private static final int FRAMES = 4000;            // 0.5s 单声道
    private static final short AMPLITUDE = 1;          // 最低有效位，人耳不可闻

    private AudioTrack track;

    /** 开始"播放"（重复调用无副作用） */
    synchronized void start() {
        if (track != null) return;
        AudioTrack t = null;
        try {
            short[] pcm = new short[FRAMES];
            for (int i = 0; i < FRAMES; i++) pcm[i] = AMPLITUDE;
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(pcm.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            int written = t.write(pcm, 0, pcm.length);
            if (written <= 0) { t.release(); return; }
            t.setLoopPoints(0, written, -1);           // 无限循环
            t.setVolume(0f);                           // 静音（仍保持"播放中"状态）
            t.play();
            track = t;
        } catch (Throwable e) {
            if (t != null) { try { t.release(); } catch (Throwable ignored) {} }
        }
    }

    /** 朗读结束：释放音轨，本应用不再占用"正在播放"身份 */
    synchronized void stop() {
        AudioTrack t = track;
        track = null;
        if (t == null) return;
        try { t.stop(); } catch (Throwable ignored) {}
        try { t.release(); } catch (Throwable ignored) {}
    }
}
