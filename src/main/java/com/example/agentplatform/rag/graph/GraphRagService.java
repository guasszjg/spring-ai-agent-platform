package com.example.agentplatform.rag.graph;

import com.example.agentplatform.model.KnowledgeGraphTriplet;
import com.example.agentplatform.rag.RetrievedChunk;
import com.example.agentplatform.repository.KnowledgeGraphTripletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GraphRAG 实体关系与知识图谱关联检索服务 (试点)
 * 遵循《Spring-AI自研RAG双引擎设计.md》P4 阶段：实体关系与知识图谱关联检索试点
 *
 * 核心能力：
 * 1. 从切片文本中智能抽取主谓宾三元组 (Subject-Predicate-Object)
 * 2. 检索时基于 Query 关键实体进行多跳 (Multi-Hop) 图关联扩展
 * 3. 向前台提供拓扑图谱数据 (Nodes & Edges) 供知识图谱可视化
 */
@Service
public class GraphRagService {

    private static final Logger log = LoggerFactory.getLogger(GraphRagService.class);

    private final KnowledgeGraphTripletRepository tripletRepository;

    // 常用中文关系谓词正则提取模式
    private static final Pattern RELATION_PATTERN = Pattern.compile(
            "([\\u4e00-\\u9fa5a-zA-Z0-9_]{2,15})\\s*(包含|依赖|属于|调用|配置|基于|实现|部署在|服务于|继承自|关联|连接|负责|构成|分为|是|定义为)\\s*([\\u4e00-\\u9fa5a-zA-Z0-9_]{2,20})"
    );

    // 冒号属性模式: 例如 "认证机制: JWT令牌"
    private static final Pattern COLON_PATTERN = Pattern.compile(
            "([\\u4e00-\\u9fa5a-zA-Z0-9_]{2,15})[：:]\\s*([\\u4e00-\\u9fa5a-zA-Z0-9_]{2,20})"
    );

    @Autowired
    public GraphRagService(KnowledgeGraphTripletRepository tripletRepository) {
        this.tripletRepository = tripletRepository;
    }

    public record GraphNode(String id, String label, int degree, String category) {}
    public record GraphEdge(String source, String target, String relation, double weight) {}
    public record KnowledgeGraphData(List<GraphNode> nodes, List<GraphEdge> edges, int totalTriplets) {}

    /**
     * 从切片内容中抽取实体三元组并入库
     */
    @Transactional
    public List<KnowledgeGraphTriplet> extractAndSaveTriplets(String kbId, String chunkId, String text) {
        if (kbId == null || text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        List<KnowledgeGraphTriplet> triplets = new ArrayList<>();
        Set<String> deduplicateSet = new HashSet<>();

        // 1. 动词关系匹配
        Matcher m1 = RELATION_PATTERN.matcher(text);
        while (m1.find()) {
            String source = m1.group(1).trim();
            String relation = m1.group(2).trim();
            String target = m1.group(3).trim();
            if (isValidEntity(source) && isValidEntity(target) && !source.equalsIgnoreCase(target)) {
                String key = source + "->" + relation + "->" + target;
                if (deduplicateSet.add(key)) {
                    KnowledgeGraphTriplet t = new KnowledgeGraphTriplet();
                    t.setKnowledgeBaseId(kbId);
                    t.setSourceEntity(source);
                    t.setRelation(relation);
                    t.setTargetEntity(target);
                    t.setSourceChunkId(chunkId);
                    t.setWeight(1.0);
                    triplets.add(t);
                }
            }
        }

        // 2. 属性冒号匹配
        Matcher m2 = COLON_PATTERN.matcher(text);
        while (m2.find()) {
            String source = m2.group(1).trim();
            String target = m2.group(2).trim();
            if (isValidEntity(source) && isValidEntity(target) && !source.equalsIgnoreCase(target)) {
                String key = source + "->属性定义->" + target;
                if (deduplicateSet.add(key)) {
                    KnowledgeGraphTriplet t = new KnowledgeGraphTriplet();
                    t.setKnowledgeBaseId(kbId);
                    t.setSourceEntity(source);
                    t.setRelation("属性定义");
                    t.setTargetEntity(target);
                    t.setSourceChunkId(chunkId);
                    t.setWeight(0.85);
                    triplets.add(t);
                }
            }
        }

        if (!triplets.isEmpty()) {
            tripletRepository.saveAll(triplets);
            log.info("GraphRAG 从分段中抽取实体关系三元组完成: kbId={}, chunkId={}, count={}", kbId, chunkId, triplets.size());
        }

        return triplets;
    }

    /**
     * 基于 Query 检索关联的多跳实体关系
     */
    @Transactional(readOnly = true)
    public List<KnowledgeGraphTriplet> findRelatedTriplets(String kbId, String query) {
        if (kbId == null || query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        // 提取 Query 中的关键词并查找相关实体
        List<KnowledgeGraphTriplet> allKbTriplets = tripletRepository.findByKnowledgeBaseId(kbId);
        if (allKbTriplets.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> directMatchedEntities = new HashSet<>();
        String normalizedQuery = query.toLowerCase();

        for (KnowledgeGraphTriplet t : allKbTriplets) {
            if (normalizedQuery.contains(t.getSourceEntity().toLowerCase())) {
                directMatchedEntities.add(t.getSourceEntity());
            }
            if (normalizedQuery.contains(t.getTargetEntity().toLowerCase())) {
                directMatchedEntities.add(t.getTargetEntity());
            }
        }

        if (directMatchedEntities.isEmpty()) {
            return Collections.emptyList();
        }

        // 1跳与2跳图扩展
        Set<String> expandedEntities = new HashSet<>(directMatchedEntities);
        List<KnowledgeGraphTriplet> resultTriplets = new ArrayList<>();
        Set<String> addedKeys = new HashSet<>();

        // 1-hop
        for (KnowledgeGraphTriplet t : allKbTriplets) {
            if (directMatchedEntities.contains(t.getSourceEntity()) || directMatchedEntities.contains(t.getTargetEntity())) {
                String key = t.getSourceEntity() + "::" + t.getRelation() + "::" + t.getTargetEntity();
                if (addedKeys.add(key)) {
                    resultTriplets.add(t);
                    expandedEntities.add(t.getSourceEntity());
                    expandedEntities.add(t.getTargetEntity());
                }
            }
        }

        // 2-hop (最多扩展 8 条，避免图扩散引起上下文膨胀)
        for (KnowledgeGraphTriplet t : allKbTriplets) {
            if (resultTriplets.size() >= 12) break;
            if (expandedEntities.contains(t.getSourceEntity()) || expandedEntities.contains(t.getTargetEntity())) {
                String key = t.getSourceEntity() + "::" + t.getRelation() + "::" + t.getTargetEntity();
                if (addedKeys.add(key)) {
                    resultTriplets.add(t);
                }
            }
        }

        return resultTriplets;
    }

    /**
     * 生成图谱关联增强切片 (Synthetic Graph Chunk) 注入检索结果
     */
    public RetrievedChunk buildGraphAugmentedChunk(String kbId, String query, List<KnowledgeGraphTriplet> triplets) {
        if (triplets == null || triplets.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【知识图谱多跳拓扑推理】根据知识图谱实体网络分析，当前问题涉及以下关联关系：\n");
        for (KnowledgeGraphTriplet t : triplets) {
            sb.append("• 实体【").append(t.getSourceEntity()).append("】 —[")
              .append(t.getRelation()).append("]→ 实体【")
              .append(t.getTargetEntity()).append("】\n");
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("graphTripletCount", triplets.size());
        meta.put("graphTriplets", triplets.stream().map(t -> t.getSourceEntity() + " -" + t.getRelation() + "-> " + t.getTargetEntity()).toList());

        return RetrievedChunk.builder()
                .chunkId("graph_aug_" + kbId)
                .documentId("knowledge_graph")
                .sourceName("知识图谱实体关联网络 (GraphRAG)")
                .content(sb.toString())
                .rawContent(sb.toString())
                .score(0.92)
                .vectorScore(0.90)
                .keywordScore(0.95)
                .rerankScore(0.95)
                .matchType("GRAPH")
                .chunkType("GRAPH")
                .tokenCount(sb.length() / 2)
                .metadata(meta)
                .build();
    }

    /**
     * 获取指定知识库的完整图谱可视化数据
     */
    @Transactional(readOnly = true)
    public KnowledgeGraphData getKnowledgeGraph(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            return new KnowledgeGraphData(Collections.emptyList(), Collections.emptyList(), 0);
        }

        List<KnowledgeGraphTriplet> triplets = tripletRepository.findByKnowledgeBaseId(kbId);
        Map<String, Integer> degrees = new HashMap<>();
        List<GraphEdge> edges = new ArrayList<>();

        for (KnowledgeGraphTriplet t : triplets) {
            degrees.put(t.getSourceEntity(), degrees.getOrDefault(t.getSourceEntity(), 0) + 1);
            degrees.put(t.getTargetEntity(), degrees.getOrDefault(t.getTargetEntity(), 0) + 1);
            edges.add(new GraphEdge(t.getSourceEntity(), t.getTargetEntity(), t.getRelation(), t.getWeight() != null ? t.getWeight() : 1.0));
        }

        List<GraphNode> nodes = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : degrees.entrySet()) {
            String category = entry.getValue() > 3 ? "核心实体" : "关联实体";
            nodes.add(new GraphNode(entry.getKey(), entry.getKey(), entry.getValue(), category));
        }

        return new KnowledgeGraphData(nodes, edges, triplets.size());
    }

    /**
     * 清理知识库图谱三元组
     */
    @Transactional
    public void deleteByKnowledgeBaseId(String kbId) {
        tripletRepository.deleteByKnowledgeBaseId(kbId);
    }

    private boolean isValidEntity(String text) {
        if (text == null || text.trim().length() < 2 || text.trim().length() > 25) {
            return false;
        }
        String t = text.trim();
        // 排除过于宽泛或停用词
        return !t.matches("^(这个|那个|我们|你们|他们|文档|数据|内容|问题|答案|系统|平台)$");
    }
}
