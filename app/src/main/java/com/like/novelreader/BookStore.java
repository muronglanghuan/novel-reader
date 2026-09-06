package com.like.novelreader;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 书库：内置(assets/books) + 导入(filesDir/imports) 统一编目。
 * id 形如 "a:文件名" / "i:文件名"，同名不冲突；文件字节上限 80MB。
 */
public final class BookStore {

    public static final class Book {
        public final String id;
        public final String title;
        public final boolean builtin;
        Book(String id, String title, boolean builtin) {
            this.id = id; this.title = title; this.builtin = builtin;
        }
    }

    private static final int MAX_BYTES = 80 * 1024 * 1024;

    private BookStore() {}

    public static List<Book> list(Context ctx) {
        List<Book> out = new ArrayList<>();
        AssetManager am = ctx.getAssets();
        try {
            String[] names = am.list("books");
            if (names != null) {
                Arrays.sort(names);
                for (String n : names) {
                    if (n.toLowerCase().endsWith(".txt"))
                        out.add(new Book("a:" + n, displayTitle(n), true));
                }
            }
        } catch (IOException ignored) {}
        File dir = importsDir(ctx);
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase().endsWith(".txt"))
                    out.add(new Book("i:" + f.getName(), displayTitle(f.getName()), false));
            }
        }
        return out;
    }

    /** 读取书全文字节；不存在的 id 抛 IOException */
    public static byte[] readBook(Context ctx, String id) throws IOException {
        if (id.startsWith("a:")) {
            String name = id.substring(2);
            InputStream is = ctx.getAssets().open("books/" + name);
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[65536];
                int r;
                long total = 0;
                while ((r = is.read(buf)) > 0) {
                    total += r;
                    if (total > MAX_BYTES) throw new IOException("文件过大");
                    bos.write(buf, 0, r);
                }
                return bos.toByteArray();
            } finally {
                try { is.close(); } catch (IOException ignored) {}
            }
        } else if (id.startsWith("i:")) {
            File f = new File(importsDir(ctx), id.substring(2));
            if (!f.isFile()) throw new IOException("文件不存在");
            try (FileInputStream fis = new FileInputStream(f)) {
                long sz = f.length();
                if (sz > MAX_BYTES) throw new IOException("文件过大");
                byte[] data = new byte[(int) sz];
                int off = 0, r;
                while (off < data.length && (r = fis.read(data, off, data.length - off)) > 0) {
                    off += r;
                }
                return data;
            }
        }
        throw new IOException("未知书籍: " + id);
    }

    private static File importsDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "imports");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File importTarget(Context ctx, String fileName) {
        String name = fileName;
        if (!name.toLowerCase().endsWith(".txt")) name += ".txt";
        File d = importsDir(ctx);
        File f = new File(d, name);
        int k = 1;
        while (f.exists()) {
            f = new File(d, insertSuffix(name, k++));
        }
        return f;
    }

    private static String insertSuffix(String name, int k) {
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name) + "_" + k
                + (dot > 0 ? name.substring(dot) : "");
    }

    /** 由文件名提炼展示标题：去扩展名、清理站点水印痕迹 */
    public static String displayTitle(String fileName) {
        String t = fileName;
        int dot = t.lastIndexOf('.');
        if (dot > 0) t = t.substring(0, dot);
        // 去 {xxx} / 【xxx】 / _去广告 / _数字 等水印与后缀
        t = t.replaceAll("\\{.*?\\}", "").replaceAll("【.*?】", "");
        t = t.replaceAll("(?:_|－|-)?(副本|去广告|清理|转码|精校)+$", "");
        t = t.replaceAll("[_\\-]+\\d+$", "");
        int au = t.indexOf("作者");
        if (au > 0) {
            // 「书名 作者：xxx」取书名为题
            String pre = t.substring(0, au).trim();
            if (!pre.isEmpty()) t = pre;
        }
        return t.trim();
    }
}
