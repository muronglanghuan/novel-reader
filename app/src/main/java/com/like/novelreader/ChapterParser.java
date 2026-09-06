package com.like.novelreader;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 章节解析与编码探测。
 * 正文按行切段；章节标题形如「第1章 …」「第12章 …」（全角/汉字数字均可）。
 * 爬取文本常见问题：目录条目与正文内标题重复出现 → 产生空内容或把正文标题行吞进正文，
 * 这里做两级清理：编号重复且紧邻的标题并入一节；正文首行为同编号标题行时剔除。
 */
public final class ChapterParser {

    /** 一次解析结果 */
    public static final class Parsed {
        public final String text;
        public final int[] start;
        public final int[] end;
        public final String[] title;
        Parsed(String text, int[] start, int[] end, String[] title) {
            this.text = text; this.start = start; this.end = end; this.title = title;
        }
        public int count() { return title.length; }
        public String chapterText(int i) { return text.substring(start[i], end[i]); }
    }

    private ChapterParser() {}

    private static final Pattern HEAD =
            Pattern.compile("^[\\u3000\\t ]*第([0-9０-９〇零一二三四五六七八九十百千万两]+)[章节回卷部篇集][^\\n]*");

    /** 严格 UTF-8 解码，失败回退 GB18030；顺带清除全文 BOM 字符 */
    public static String decode(byte[] data) {
        try {
            CharBuffer cb = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data));
            return cb.toString().replace("﻿", "");
        } catch (CharacterCodingException e) {
            return new String(data, Charset.forName("GB18030")).replace("﻿", "");
        }
    }

    /** 一行（已去首尾空白）是否形如章节标题 */
    public static boolean looksLikeHeading(String line) {
        return HEAD.matcher(line.trim()).matches();
    }

    /**
     * 解析章节。返回条目数组；首个标题之前的非空文本作为第 0 章「开头」。
     * 条目按下标升序、互不重叠（start[i]…end[i]），正文不含标题行本身。
     */
    public static Parsed parse(String text) {
        String full = text;
        int len = full.length();
        String[] lines = full.split("\n", -1);
        int[] off = new int[lines.length];
        int cur = 0;
        for (int i = 0; i < lines.length; i++) { off[i] = cur; cur += lines[i].length() + 1; }

        // 标题行收集
        List<Integer> headOff = new ArrayList<>();
        List<String> headTxt = new ArrayList<>();
        List<Integer> headNum = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty()) continue;
            Matcher m = HEAD.matcher(t);
            if (m.matches()) {
                headOff.add(off[i]);
                headTxt.add(t.replaceFirst("^第", "第").replaceAll("[\\u3000\\t ]+$", "").trim());
                headNum.add(chineseToInt(m.group(1)));
            }
        }

        // 组装原始条目（含标题行本身）
        List<int[]> bounds = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < headOff.size(); i++) {
            int s = headOff.get(i);
            int e = (i + 1 < headOff.size()) ? headOff.get(i + 1) : len;
            // 剔除本条目开头紧接的、同编号的重复标题行（目录/正文双写）
            String headSeg = full.substring(s, Math.min(e, s + 80));
            int nlPos = headSeg.indexOf('\n');
            String firstLine = nlPos < 0 ? headSeg : headSeg.substring(0, nlPos);
            Matcher fm = HEAD.matcher(firstLine.trim());
            int skipTo = s;
            if (fm.matches() && chineseToInt(fm.group(1)) == headNum.get(i)) {
                skipTo = s + firstLine.length() + 1;   // 越过该重复标题行
            }
            bounds.add(new int[]{skipTo, e});
            titles.add(headTxt.get(i));
        }

        // 首个标题前的非空文本作为「开头」（以原始标题行偏移为准）
        boolean hasIntro = !headOff.isEmpty() && headOff.get(0) > 0
                && !full.substring(0, headOff.get(0)).trim().isEmpty();
        List<int[]> fb = new ArrayList<>();
        List<String> ft = new ArrayList<>();
        if (hasIntro) {
            fb.add(new int[]{0, bounds.get(0)[0]});
            ft.add("开头");
        }
        for (int i = 0; i < bounds.size(); i++) {
            fb.add(bounds.get(i));
            ft.add(titles.get(i));
        }
        // 完全无标题 → 全文一个「正文」
        if (ft.isEmpty()) { fb.add(new int[]{0, len}); ft.add("正文"); }

        // 剔除空内容条目（紧邻重复标题产生的零长条目；其内容并入相邻节）
        List<int[]> f2 = new ArrayList<>();
        List<String> t2 = new ArrayList<>();
        for (int i = 0; i < fb.size(); i++) {
            int[] b = fb.get(i);
            if (full.substring(b[0], b[1]).trim().isEmpty()) continue;
            f2.add(b);
            t2.add(ft.get(i));
        }
        // 若剔空后开头章内容为空(如文本本身以标题开头且无intro)时保持一致性
        int n = t2.size();
        int[] sA = new int[n], eA = new int[n];
        String[] tA = t2.toArray(new String[0]);
        for (int i = 0; i < n; i++) { sA[i] = f2.get(i)[0]; eA[i] = f2.get(i)[1]; }
        return new Parsed(full, sA, eA, tA);
    }

    /** 汉字/全角/阿拉伯混排数字 → int，失败 -1 */
    static int chineseToInt(String s) {
        if (s == null) return -1;
        s = s.trim().replace('０', '0').replace('１', '1').replace('２', '2').replace('３', '3')
             .replace('４', '4').replace('５', '5').replace('６', '6').replace('７', '7')
             .replace('８', '8').replace('９', '9')
             .replace('〇', '0').replace('零', '0').replace('两', '2');
        if (s.isEmpty()) return -1;
        if (s.matches("\\d+")) {
            try { return (int) Long.parseLong(s); } catch (NumberFormatException e) { return -1; }
        }
        java.util.Map<Character, Integer> digit = new java.util.HashMap<>();
        digit.put('一', 1); digit.put('二', 2); digit.put('三', 3); digit.put('四', 4);
        digit.put('五', 5); digit.put('六', 6); digit.put('七', 7); digit.put('八', 8);
        digit.put('九', 9); digit.put('十', 10); digit.put('百', 100); digit.put('千', 1000);
        int total = 0, cur = 0;
        for (char c : s.toCharArray()) {
            Integer v = digit.get(c);
            if (v == null) return -1;
            if (v == 10 || v == 100 || v == 1000) {
                if (cur == 0) cur = 1;
                total += cur * v;
                cur = 0;
            } else cur = v;
        }
        total += cur;
        return total <= 0 ? -1 : total;
    }
}
