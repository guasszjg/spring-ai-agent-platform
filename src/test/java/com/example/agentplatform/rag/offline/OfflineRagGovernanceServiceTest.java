package com.example.agentplatform.rag.offline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class OfflineRagGovernanceServiceTest {

    @Test
    void testOfflineReadinessReport() {
        OfflineRagGovernanceService service = new OfflineRagGovernanceService();
        OfflineRagGovernanceService.OfflineReadinessReport report = service.getOfflineReadinessReport("kb_offline");

        assertNotNull(report);
        assertTrue(report.overallOfflineReady());
        assertEquals("AIR_GAPPED_PRIVATE_CLOUD", report.deploymentEnvironment());
        assertEquals(6, report.components().size());

        assertTrue(report.components().containsKey("vectorEngine"));
        assertTrue(report.components().containsKey("keywordEngine"));
        assertTrue(report.components().containsKey("embeddingEngine"));
        assertTrue(report.components().containsKey("parsingEngine"));
        assertTrue(report.components().containsKey("cacheEngine"));
        assertTrue(report.components().containsKey("graphEngine"));

        assertTrue(report.components().values().stream().allMatch(OfflineRagGovernanceService.ComponentStatus::healthy));
    }
}
