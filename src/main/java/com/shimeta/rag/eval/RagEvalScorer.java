package com.shimeta.rag.eval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 按检索结果的名次计算一道题的得分。一道题有若干「目标」（答案原文、来源文档或切片），
 * 同一目标命中多次只算一次，所以同一文档的多个切片不会把召回率算到 100% 以上。
 */
public final class RagEvalScorer {

    private RagEvalScorer() {
    }

    /** @param firstHitRank 第一个命中目标的名次（从 1 开始），没有命中时为 null */
    public record Score(double hitAt5, double recallAt10, double ndcgAt10, Integer firstHitRank, Set<String> matchedTargets) {}

    /**
     * @param matchedByRank 每个名次的切片命中了哪些目标（下标 0 是第 1 名）
     * @param targetCount   这道题的目标总数
     */
    public static Score score(List<Set<String>> matchedByRank, int targetCount) {
        Set<String> seen = new HashSet<>();
        Set<String> matchedInTop10 = new HashSet<>();
        Integer firstHitRank = null;
        boolean hitInTop5 = false;
        double dcg = 0;
        for (int i = 0; i < matchedByRank.size() && i < 10; i++) {
            int fresh = 0;
            for (String target : matchedByRank.get(i)) {
                if (seen.add(target)) {
                    fresh++;
                }
            }
            if (fresh > 0) {
                matchedInTop10.addAll(matchedByRank.get(i));
                dcg += fresh / log2(i + 2);
                if (firstHitRank == null) {
                    firstHitRank = i + 1;
                }
                hitInTop5 |= i < 5;
            }
        }
        double idcg = 0;
        for (int i = 0; i < Math.min(targetCount, 10); i++) {
            idcg += 1 / log2(i + 2);
        }
        // 一个切片同时命中多个目标时，得分可能略超理想值
        double ndcg = idcg > 0 ? Math.min(1.0, dcg / idcg) : 0;
        double recall = targetCount > 0 ? matchedInTop10.size() / (double) targetCount : 0;
        return new Score(hitInTop5 ? 1.0 : 0.0, recall, ndcg, firstHitRank, matchedInTop10);
    }

    /** 比较原文时忽略大小写和空白，切片里的换行和缩进不影响匹配 */
    public static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /** 拆成一行一段，去掉空行和重复 */
    public static List<String> cleanTexts(List<String> texts) {
        List<String> out = new ArrayList<>();
        if (texts == null) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (String raw : texts) {
            if (raw == null) {
                continue;
            }
            for (String line : raw.split("\\R")) {
                String text = line.trim();
                if (!text.isEmpty() && seen.add(normalize(text))) {
                    out.add(text);
                }
            }
        }
        return out;
    }

    private static double log2(int value) {
        return Math.log(value) / Math.log(2);
    }
}
