package com.shimeta.rag.pipeline;

import com.shimeta.rag.RetrievedChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 检索结果去重：同一份文件在一个知识库里传了两次，或者智能体关联的几个知识库里有相同文档时，
 * 同样的内容会被检索出多次，既占名额又让模型看到重复资料。按正文（忽略空白和大小写）去重，保留排在前面（分数高）的那条。
 */
public final class ChunkDedup {

    private ChunkDedup() {}

    /** 比较用的正文：去掉所有空白、统一小写 */
    public static String key(String content) {
        return content == null ? "" : content.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** 按正文去重，保留先出现的；调用前应已按分数从高到低排好 */
    public static List<RetrievedChunk> distinctByContent(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.size() < 2) {
            return chunks;
        }
        Set<String> seen = new HashSet<>();
        List<RetrievedChunk> out = new ArrayList<>(chunks.size());
        for (RetrievedChunk chunk : chunks) {
            String k = chunk == null ? "" : key(chunk.content());
            if (k.isEmpty() || seen.add(k)) {
                out.add(chunk);
            }
        }
        return out;
    }
}
