package com.example.agentplatform.rag.pipeline;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 本地文档解析与智能切片器 (Document Splitter & Chunker)
 * 采用层级标点与换行分块算法，保留语义完整性与边界
 */
@Component
public class DocumentChunker {

    public static final int DEFAULT_CHUNK_SIZE = 500;
    public static final int DEFAULT_CHUNK_OVERLAP = 80;

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

    /**
     * 将长文本切分为有序分段列表
     *
     * @param text         原始正文
     * @param chunkSize    单段目标字符数
     * @param chunkOverlap 段间重叠字符数
     * @return 分块结果
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
