package com.example.agentplatform.rag.pipeline;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * pgvector HNSW 索引管理（按维度建表达式部分索引）。
 *
 * <p>背景：{@code embedding_vector} 列类型为无维度的 {@code vector}，以便不同维度的 Embedding 模型共存。
 * pgvector 的 HNSW 要求列有固定维度，因此 V9 中直接建在列上的索引必然失败（被异常块静默跳过）。
 * 正确做法是对每个维度建一个表达式部分索引：
 * <pre>
 *   CREATE INDEX idx_kdc_hnsw_d1024 ON knowledge_document_chunks
 *     USING hnsw ((embedding_vector::vector(1024)) vector_cosine_ops)
 *     WHERE embedding_dim = 1024 AND embedding_vector IS NOT NULL;
 * </pre>
 * 查询端必须使用完全相同的表达式与谓词（见 {@link PgChunkSearch}），规划器才会走索引。
 *
 * <p>V12 迁移会为库中已有维度与 1024 建好索引；运行期遇到新维度（切换了 Embedding 模型）时，
 * 本类在后台线程以 {@code CREATE INDEX CONCURRENTLY} 补建，不阻塞检索与写入。
 */
@Component
public class VectorIndexManager {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexManager.class);

    /** pgvector 对 vector 类型 HNSW 索引的维度上限。 */
    public static final int HNSW_MAX_DIM = 2000;

    private final JdbcTemplate jdbcTemplate;
    private final Set<Integer> ready = ConcurrentHashMap.newKeySet();
    private final Set<Integer> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<Integer> warnedUnindexable = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hnsw-index-builder");
        t.setDaemon(true);
        return t;
    });
    private volatile Boolean iterativeScanSupported;

    public VectorIndexManager(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public static boolean isIndexable(int dim) {
        return dim > 0 && dim <= HNSW_MAX_DIM;
    }

    public static String indexName(int dim) {
        return "idx_kdc_hnsw_d" + dim;
    }

    /** 与 V12 迁移、{@link PgChunkSearch} 查询保持一致的建索引语句。dim 为 int，拼接无注入风险。 */
    public static String createIndexSql(int dim, boolean concurrently) {
        if (!isIndexable(dim)) {
            throw new IllegalArgumentException("HNSW 不支持维度 " + dim + "（上限 " + HNSW_MAX_DIM + "）");
        }
        return "CREATE INDEX " + (concurrently ? "CONCURRENTLY " : "") + "IF NOT EXISTS " + indexName(dim)
                + " ON knowledge_document_chunks USING hnsw ((embedding_vector::vector(" + dim + ")) vector_cosine_ops)"
                + " WHERE embedding_dim = " + dim + " AND embedding_vector IS NOT NULL";
    }

    /**
     * 确保某维度的 HNSW 索引存在；缺失时提交到后台线程补建，立即返回。
     * 超过 {@link #HNSW_MAX_DIM} 的维度无法建 HNSW，只记录一次告警（检索会退化为顺序扫描）。
     */
    public void ensureIndexAsync(int dim) {
        if (dim <= 0 || ready.contains(dim)) {
            return;
        }
        if (!isIndexable(dim)) {
            if (warnedUnindexable.add(dim)) {
                log.warn("Embedding 维度 {} 超过 pgvector HNSW 上限 {}，该维度向量检索将使用顺序扫描。"
                        + "建议换用 ≤{} 维的模型，或改用 halfvec 存储。", dim, HNSW_MAX_DIM, HNSW_MAX_DIM);
            }
            return;
        }
        if (!inFlight.add(dim)) {
            return;
        }
        executor.submit(() -> {
            try {
                buildIndex(dim);
            } catch (Exception e) {
                log.warn("HNSW 索引 {} 创建失败（向量检索将退化为顺序扫描）: {}", indexName(dim), e.getMessage());
            } finally {
                inFlight.remove(dim);
            }
        });
    }

    void buildIndex(int dim) {
        Boolean valid = indexValid(dim);
        if (Boolean.TRUE.equals(valid)) {
            ready.add(dim);
            return;
        }
        if (Boolean.FALSE.equals(valid)) {
            // 之前的 CONCURRENTLY 构建中断会留下 INVALID 索引，IF NOT EXISTS 会误以为已存在
            log.warn("发现无效的 HNSW 索引 {}，删除后重建", indexName(dim));
            jdbcTemplate.execute("DROP INDEX CONCURRENTLY IF EXISTS " + indexName(dim));
        }
        long start = System.currentTimeMillis();
        log.info("开始创建 HNSW 索引 {} (dim={})", indexName(dim), dim);
        // 在后台线程上执行，不处于任何 Spring 事务中，连接为 autocommit，满足 CONCURRENTLY 的要求。
        // 同一连接上临时调大 maintenance_work_mem，完成后 RESET，避免把会话参数带回连接池。
        jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
            try (Statement st = con.createStatement()) {
                st.execute("SET maintenance_work_mem = '256MB'");
                try {
                    st.execute(createIndexSql(dim, true));
                } finally {
                    st.execute("RESET maintenance_work_mem");
                }
            }
            return null;
        });
        ready.add(dim);
        log.info("HNSW 索引 {} 创建完成，耗时 {} ms", indexName(dim), System.currentTimeMillis() - start);
    }

    /** @return true=有效索引存在，false=存在但无效，null=不存在 */
    Boolean indexValid(int dim) {
        List<Boolean> rows = jdbcTemplate.query(
                "SELECT i.indisvalid FROM pg_class c JOIN pg_index i ON i.indexrelid = c.oid WHERE c.relname = ?",
                (rs, n) -> rs.getBoolean(1), indexName(dim));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** pgvector ≥ 0.8 支持 hnsw.iterative_scan，可避免"先取 ef_search 个近邻再按知识库过滤"导致的召回不足。 */
    public boolean iterativeScanSupported() {
        Boolean cached = iterativeScanSupported;
        if (cached != null) {
            return cached;
        }
        boolean supported = false;
        try {
            List<String> versions = jdbcTemplate.query(
                    "SELECT extversion FROM pg_extension WHERE extname = 'vector'", (rs, n) -> rs.getString(1));
            supported = !versions.isEmpty() && versionAtLeast(versions.get(0), 0, 8);
        } catch (DataAccessException e) {
            log.debug("读取 pgvector 版本失败: {}", e.getMessage());
        }
        iterativeScanSupported = supported;
        return supported;
    }

    static boolean versionAtLeast(String version, int major, int minor) {
        if (version == null) {
            return false;
        }
        String[] parts = version.split("\\.");
        try {
            int ma = Integer.parseInt(parts[0].replaceAll("\\D", ""));
            int mi = parts.length > 1 ? Integer.parseInt(parts[1].replaceAll("\\D", "")) : 0;
            return ma > major || (ma == major && mi >= minor);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 启动时为库中已存在的所有维度补建缺失的索引（例如 V12 之后才切换过模型）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            List<Integer> dims = jdbcTemplate.query(
                    "SELECT DISTINCT embedding_dim FROM knowledge_document_chunks "
                            + "WHERE embedding_dim IS NOT NULL AND embedding_vector IS NOT NULL",
                    (rs, n) -> rs.getInt(1));
            dims.forEach(this::ensureIndexAsync);
        } catch (DataAccessException e) {
            log.debug("跳过 HNSW 索引自检（pgvector 未安装或表不存在）: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
