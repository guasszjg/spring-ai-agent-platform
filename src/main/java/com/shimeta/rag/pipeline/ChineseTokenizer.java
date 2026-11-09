package com.shimeta.rag.pipeline;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BM25 用的分词：中文按 jieba 搜索模式切词（长词再切出子词），英文、数字、文件名整体保留并拆出各段。
 * <p>
 * 例：「ShiMeta_DS_Basic_V7.0.0.apk 怎么装到设备上」→ shimeta_ds_basic_v7.0.0.apk、shimeta、ds、basic、v7、apk、装到、设备 …
 * 词典第一次用到时加载（约 1–2 秒），之后复用。
 */
public final class ChineseTokenizer {

    /** 中文连续段 / 英文数字连续段（可带 . _ - 连接，如文件名、版本号） */
    private static final Pattern SEGMENT = Pattern.compile("[\\p{IsHan}]+|[a-z0-9]+(?:[._\\-][a-z0-9]+)*");
    private static final Pattern ASCII_PART = Pattern.compile("[a-z0-9]+");

    /** 疑问词、语气词、虚词：几乎每个问题都有，对找资料没有帮助 */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "吗", "呢", "吧", "啊", "呀", "么", "是", "在", "和", "与", "或", "及", "也", "都", "就", "还",
            "又", "要", "会", "能", "可", "可以", "能够", "怎么", "怎样", "怎么样", "如何", "什么", "哪些", "哪个", "哪里",
            "多少", "几", "为什么", "请问", "一下", "一个", "这个", "那个", "这", "那", "我", "你", "他", "它", "我们",
            "有", "没有", "不", "上", "里", "中", "后", "时", "时候", "之后", "进行", "对", "把", "被", "让", "给",
            "如果", "是否", "以及", "等", "个", "条", "种", "该", "其", "将", "从", "到", "为", "以", "于");

    private static final class Holder {
        static final JiebaSegmenter SEGMENTER = new JiebaSegmenter();
    }

    private ChineseTokenizer() {
    }

    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Matcher m = SEGMENT.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String seg = m.group();
            if (Character.UnicodeScript.of(seg.codePointAt(0)) == Character.UnicodeScript.HAN) {
                addHan(seg, out);
            } else {
                addAscii(seg, out);
            }
        }
        return out;
    }

    private static void addHan(String seg, List<String> out) {
        if (seg.length() == 1) {
            addIfUseful(seg, out);
            return;
        }
        for (SegToken token : Holder.SEGMENTER.process(seg, JiebaSegmenter.SegMode.SEARCH)) {
            addIfUseful(token.word, out);
        }
    }

    private static void addAscii(String seg, List<String> out) {
        // 单个英文字母（如「U 盘」的 u）太常见又没区分度；数字保留（端口、条数、版本）
        if (seg.length() > 1 || Character.isDigit(seg.charAt(0))) {
            out.add(seg);
        }
        if (seg.indexOf('.') >= 0 || seg.indexOf('_') >= 0 || seg.indexOf('-') >= 0) {
            Matcher parts = ASCII_PART.matcher(seg);
            while (parts.find()) {
                String part = parts.group();
                if (part.length() > 1 || Character.isDigit(part.charAt(0))) {
                    out.add(part);
                }
            }
        }
    }

    private static void addIfUseful(String word, List<String> out) {
        if (word == null) {
            return;
        }
        String w = word.trim();
        if (w.isEmpty() || STOP_WORDS.contains(w)) {
            return;
        }
        out.add(w);
    }
}
