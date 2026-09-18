package com.example.agentplatform.rag.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PostgreSQL 侧双路召回：pgvector 余弦距离 + trigram/全文关键词。
 * 扩展不可用时返回空列表，由调用方回退到内存扫描。
 */
@Service
public class PgChunkSearch {

    private static final Logger log = LoggerFactory.getLogger(PgChunkSearch.class);

    public record Hit(String id, double distanceOrRank) {
    }

    private final JdbcTemplate jdbcTemplate;

    public PgChunkSearch(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Hit> vectorSearch(String knowledgeBaseId, float[] queryVector, int limit) {
        if (knowledgeBaseId == null || queryVector == null || queryVector.length == 0 || limit <= 0) {
            return List.of();
        }
        String literal = toVectorLiteral(queryVector);
        int dim = queryVector.length;
        try {
            return jdbcTemplate.query(
                    """
                            SELECT id, (embedding_vector <=> CAST(? AS vector)) AS dist
                            FROM knowledge_document_chunks
                            WHERE knowledge_base_id = ?
                              AND enabled = TRUE
                              AND embedding_vector IS NOT NULL
                              AND (embedding_dim IS NULL OR embedding_dim = ?)
                            ORDER BY embedding_vector <=> CAST(? AS vector)
                            LIMIT ?
                            """,
                    (rs, rowNum) -> new Hit(rs.getString("id"), rs.getDouble("dist")),
                    literal, knowledgeBaseId, dim, literal, limit
            );
        } catch (DataAccessException e) {
            log.debug("pgvector ANN 不可用，回退内存检索: {}", e.getMessage());
            return List.of();
        }
    }

    public List<Hit> keywordSearch(String knowledgeBaseId, String query, int limit) {
        if (knowledgeBaseId == null || query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        String q = query.trim();
        try {
            return jdbcTemplate.query(
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
            );
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
