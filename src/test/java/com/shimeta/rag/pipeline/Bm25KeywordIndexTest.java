package com.shimeta.rag.pipeline;

import com.shimeta.repository.KnowledgeDocumentChunkRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Bm25KeywordIndexTest {

    private record Chunk(String getId, String getContent, String getContextTitle) implements KnowledgeDocumentChunkRepository.ChunkText {
        Chunk(String getId, String getContent) {
            this(getId, getContent, null);
        }
    }

    private record Fp(Long getCount, Long getTotalLength, String getMinId, String getMaxId)
            implements KnowledgeDocumentChunkRepository.ChunkFingerprint {
    }

    private static final List<Chunk> CHUNKS = List.of(
            new Chunk("c1", "音量设置：音量值设置范围为 0-100 之间，默认为 20"),
            new Chunk("c2", "亮度设置：亮度值设置范围为 0-100 之间，默认为 20"),
            new Chunk("c3", "播放模式：追加发布、置顶发布、替换发布。垫片追加：根据特定的播放需求设定播放周期进行播放"),
            new Chunk("c4", "在终端应用界面中打开文件管理器，进入外置存储设备找到文件 ShiMeta_DS_Basic_V7.0.0.apk，直接点击进行安装即可"),
            new Chunk("c5", "U 盘导入节目时，必须在 U 盘中创建名为 resource 的文件夹"));

    @Test
    @DisplayName("分词：中文按词切，文件名整体保留并拆出各段，去掉疑问词")
    void tokenize() {
        List<String> tokens = ChineseTokenizer.tokenize("ShiMeta_DS_Basic_V7.0.0.apk 怎么装到设备上");
        assertThat(tokens).contains("shimeta_ds_basic_v7.0.0.apk", "shimeta", "basic", "apk", "设备")
                .doesNotContain("怎么", "u");
        assertThat(ChineseTokenizer.tokenize("U 盘")).doesNotContain("u");
    }

    private static Bm25KeywordIndex index(KnowledgeDocumentChunkRepository repo) {
        return new Bm25KeywordIndex(repo, true);
    }

    private static KnowledgeDocumentChunkRepository repo(List<Chunk> chunks, Fp fp) {
        KnowledgeDocumentChunkRepository repo = mock(KnowledgeDocumentChunkRepository.class);
        when(repo.fingerprintByKnowledgeBaseId(anyString())).thenReturn(fp);
        when(repo.findTextByKnowledgeBaseId(anyString())).thenReturn(List.copyOf(chunks));
        return repo;
    }

    @Test
    @DisplayName("相似段落里靠区分词排序：问亮度时亮度段落排第一")
    void distinguishesSimilarChunks() {
        Bm25KeywordIndex index = index(repo(CHUNKS, new Fp(5L, 100L, "c1", "c5")));
        List<Bm25KeywordIndex.Hit> hits = index.search("kb", "屏幕亮度默认是多少", 10);
        assertThat(hits.get(0).id()).isEqualTo("c2");
        assertThat(hits.get(0).score()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("短术语和文件名能直接命中")
    void termAndFileName() {
        Bm25KeywordIndex index = index(repo(CHUNKS, new Fp(5L, 100L, "c1", "c5")));
        assertThat(index.search("kb", "垫片追加", 10).get(0).id()).isEqualTo("c3");
        assertThat(index.search("kb", "ShiMeta_DS_Basic_V7.0.0.apk 怎么装", 10).get(0).id()).isEqualTo("c4");
    }

    @Test
    @DisplayName("问题里的词资料中都没有：返回空，交给原来的数据库匹配兜底")
    void noMatch() {
        Bm25KeywordIndex index = index(repo(CHUNKS, new Fp(5L, 100L, "c1", "c5")));
        assertThat(index.search("kb", "天气预报", 10)).isEmpty();
    }

    @Test
    @DisplayName("切片没变时复用索引，变了才重建；关闭开关时不检索")
    void rebuildOnlyWhenChanged() {
        KnowledgeDocumentChunkRepository repo = repo(CHUNKS, new Fp(5L, 100L, "c1", "c5"));
        Bm25KeywordIndex index = index(repo);
        index.search("kb", "音量", 10);
        index.search("kb", "亮度", 10);
        verify(repo, times(1)).findTextByKnowledgeBaseId("kb");

        when(repo.fingerprintByKnowledgeBaseId(anyString())).thenReturn(new Fp(4L, 80L, "c1", "c4"));
        index.search("kb", "音量", 10);
        verify(repo, times(2)).findTextByKnowledgeBaseId("kb");

        assertThat(new Bm25KeywordIndex(repo, false).search("kb", "音量", 10)).isEmpty();
        assertThat(index.fusionMinScore()).isEqualTo(0.6);
    }
}
