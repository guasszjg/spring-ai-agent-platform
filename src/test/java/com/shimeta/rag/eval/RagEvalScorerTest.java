package com.shimeta.rag.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RagEvalScorerTest {

    @Test
    void repeatedTargetCountsOnce() {
        RagEvalScorer.Score score = RagEvalScorer.score(List.of(Set.of("a"), Set.of("a"), Set.of("a")), 1);

        assertThat(score.recallAt10()).isEqualTo(1.0);
        assertThat(score.ndcgAt10()).isEqualTo(1.0);
        assertThat(score.firstHitRank()).isEqualTo(1);
    }

    @Test
    void hitAt5OnlyLooksAtTopFive() {
        List<Set<String>> ranks = List.of(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of("a"));

        RagEvalScorer.Score score = RagEvalScorer.score(ranks, 1);

        assertThat(score.hitAt5()).isZero();
        assertThat(score.recallAt10()).isEqualTo(1.0);
        assertThat(score.firstHitRank()).isEqualTo(6);
        assertThat(score.ndcgAt10()).isBetween(0.3, 0.4);
    }

    @Test
    void rankBeyondTenIsIgnored() {
        java.util.ArrayList<Set<String>> ranks = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ranks.add(Set.of());
        }
        ranks.add(Set.of("a"));

        RagEvalScorer.Score score = RagEvalScorer.score(ranks, 1);

        assertThat(score.recallAt10()).isZero();
        assertThat(score.firstHitRank()).isNull();
    }

    @Test
    void partialRecallAndOneChunkWithTwoTargets() {
        assertThat(RagEvalScorer.score(List.of(Set.of("a")), 2).recallAt10()).isEqualTo(0.5);

        RagEvalScorer.Score both = RagEvalScorer.score(List.of(Set.of("a", "b")), 2);
        assertThat(both.recallAt10()).isEqualTo(1.0);
        assertThat(both.ndcgAt10()).isEqualTo(1.0);
    }

    @Test
    void emptyResults() {
        RagEvalScorer.Score score = RagEvalScorer.score(List.of(), 1);

        assertThat(score.hitAt5()).isZero();
        assertThat(score.recallAt10()).isZero();
        assertThat(score.ndcgAt10()).isZero();
    }

    @Test
    void normalizesAndCleansText() {
        assertThat(RagEvalScorer.normalize(" 退款 A\n到账 ")).isEqualTo("退款a到账");
        assertThat(RagEvalScorer.cleanTexts(List.of("第一段\n\n 第二段 ", "第 一段"))).containsExactly("第一段", "第二段");
    }
}
