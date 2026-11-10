package com.shimeta.rag.pipeline;

import com.shimeta.rag.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkDedupTest {

    @Test
    void keepsFirstOfSameContentIgnoringWhitespaceAndCase() {
        List<RetrievedChunk> chunks = List.of(
                new RetrievedChunk("APK 安装\n步骤一", "a.pdf", 0.9),
                new RetrievedChunk("其它内容", "a.pdf", 0.8),
                new RetrievedChunk("apk 安装 步骤一", "b.pdf", 0.7));
        assertThat(ChunkDedup.distinctByContent(chunks)).extracting(RetrievedChunk::sourceName).containsExactly("a.pdf", "a.pdf");
        assertThat(ChunkDedup.distinctByContent(chunks)).hasSize(2);
    }

    @Test
    void emptyContentIsNeverMerged() {
        List<RetrievedChunk> chunks = List.of(new RetrievedChunk("", "a", 0.9), new RetrievedChunk("", "b", 0.8));
        assertThat(ChunkDedup.distinctByContent(chunks)).hasSize(2);
    }
}
