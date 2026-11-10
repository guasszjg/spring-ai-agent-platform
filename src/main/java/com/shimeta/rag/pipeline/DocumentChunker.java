package com.shimeta.rag.pipeline;

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

    /** 上下文标题最多带几级章节（只取最深的几级） */
    static final int MAX_TITLE_LEVELS = 3;
    static final int MAX_TITLE_CHARS = 200;
    private static final java.util.regex.Pattern MD_HEADING = java.util.regex.Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final java.util.regex.Pattern HEADING_NUMBER = java.util.regex.Pattern.compile("^\\d{1,2}(?:\\.\\d{1,3}){0,5}\\s+");

    private record Heading(int offset, int level, String title) {}

    /**
     * 每个切片的上下文标题：「文档名 › 章 › 节」。按切片在原文里的位置，取它所在的 Markdown 标题路径
     * （PDF、Word 的章节标题在解析时已标成 Markdown 标题）。切片的切分方式不变，标题只用于检索。
     *
     * @param text          切片用的原文（和 {@link #splitParentChild} 收到的一样）
     * @param documentTitle 文档名（不含扩展名），为空时只用章节路径
     * @return 和 pieces 一一对应；没有任何标题信息时对应位置为 null
     */
    public List<String> contextTitles(String text, List<ParentChildPiece> pieces, String documentTitle) {
        List<String> titles = new ArrayList<>(pieces.size());
        if (text == null || text.isBlank()) {
            pieces.forEach(p -> titles.add(buildTitle(documentTitle, List.of())));
            return titles;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        List<Heading> headings = findHeadings(normalized);
        int cursor = 0;
        for (ParentChildPiece piece : pieces) {
            int offset = locate(normalized, piece.childContent(), cursor);
            if (offset >= 0) {
                cursor = offset;
            }
            titles.add(buildTitle(documentTitle, headingPath(headings, skipLeadingHeadings(normalized, offset >= 0 ? offset : cursor))));
        }
        return titles;
    }

    /** 切片以标题行开头时（如「# 第一章」「## 1.1 概述」接正文），按正文开始的位置算路径，标题都算进去 */
    private static int skipLeadingHeadings(String text, int offset) {
        int pos = offset;
        while (pos < text.length()) {
            int end = text.indexOf('\n', pos);
            String line = (end < 0 ? text.substring(pos) : text.substring(pos, end)).trim();
            if (!line.isEmpty() && !MD_HEADING.matcher(line).matches()) {
                return pos;
            }
            if (end < 0) {
                return text.length();
            }
            pos = end + 1;
        }
        return pos;
    }

    private static List<Heading> findHeadings(String text) {
        List<Heading> headings = new ArrayList<>();
        boolean inFence = false;
        int offset = 0;
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
            } else if (!inFence) {
                java.util.regex.Matcher m = MD_HEADING.matcher(trimmed);
                if (m.matches()) {
                    String title = HEADING_NUMBER.matcher(m.group(2).trim()).replaceFirst("").trim();
                    if (!title.isEmpty()) {
                        headings.add(new Heading(offset, m.group(1).length(), title));
                    }
                }
            }
            offset += line.length() + 1;
        }
        return headings;
    }

    /** 切片正文在原文里的位置：先从上一个切片之后找，找不到再从头找；用开头一段文字定位 */
    private static int locate(String text, String content, int from) {
        if (content == null || content.isBlank()) {
            return -1;
        }
        String probe = content.strip();
        probe = probe.substring(0, Math.min(40, probe.length()));
        int idx = text.indexOf(probe, Math.max(0, from));
        return idx >= 0 ? idx : text.indexOf(probe);
    }

    private static List<String> headingPath(List<Heading> headings, int offset) {
        List<Heading> stack = new ArrayList<>();
        for (Heading h : headings) {
            if (h.offset() > offset) {
                break;
            }
            while (!stack.isEmpty() && stack.get(stack.size() - 1).level() >= h.level()) {
                stack.remove(stack.size() - 1);
            }
            stack.add(h);
        }
        List<String> path = new ArrayList<>();
        for (int i = Math.max(0, stack.size() - MAX_TITLE_LEVELS); i < stack.size(); i++) {
            path.add(stack.get(i).title());
        }
        return path;
    }

    static String buildTitle(String documentTitle, List<String> path) {
        List<String> parts = new ArrayList<>();
        if (documentTitle != null && !documentTitle.isBlank()) {
            parts.add(documentTitle.trim());
        }
        parts.addAll(path);
        if (parts.isEmpty()) {
            return null;
        }
        String title = String.join(" › ", parts);
        return title.length() > MAX_TITLE_CHARS ? title.substring(0, MAX_TITLE_CHARS) : title;
    }

    /** 文件名去掉路径和扩展名，作为上下文标题里的文档名 */
    public static String documentTitle(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String name = fileName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 向量化和关键词检索用的文本：上下文标题 + 正文；回答时引用的仍是正文 */
    public static String indexText(String contextTitle, String content) {
        if (contextTitle == null || contextTitle.isBlank()) {
            return content;
        }
        return contextTitle + "\n" + (content == null ? "" : content);
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
        // 按模型返回的真实用量校准：中文正文约 0.7 Token/字（含标点和英文），汉字按 0.9 算时总量接近真实值。
        // 原来按 1.5 算，比真实值高约 1.6 倍，上下文预算会提前用完，排在后面的资料被丢掉
        return Math.max(1L, Math.round(chineseCount * 0.9 + (otherCount / 4.0) * 1.2));
    }
}
