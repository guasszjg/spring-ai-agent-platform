package com.example.agentplatform.rag.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * PostgreSQL 侧双路召回：pgvector 余弦距离（按维度 HNSW 索引）+ trigram/全文关键词。
 * 扩展不可用时返回空列表，由调用方回退到内存扫描。
 * 索引的创建与维护见 {@link VectorIndexManager}。
 */
@Service
public class PgChunkSearch {

    private static final Logger log = LoggerFactory.getLogger(PgChunkSearch.class);

    public record Hit(String id, double distanceOrRank) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final VectorIndexManager indexManager;
    private final TransactionTemplate readTx;
    private final Set<String> warnedDimMismatch = ConcurrentHashMap.newKeySet();

    public PgChunkSearch(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null, null);
    }

    @Autowired
    public PgChunkSearch(JdbcTemplate jdbcTemplate,
                         @Autowired(required = false) VectorIndexManager indexManager,
                         @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.indexManager = indexManager;
        if (transactionManager != null) {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            // 独立的短只读事务（REQUIRES_NEW）：
            // 1) SET LOCAL 需要事务，且只应作用于本次检索；
            // 2) 检索多在 @Transactional 方法中调用，若 SQL 失败（扩展缺失、维度异常等）在外层事务里执行，
            //    PostgreSQL 会把整个外层事务置为 aborted，后续的统计写入都会失败。隔离后失败只影响本次召回。
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.setReadOnly(true);
            this.readTx = tx;
        } else {
            this.readTx = null;
        }
    }

    /**
     * 构造向量检索 SQL。维度以字面量写入（int，无注入风险），原因：
     * <ul>
     *   <li>HNSW 是 {@code (embedding_vector::vector(N))} 上的表达式索引，ORDER BY 必须是同一表达式；</li>
     *   <li>索引带 {@code WHERE embedding_dim = N} 谓词，若用绑定参数，JDBC 切换到通用执行计划后规划器无法证明谓词成立，会放弃索引。</li>
     * </ul>
     */
    static String vectorSearchSql(int dim) {
        String col;
        String type;
        if (VectorIndexManager.isIndexable(dim)) {
            col = "(embedding_vector::vector(" + dim + "))";
            type = "vector(" + dim + ")";
        } else {
            col = "embedding_vector";
            type = "vector";
        }
        return "SELECT id, (" + col + " <=> CAST(? AS " + type + ")) AS dist"
                + " FROM knowledge_document_chunks"
                + " WHERE knowledge_base_id = ?"
                + " AND enabled = TRUE"
                + " AND embedding_vector IS NOT NULL"
                + " AND embedding_dim = " + dim
                + " ORDER BY " + col + " <=> CAST(? AS " + type + ")"
                + " LIMIT ?";
    }

    /** HNSW 默认 ef_search=40：按知识库过滤前只取 40 个近邻，召回数可能少于 limit，这里按需放大。 */
    static int efSearchFor(int limit) {
        return Math.max(40, Math.min(1000, limit * 4));
    }

    public List<Hit> vectorSearch(String knowledgeBaseId, float[] queryVector, int limit) {
        if (knowledgeBaseId == null || queryVector == null || queryVector.length == 0 || limit <= 0) {
            return List.of();
        }
        String literal = toVectorLiteral(queryVector);
        int dim = queryVector.length;
        if (indexManager != null) {
            indexManager.ensureIndexAsync(dim);
        }
        String sql = vectorSearchSql(dim);
        try {
            List<Hit> hits = runWithSearchSettings(limit, () -> jdbcTemplate.query(
                    sql,
                    (rs, rowNum) -> new Hit(rs.getString("id"), rs.getDouble("dist")),
                    literal, knowledgeBaseId, literal, limit
            ));
            if (hits.isEmpty()) {
                warnIfDimensionMismatch(knowledgeBaseId, dim);
            }
            return hits;
        } catch (DataAccessException e) {
            log.debug("pgvector 检索不可用，回退内存检索: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Hit> runWithSearchSettings(int limit, Supplier<List<Hit>> query) {
        return isolated(() -> {
            if (readTx != null) {
                jdbcTemplate.execute("SET LOCAL hnsw.ef_search = " + efSearchFor(limit));
                if (indexManager != null && indexManager.iterativeScanSupported()) {
                    jdbcTemplate.execute("SET LOCAL hnsw.iterative_scan = strict_order");
                }
            }
            return query.get();
        });
    }

    private <T> List<T> isolated(Supplier<List<T>> query) {
        if (readTx == null) {
            return query.get();
        }
        List<T> result = readTx.execute(status -> query.get());
        return result != null ? result : List.of();
    }

    /**
     * 知识库里有向量、但没有当前模型维度的向量 → 通常是切换了 Embedding 模型而未重建索引。
     * 以前这种情况会静默退化为纯关键词检索，这里显式告警（每个知识库+维度只告警一次）。
     */
    private void warnIfDimensionMismatch(String knowledgeBaseId, int dim) {
        if (!warnedDimMismatch.add(knowledgeBaseId + ":" + dim)) {
            return;
        }
        try {
            List<String> others = isolated(() -> jdbcTemplate.query(
                    "SELECT embedding_dim || ' 维 × ' || count(*) FROM knowledge_document_chunks"
                            + " WHERE knowledge_base_id = ? AND enabled = TRUE AND embedding_dim IS NOT NULL"
                            + " AND embedding_dim <> ? GROUP BY embedding_dim",
                    (rs, n) -> rs.getString(1), knowledgeBaseId, dim));
            if (!others.isEmpty()) {
                log.warn("知识库 {} 的分块向量维度 {} 与当前 Embedding 模型维度 {} 不一致，向量召回为空，"
                        + "当前仅靠关键词检索。请重新上传/重建该知识库的索引。", knowledgeBaseId, others, dim);
            }
        } catch (DataAccessException e) {
            log.debug("维度一致性检查失败: {}", e.getMessage());
        }
    }

    public List<Hit> keywordSearch(String knowledgeBaseId, String query, int limit) {
        if (knowledgeBaseId == null || query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        String q = query.trim();
        try {
            return isolated(() -> jdbcTemplate.query(
                    """
                            SELECT id,
                                   GREATEST(
                                       CASE WHEN position(lower(?) in lower(coalesce(content, ''))) > 0 THEN 1.0 ELSE 0 END,
                                       COALESCE(similarity(left(coalesce(content, ''), 4096), ?), 0)
                                   ) AS kw
                            FROM knowledge_document_chunks
                            WHERE knowledge_base_id = ?
                              AND enabled = TRUE
                              AND (
                                    content ILIKE ('%' || ? || '%')
                                    OR content % ?
                                    OR (content_tsv IS NOT NULL AND content_tsv @@ plainto_tsquery('simple', ?))
                                  )
                            ORDER BY kw DESC
                            LIMIT ?
                            """,
                    (rs, rowNum) -> new Hit(rs.getString("id"), rs.getDouble("kw")),
                    q, q, knowledgeBaseId, q, q, q, limit
            ));
        } catch (DataAccessException e) {
            log.debug("关键词 SQL 检索不可用，回退内存: {}", e.getMessage());
            return List.of();
        }
    }

    public static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.US, "%.8f", vector[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    public static List<String> ids(List<Hit> hits) {
        List<String> ids = new ArrayList<>(hits.size());
        for (Hit hit : hits) {
            ids.add(hit.id());
        }
        return ids;
    }
}
