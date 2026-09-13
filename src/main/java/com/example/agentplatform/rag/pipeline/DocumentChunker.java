package com.example.agentplatform.rag.pipeline;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 本地文档解析与智能切片器 (Document Splitter & Chunker)
 * 采用层级标点与换行分块算法，支持普通递归切片与高级父子分块 (Parent-Child Chunking)
 */
@Component
public class DocumentChunker {

    public static final int DEFAULT_CHUNK_SIZE = 500;
    public static final int DEFAULT_CHUNK_OVERLAP = 80;

    public static final int DEFAULT_PARENT_CHUNK_SIZE = 1200;
    public static final int DEFAULT_PARENT_CHUNK_OVERLAP = 150;
    public static final int DEFAULT_CHILD_CHUNK_SIZE = 350;
    public static final int DEFAULT_CHILD_CHUNK_OVERLAP = 60;

    private static final List<String> SEPARATORS = Arrays.asList(
            "\n\n",
            "\n",
            "。\n",
            "！\n",
            "？\n",
            "。",
            "！",
            "？",
            "；",
            ";",
            ". ",
            " ",
            ""
    );

    public record ChunkPiece(
            int index,
            String content,
            int charCount,
            long tokenCount
    ) {}

    public record ParentChildPiece(
            int index,
            String childContent,
            String parentChunkId,
            String parentContent,
            int charCount,
            long tokenCount,
            String chunkType
    ) {}

    /**
     * 将长文本切分为有序分段列表（单层分块）
     */
    public List<ChunkPiece> splitText(String text, int chunkSize, int chunkOverlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int targetSize = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;
        int overlap = (chunkOverlap >= 0 && chunkOverlap < targetSize) ? chunkOverlap : DEFAULT_CHUNK_OVERLAP;

        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        List<String> rawChunks = recursiveSplit(normalized, targetSize, overlap, 0);

        List<ChunkPiece> results = new ArrayList<>();
        int index = 0;
        for (String raw : rawChunks) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                long tokens = estimateTokens(trimmed);
                results.add(new ChunkPiece(index++, trimmed, trimmed.length(), tokens));
            }
        }
        return results;
    }

    public List<ChunkPiece> splitText(String text) {
        return splitText(text, DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
    }

    /**
     * 高级父子切片管线 (Parent-Child Chunking)
     * 父块 (1200~1500字符) 保持段落大上下文；子块 (300~400字符) 用于高精度向量召回
     */
    public List<ParentChildPiece> splitParentChild(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();

        // 短文本直接作为单一切片，无需二级切割
        if (normalized.length() <= DEFAULT_CHILD_CHUNK_SIZE + 100) {
            long tokens = estimateTokens(normalized);
            return List.of(new ParentChildPiece(
                    0,
                    normalized,
                    null,
                    normalized,
                    normalized.length(),
                    tokens,
                    "STANDALONE"
            ));
        }

        // 1. 先切割父块大段落
        List<String> parentRawList = recursiveSplit(normalized, DEFAULT_PARENT_CHUNK_SIZE, DEFAULT_PARENT_CHUNK_OVERLAP, 0);
        List<ParentChildPiece> pieces = new ArrayList<>();
        int globalIndex = 0;

        for (String parentRaw : parentRawList) {
            String parentContent = parentRaw.trim();
            if (parentContent.isEmpty()) continue;

            String parentChunkId = "parent_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

            // 2. 将父块进一步细切为子块
            List<String> childRawList = recursiveSplit(parentContent, DEFAULT_CHILD_CHUNK_SIZE, DEFAULT_CHILD_CHUNK_OVERLAP, 0);
            for (String childRaw : childRawList) {
                String childContent = childRaw.trim();
                if (childContent.isEmpty()) continue;

                long tokens = estimateTokens(childContent);
                pieces.add(new ParentChildPiece(
                        globalIndex++,
                        childContent,
                        parentChunkId,
                        parentContent,
                        childContent.length(),
                        tokens,
                        "CHILD"
                ));
            }
        }

        return pieces;
    }

    private List<String> recursiveSplit(String text, int targetSize, int overlap, int sepIndex) {
        List<String> chunks = new ArrayList<>();
        if (text.length() <= targetSize) {
            chunks.add(text);
            return chunks;
        }

        if (sepIndex >= SEPARATORS.size() - 1) {
            // 兜底：按字符强制截断
            for (int i = 0; i < text.length(); i += (targetSize - overlap)) {
                int end = Math.min(text.length(), i + targetSize);
                chunks.add(text.substring(i, end));
                if (end == text.length()) break;
            }
            return chunks;
        }

        String separator = SEPARATORS.get(sepIndex);
        String[] parts = separator.isEmpty() ? new String[]{text} : text.split(java.util.regex.Pattern.quote(separator));

        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;

            if (current.length() + part.length() + separator.length() <= targetSize) {
                if (!current.isEmpty() && !separator.isEmpty()) {
                    current.append(separator);
                }
                current.append(part);
            } else {
                if (!current.isEmpty()) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }

                if (part.length() > targetSize) {
                    // 该段本身超长，深入下一级细粒度分隔符切割
                    chunks.addAll(recursiveSplit(part, targetSize, overlap, sepIndex + 1));
                } else {
                    current.append(part);
                }
            }
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }

        return chunks;
    }

    /**
     * 估算 Token 数量 (中文字符约 1.5 token，英文单词约 1.3 token)
     */
    public long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        int chineseCount = 0;
        int otherCount = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                chineseCount++;
            } else {
                otherCount++;
            }
        }
        return Math.max(1L, Math.round(chineseCount * 1.5 + (otherCount / 4.0) * 1.2));
    }
}
