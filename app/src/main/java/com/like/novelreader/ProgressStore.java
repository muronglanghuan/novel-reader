package com.like.novelreader;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

/**
 * 进度/设置持久化：SharedPreferences。
 * 每本书一个 key p:{id} → JSON {ch,par,updatedAt}；
 * 全局设置键 settings → JSON；最近打开的书 lastBook → 书名 id。
 * 导出/导入用于外部镜像备份（卸载重装后自动恢复）。
 */
public final class ProgressStore {

    private static final String SP = "reader_state";
    private static final String KEY_PREFIX = "p:";
    private static final String KEY_SETTINGS = "settings";
    private static final String KEY_LAST = "lastBook";

    private ProgressStore() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    public static void saveBook(Context ctx, String bookId, JSONObject progress) {
        if (bookId == null || bookId.isEmpty()) return;
        sp(ctx).edit().putString(KEY_PREFIX + bookId, progress.toString()).apply();
    }

    /** 返回 JSON 字符串或 null */
    public static String loadBook(Context ctx, String bookId) {
        if (bookId == null) return null;
        return sp(ctx).getString(KEY_PREFIX + bookId, null);
    }

    public static void removeBook(Context ctx, String bookId) {
        sp(ctx).edit().remove(KEY_PREFIX + bookId).apply();
    }

    public static void saveSettings(Context ctx, JSONObject settings) {
        sp(ctx).edit().putString(KEY_SETTINGS, settings.toString()).apply();
    }

    /** 返回 JSON 字符串或 null */
    public static String loadSettings(Context ctx) {
        return sp(ctx).getString(KEY_SETTINGS, null);
    }

    public static JSONObject mergeSettings(JSONObject base, JSONObject patch) {
        if (base == null) return patch;
        java.util.Iterator<String> it = patch.keys();
        while (it.hasNext()) {
            String k = it.next();
            try { base.put(k, patch.get(k)); } catch (JSONException ignored) {}
        }
        return base;
    }

    // ---------------- 最近打开的书（启动直达续读） ----------------

    public static void setLastBook(Context ctx, String bookId) {
        sp(ctx).edit().putString(KEY_LAST, bookId).apply();
    }

    public static String getLastBook(Context ctx) {
        return sp(ctx).getString(KEY_LAST, null);
    }

    public static void clearLastBook(Context ctx) {
        sp(ctx).edit().remove(KEY_LAST).apply();
    }

    // ---------------- 导出 / 导入（防丢镜像） ----------------

    /** 是否有任何本地进度（含最近打开标记） */
    public static boolean hasAnyData(Context ctx) {
        Map<String, ?> all = sp(ctx).getAll();
        return !all.isEmpty();
    }

    /** 全量导出为 JSON：{lastBook, settings, books:{id:progressJson}} */
    public static JSONObject exportAll(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            Map<String, ?> all = sp(ctx).getAll();
            JSONObject books = new JSONObject();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String k = e.getKey();
                Object v = e.getValue();
                if (!(v instanceof String)) continue;
                if (k.startsWith(KEY_PREFIX)) {
                    books.put(k.substring(KEY_PREFIX.length()), (String) v);
                } else if (k.equals(KEY_SETTINGS) && v != null) {
                    out.put("settings", new JSONObject((String) v));
                } else if (k.equals(KEY_LAST) && v != null) {
                    out.put("lastBook", (String) v);
                }
            }
            out.put("books", books);
        } catch (JSONException ignored) {}
        return out;
    }

    /** 用导出的 JSON 覆盖式导入（仅当本地无数据时调用） */
    public static int importAll(Context ctx, JSONObject data) {
        int n = 0;
        try {
            JSONObject books = data.optJSONObject("books");
            if (books != null) {
                SharedPreferences.Editor ed = sp(ctx).edit();
                java.util.Iterator<String> it = books.keys();
                while (it.hasNext()) {
                    String id = it.next();
                    Object p = books.opt(id);
                    if (p instanceof String) {
                        ed.putString(KEY_PREFIX + id, (String) p);
                        n++;
                    }
                }
                ed.apply();
            }
            if (data.has("lastBook") && !data.isNull("lastBook")) {
                sp(ctx).edit().putString(KEY_LAST, data.optString("lastBook")).apply();
            }
            if (data.has("settings") && !data.isNull("settings")) {
                saveSettings(ctx, data.optJSONObject("settings"));
            }
        } catch (Exception ignored) {}
        return n;
    }
}
