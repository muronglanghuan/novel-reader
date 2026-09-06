package com.like.novelreader;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * 进度外部镜像备份：把全量进度写入公共「下载/NovelReader」目录(API 29+)，
 * 卸载重装后由 App 首次启动自动检测恢复；同时也方便用户手动拷贝换机。
 * API 24-28 无公共写权限 → 静默跳过（该区间靠系统云备份/设备迁移保数据）。
 */
public final class BackupManager {

    public static final String FILE_NAME = "小说有声阅读-阅读进度.json";
    public static final String NAME_PREFIX = "小说有声阅读-阅读进度";
    private static final String REL_DIR = Environment.DIRECTORY_DOWNLOADS + "/NovelReader";

    private BackupManager() {}

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= 29;
    }

    private static Uri collection() {
        return MediaStore.Downloads.EXTERNAL_CONTENT_URI;
    }

    /** 找已有镜像行 id：先精确文件名，再无则 LIKE 最新一条。无则 -1 */
    private static long findExistingId(ContentResolver cr) {
        try (android.database.Cursor c = cr.query(collection(),
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{FILE_NAME}, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Throwable ignored) {}
        try (android.database.Cursor c = cr.query(collection(),
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                new String[]{NAME_PREFIX + "%"},
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Throwable ignored) {}
        return -1;
    }

    /**
     * 写镜像：upsert（命中既有行 → 直接覆写内容；否则插入）。
     * 进程内串行化，避免并发插入产生系统改名的 (1)/(2) 副本。
     */
    public static synchronized boolean write(Context ctx, JSONObject data) {
        if (!supported()) return false;
        try {
            ContentResolver cr = ctx.getContentResolver();
            byte[] payload = data.toString().getBytes("UTF-8");
            long id = findExistingId(cr);
            Uri uri;
            if (id > 0) {
                uri = Uri.withAppendedPath(collection(), String.valueOf(id));
                try (OutputStream os = cr.openOutputStream(uri, "wt")) {   // wt=截断覆写
                    if (os == null) return false;
                    os.write(payload);
                }
                // 顺手清理历史改名副本（不含当前行）
                cr.delete(collection(),
                        MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? AND "
                                + MediaStore.MediaColumns._ID + " != ?",
                        new String[]{NAME_PREFIX + "%", String.valueOf(id)});
            } else {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, REL_DIR);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                uri = cr.insert(collection(), cv);
                if (uri == null) return false;
                try (OutputStream os = cr.openOutputStream(uri)) {
                    if (os == null) return false;
                    os.write(payload);
                }
                ContentValues fin = new ContentValues();
                fin.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, fin, null, null);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读取镜像（若最近一次写入被异常打断留下 pending 行，追加一次含 pending 的查询兜底）。 */
    public static JSONObject read(Context ctx) {
        if (!supported()) return null;
        try {
            ContentResolver cr = ctx.getContentResolver();
            JSONObject got = queryRead(cr, MediaStore.MediaColumns.IS_PENDING + " = 0");
            if (got == null) got = queryRead(cr, null);   // 兜底：连 pending 一起找
            return got;
        } catch (Throwable ignored) {}
        return null;
    }

    private static JSONObject queryRead(ContentResolver cr, String pendingSel) throws Exception {
        StringBuilder sel = new StringBuilder(MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?");
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add(NAME_PREFIX + "%");
        if (pendingSel != null) {
            sel.append(" AND ").append(pendingSel);
        }
        try (android.database.Cursor c = cr.query(collection(),
                new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED},
                sel.toString(), args.toArray(new String[0]),
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (c == null || !c.moveToFirst()) return null;
            Uri uri = Uri.withAppendedPath(collection(), String.valueOf(c.getLong(0)));
            try (InputStream is = cr.openInputStream(uri)) {
                if (is == null) return null;
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) > 0) bos.write(buf, 0, r);
                String txt = bos.toString("UTF-8");
                if (txt == null || txt.trim().isEmpty()) return null;
                return new JSONObject(txt);
            }
        }
    }
}
