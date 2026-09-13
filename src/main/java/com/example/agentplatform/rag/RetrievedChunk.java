package com.example.agentplatform.rag;

import java.util.Collections;
import java.util.Map;

/**
 * 检索切片证据对象（支持多引擎统一溯源、高亮、打分与元数据展示）
 */
public record RetrievedChunk(
        String chunkId,              // 切片唯一标识
        String documentId,           // 所属文档标识
        String parentChunkId,        // 父块标识（用于父子切分展开，可为空）
        String sourceName,           // 文档名/数据源名
        String sourceUrl,            // 受控下载地址（可为空）
        Integer pageNumber,          // 页码（可为空，1-indexed）
        Integer startOffset,         // 原文起始偏移（字符或token偏移，可为空）
        Integer endOffset,           // 原文结束偏移（可为空）
        String content,              // 最终注入上下文的内容
        String rawContent,           // 原始切片内容（用于命中高亮，可为空）
        Double score,                // 综合/最终评分（兼容字段，优先等于 fusedScore 或 vectorScore）
        Double vectorScore,          // 向量相似度分（可为空）
        Double keywordScore,         // 关键词/全文检索分（可为空）
        Double rerankScore,          // 重排打分（可为空）
        String matchType,            // 命中类型：VECTOR / KEYWORD / HYBRID / RERANK
        Integer tokenCount,          // 切片预估 token 数量
        Map<String, Object> metadata // 自定义扩展元数据（包含分段定位、标题等）
) {
    /**
     * 向后兼容构造器：仅提供 (content, sourceName, score)
     */
    public RetrievedChunk(String content, String sourceName, Double score) {
        this(null, null, null, sourceName, null, null, null, null,
                content, content, score, score, null, null, "VECTOR", null,
                Collections.emptyMap());
    }

    /**
     * 别名方法，兼容不同命名习惯（如 pageNo、fusedScore 等）
     */
    public Integer pageNo() {
        return pageNumber;
    }

    public Double fusedScore() {
        return score;
    }

    /**
     * 静态 Builder 便于逐步组装证据
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String chunkId;
        private String documentId;
        private String parentChunkId;
        private String sourceName;
        private String sourceUrl;
        private Integer pageNumber;
        private Integer startOffset;
        private Integer endOffset;
        private String content;
        private String rawContent;
        private Double score;
        private Double vectorScore;
        private Double keywordScore;
        private Double rerankScore;
        private String matchType;
        private Integer tokenCount;
        private Map<String, Object> metadata;

        public Builder chunkId(String chunkId) { this.chunkId = chunkId; return this; }
        public Builder documentId(String documentId) { this.documentId = documentId; return this; }
        public Builder parentChunkId(String parentChunkId) { this.parentChunkId = parentChunkId; return this; }
        public Builder sourceName(String sourceName) { this.sourceName = sourceName; return this; }
        public Builder sourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; return this; }
        public Builder pageNumber(Integer pageNumber) { this.pageNumber = pageNumber; return this; }
        public Builder pageNo(Integer pageNo) { this.pageNumber = pageNo; return this; }
        public Builder startOffset(Integer startOffset) { this.startOffset = startOffset; return this; }
        public Builder endOffset(Integer endOffset) { this.endOffset = endOffset; return this; }
        public Builder content(String content) { this.content = content; return this; }
        public Builder rawContent(String rawContent) { this.rawContent = rawContent; return this; }
        public Builder score(Double score) { this.score = score; return this; }
        public Builder vectorScore(Double vectorScore) { this.vectorScore = vectorScore; return this; }
        public Builder keywordScore(Double keywordScore) { this.keywordScore = keywordScore; return this; }
        public Builder rerankScore(Double rerankScore) { this.rerankScore = rerankScore; return this; }
        public Builder matchType(String matchType) { this.matchType = matchType; return this; }
        public Builder tokenCount(Integer tokenCount) { this.tokenCount = tokenCount; return this; }
        public Builder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }

        public RetrievedChunk build() {
            String finalRaw = rawContent != null ? rawContent : content;
            Double finalScore = score != null ? score : (vectorScore != null ? vectorScore : rerankScore);
            return new RetrievedChunk(
                    chunkId, documentId, parentChunkId, sourceName, sourceUrl,
                    pageNumber, startOffset, endOffset, content, finalRaw,
                    finalScore, vectorScore, keywordScore, rerankScore,
                    matchType != null ? matchType : "VECTOR",
                    tokenCount,
                    metadata != null ? metadata : Collections.emptyMap()
            );
        }
    }
}
