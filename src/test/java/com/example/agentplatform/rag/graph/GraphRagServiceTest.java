package com.example.agentplatform.rag.graph;

import com.example.agentplatform.model.KnowledgeGraphTriplet;
import com.example.agentplatform.rag.RetrievedChunk;
import com.example.agentplatform.repository.KnowledgeGraphTripletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

public class GraphRagServiceTest {

    private KnowledgeGraphTripletRepository tripletRepository;
    private GraphRagService graphRagService;

    @BeforeEach
    void setUp() {
        tripletRepository = Mockito.mock(KnowledgeGraphTripletRepository.class);
        graphRagService = new GraphRagService(tripletRepository);
    }

    @Test
    void testExtractAndSaveTriplets() {
        String kbId = "kb_arch";
        String chunkId = "ch_001";
        String content = "网关服务包含鉴权中心。鉴权中心依赖Redis缓存。核心服务负责订单计算。";

        List<KnowledgeGraphTriplet> triplets = graphRagService.extractAndSaveTriplets(kbId, chunkId, content);

        assertNotNull(triplets);
        assertFalse(triplets.isEmpty());
        assertTrue(triplets.stream().anyMatch(t -> "网关服务".equals(t.getSourceEntity()) && "包含".equals(t.getRelation()) && "鉴权中心".equals(t.getTargetEntity())));
        assertTrue(triplets.stream().anyMatch(t -> "鉴权中心".equals(t.getSourceEntity()) && "依赖".equals(t.getRelation()) && "Redis缓存".equals(t.getTargetEntity())));

        verify(tripletRepository, times(1)).saveAll(anyList());
    }

    @Test
    void testFindRelatedTripletsMultiHop() {
        String kbId = "kb_arch";

        KnowledgeGraphTriplet t1 = new KnowledgeGraphTriplet();
        t1.setSourceEntity("用户中心");
        t1.setRelation("依赖");
        t1.setTargetEntity("MySQL集群");

        KnowledgeGraphTriplet t2 = new KnowledgeGraphTriplet();
        t2.setSourceEntity("MySQL集群");
        t2.setRelation("部署在");
        t2.setTargetEntity("私有云服务器");

        when(tripletRepository.findByKnowledgeBaseId(eq(kbId))).thenReturn(List.of(t1, t2));

        List<KnowledgeGraphTriplet> related = graphRagService.findRelatedTriplets(kbId, "请问用户中心的存储架构是什么");

        assertNotNull(related);
        assertEquals(2, related.size());
        assertEquals("用户中心", related.get(0).getSourceEntity());
        assertEquals("MySQL集群", related.get(1).getSourceEntity());
    }

    @Test
    void testBuildGraphAugmentedChunk() {
        String kbId = "kb_arch";
        KnowledgeGraphTriplet t1 = new KnowledgeGraphTriplet();
        t1.setSourceEntity("认证服务");
        t1.setRelation("调用");
        t1.setTargetEntity("LDAP服务");

        RetrievedChunk chunk = graphRagService.buildGraphAugmentedChunk(kbId, "认证流程", List.of(t1));

        assertNotNull(chunk);
        assertEquals("GRAPH", chunk.matchType());
        assertEquals("GRAPH", chunk.chunkType());
        assertTrue(chunk.isGraph());
        assertTrue(chunk.content().contains("认证服务"));
        assertTrue(chunk.content().contains("LDAP服务"));
    }

    @Test
    void testGetKnowledgeGraph() {
        String kbId = "kb_arch";
        KnowledgeGraphTriplet t = new KnowledgeGraphTriplet();
        t.setSourceEntity("前端UI");
        t.setRelation("连接");
        t.setTargetEntity("网关API");

        when(tripletRepository.findByKnowledgeBaseId(eq(kbId))).thenReturn(List.of(t));

        GraphRagService.KnowledgeGraphData data = graphRagService.getKnowledgeGraph(kbId);

        assertNotNull(data);
        assertEquals(2, data.nodes().size());
        assertEquals(1, data.edges().size());
        assertEquals(1, data.totalTriplets());
    }
}
