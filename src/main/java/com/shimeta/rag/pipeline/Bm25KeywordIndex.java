package com.shimeta.rag.pipeline;

import com.shimeta.repository.KnowledgeDocumentChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库关键词检索：每个知识库在内存里建一份 BM25 倒排索引（中文按 jieba 分词）。
 * <p>
 * 不改数据库，不依赖 PostgreSQL 扩展。每次检索先查一下切片概况（数量、总长度、首尾 ID），
 * 有变化就重建，所以上传、删除、启停文档后不需要单独通知。单个知识库几千个切片时重建在百毫秒级。
 */
@Component
public class Bm25KeywordIndex {

    private static final Logger log = LoggerFactory.getLogger(Bm25KeywordIndex.class);

    static final double K1 = 1.2;
    static final double B = 0.75;

    private final KnowledgeDocumentChunkRepository chunkRepository;
    private final boolean enabled;
    private final double minRelativeScore;
    private final Map<String, Index> indexes = new ConcurrentHashMap<>();

    @Autowired
    public Bm25KeywordIndex(KnowledgeDocumentChunkRepository chunkRepository,
                            @Value("${app.rag.keyword.bm25-enabled:true}") boolean enabled,
                            @Value("${app.rag.keyword.bm25-fusion-min-score:0.6}") double minRelativeScore) {
        this.chunkRepository = chunkRepository;
        this.enabled = enabled;
        this.minRelativeScore = Math.max(0, Math.min(1, minRelativeScore));
    }

    public Bm25KeywordIndex(KnowledgeDocumentChunkRepository chunkRepository, boolean enabled) {
        this(chunkRepository, enabled, 0.6);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 混合检索时关键词结果只保留分数不低于最高分这个比例的切片。只命中问题里个别常见词的切片分数低，
     * 它们往往也在向量结果里，融合后会把只在关键词结果里排第一的切片挤下去（评测里「垫片追加」从第 1 掉到第 6）。
     * 0.6 是在 87 道评测题上比较 0.3 / 0.6 / 0.8 后选的。只看关键词时不过滤。
     */
    public double fusionMinScore() {
        return minRelativeScore;
    }

    /** 启动后在后台加载分词词典（约 70MB 内存、几秒），避免第一个提问等词典加载 */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!enabled) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                ChineseTokenizer.tokenize("知识库分词预热");
            } catch (Throwable e) {
                log.warn("BM25 tokenizer warm-up failed: {}", e.getMessage());
            }
        }, "bm25-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    /** 命中的切片 ID 和分数（最高分归一为 1），按分数从高到低 */
    public record Hit(String id, double score) {
    }

    public List<Hit> search(String knowledgeBaseId, String query, int limit) {
        if (!enabled || knowledgeBaseId == null || query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        Index index = currentIndex(knowledgeBaseId);
        if (index == null) {
            return List.of();
        }
        return index.search(ChineseTokenizer.tokenize(query), limit);
    }

    private Index currentIndex(String kbId) {
        KnowledgeDocumentChunkRepository.ChunkFingerprint fp = chunkRepository.fingerprintByKnowledgeBaseId(kbId);
        String fingerprint = fp == null ? "0" : fp.getCount() + "|" + fp.getTotalLength() + "|" + fp.getMinId() + "|" + fp.getMaxId();
        if (fp == null || fp.getCount() == null || fp.getCount() == 0) {
            indexes.remove(kbId);
            return null;
        }
        Index existing = indexes.get(kbId);
        if (existing != null && existing.fingerprint.equals(fingerprint)) {
            return existing;
        }
        synchronized (this) {
            existing = indexes.get(kbId);
            if (existing != null && existing.fingerprint.equals(fingerprint)) {
                return existing;
            }
            long started = System.currentTimeMillis();
            List<KnowledgeDocumentChunkRepository.ChunkText> chunks = chunkRepository.findTextByKnowledgeBaseId(kbId);
            Index built = Index.build(fingerprint, chunks);
            indexes.put(kbId, built);
            log.info("BM25 index built for knowledge base {}: {} chunks, {} terms, {} ms",
                    kbId, built.ids.size(), built.postings.size(), System.currentTimeMillis() - started);
            return built;
        }
    }

    /** 一个知识库的倒排索引，建好后只读 */
    static final class Index {
        final String fingerprint;
        final List<String> ids = new ArrayList<>();
        final int[] docLengths;
        final double avgDocLength;
        final Map<String, int[][]> postings;

        private Index(String fingerprint, List<String> ids, int[] docLengths, Map<String, int[][]> postings) {
            this.fingerprint = fingerprint;
            this.ids.addAll(ids);
            this.docLengths = docLengths;
            this.postings = postings;
            long total = 0;
            for (int len : docLengths) {
                total += len;
            }
            this.avgDocLength = docLengths.length == 0 ? 0 : (double) total / docLengths.length;
        }

        static Index build(String fingerprint, List<? extends KnowledgeDocumentChunkRepository.ChunkText> chunks) {
            List<String> ids = new ArrayList<>(chunks.size());
            int[] lengths = new int[chunks.size()];
            Map<String, List<int[]>> raw = new HashMap<>();
            for (int doc = 0; doc < chunks.size(); doc++) {
                KnowledgeDocumentChunkRepository.ChunkText chunk = chunks.get(doc);
                ids.add(chunk.getId());
                List<String> tokens = ChineseTokenizer.tokenize(DocumentChunker.indexText(chunk.getContextTitle(), chunk.getContent()));
                lengths[doc] = tokens.size();
                Map<String, Integer> tf = new HashMap<>();
                for (String token : tokens) {
                    tf.merge(token, 1, Integer::sum);
                }
                for (Map.Entry<String, Integer> e : tf.entrySet()) {
                    raw.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(new int[]{doc, e.getValue()});
                }
            }
            Map<String, int[][]> postings = new HashMap<>(raw.size() * 2);
            raw.forEach((term, list) -> postings.put(term, list.toArray(new int[0][])));
            return new Index(fingerprint, ids, lengths, postings);
        }

        List<Hit> search(List<String> queryTokens, int limit) {
            int n = ids.size();
            if (n == 0 || queryTokens.isEmpty()) {
                return List.of();
            }
            double[] scores = new double[n];
            boolean any = false;
            for (String term : new LinkedHashSet<>(queryTokens)) {
                int[][] list = postings.get(term);
                if (list == null) {
                    continue;
                }
                any = true;
                double idf = Math.log(1 + (n - list.length + 0.5) / (list.length + 0.5));
                for (int[] p : list) {
                    int doc = p[0];
                    double tf = p[1];
                    double norm = 1 - B + B * (avgDocLength > 0 ? docLengths[doc] / avgDocLength : 1);
                    scores[doc] += idf * (tf * (K1 + 1)) / (tf + K1 * norm);
                }
            }
            if (!any) {
                return List.of();
            }
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (scores[i] > 0) {
                    order.add(i);
                }
            }
            order.sort((a, b) -> {
                int c = Double.compare(scores[b], scores[a]);
                return c != 0 ? c : Objects.compare(ids.get(a), ids.get(b), String::compareTo);
            });
            double top = scores[order.get(0)];
            List<Hit> hits = new ArrayList<>(Math.min(limit, order.size()));
            for (int i = 0; i < order.size() && hits.size() < limit; i++) {
                int doc = order.get(i);
                hits.add(new Hit(ids.get(doc), scores[doc] / top));
            }
            return hits;
        }
    }
}
