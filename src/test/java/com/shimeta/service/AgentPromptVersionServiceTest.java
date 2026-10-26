package com.shimeta.service;

import com.shimeta.model.Agent;
import com.shimeta.model.AgentPromptVersion;
import com.shimeta.model.UserRole;
import com.shimeta.repository.AgentPromptVersionRepository;
import com.shimeta.repository.AgentRepository;
import com.shimeta.security.CurrentActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentPromptVersionServiceTest {

    @Mock AgentPromptVersionRepository versionRepository;
    @Mock AgentRepository agentRepository;
    @Mock ResourceAuthorizationService authorizationService;

    private final CurrentActor dev = new CurrentActor("u-dev", "dev", UserRole.DEVELOPER);
    private final List<AgentPromptVersion> stored = new ArrayList<>();
    private AgentPromptVersionService service;

    @BeforeEach
    void setUp() {
        service = new AgentPromptVersionService(versionRepository, agentRepository, authorizationService);
        // 用内存列表模拟版本表
        when(versionRepository.save(any())).thenAnswer(inv -> {
            AgentPromptVersion v = inv.getArgument(0);
            v.setId("apv-" + (stored.size() + 1));
            stored.add(v);
            return v;
        });
        when(versionRepository.findByAgentIdOrderByVersionNoDesc(anyString())).thenAnswer(inv -> stored.stream()
                .filter(v -> v.getAgentId().equals(inv.getArgument(0)))
                .sorted(Comparator.comparing(AgentPromptVersion::getVersionNo).reversed()).toList());
        when(versionRepository.findFirstByAgentIdOrderByVersionNoDesc(anyString())).thenAnswer(inv -> stored.stream()
                .filter(v -> v.getAgentId().equals(inv.getArgument(0)))
                .max(Comparator.comparing(AgentPromptVersion::getVersionNo)));
        org.mockito.Mockito.doAnswer(inv -> {
            stored.removeAll(inv.getArgument(0));
            return null;
        }).when(versionRepository).deleteAll(any());
    }

    private Agent agent(String prompt) {
        Agent a = new Agent();
        a.setId("agent-1");
        a.setName("售后客服");
        a.setSystemPrompt(prompt);
        when(agentRepository.findById("agent-1")).thenReturn(Optional.of(a));
        when(authorizationService.canViewAgent(dev, a)).thenReturn(true);
        return a;
    }

    @Test
    void firstChangeOfLegacyAgentBackfillsInitialVersion() {
        service.recordChange("agent-1", "旧提示词", "新提示词", AgentPromptVersionService.SOURCE_PUBLISH, "补充退货规则", dev);

        assertEquals(2, stored.size());
        assertEquals(1, stored.get(0).getVersionNo());
        assertEquals("旧提示词", stored.get(0).getSystemPrompt());
        assertEquals(AgentPromptVersionService.SOURCE_INITIAL, stored.get(0).getSource());
        assertNull(stored.get(0).getCreatedBy());
        assertEquals(2, stored.get(1).getVersionNo());
        assertEquals("新提示词", stored.get(1).getSystemPrompt());
        assertEquals("补充退货规则", stored.get(1).getNote());
        assertEquals("u-dev", stored.get(1).getCreatedBy());
    }

    @Test
    void unchangedPromptIsNotRecordedAndVersionsIncrement() {
        service.recordCreated(agentWithId("v1"), AgentPromptVersionService.SOURCE_CREATE, null, dev);
        service.recordChange("agent-1", "v1", "v1\n", AgentPromptVersionService.SOURCE_EDIT, null, dev);
        assertEquals(1, stored.size());

        service.recordChange("agent-1", "v1", "v2", AgentPromptVersionService.SOURCE_EDIT, null, dev);
        service.recordChange("agent-1", "v2", "v1", AgentPromptVersionService.SOURCE_ROLLBACK, "回滚到 v1", dev);
        assertEquals(List.of(1, 2, 3), stored.stream().map(AgentPromptVersion::getVersionNo).toList());
        assertEquals(AgentPromptVersionService.SOURCE_ROLLBACK, stored.get(2).getSource());
    }

    @Test
    void keepsOnlyLatestVersions() {
        service.recordCreated(agentWithId("p0"), AgentPromptVersionService.SOURCE_CREATE, null, dev);
        for (int i = 1; i <= AgentPromptVersionService.MAX_VERSIONS + 5; i++) {
            service.recordChange("agent-1", "p" + (i - 1), "p" + i, AgentPromptVersionService.SOURCE_EDIT, null, dev);
        }
        assertEquals(AgentPromptVersionService.MAX_VERSIONS, stored.size());
        assertEquals(AgentPromptVersionService.MAX_VERSIONS + 6,
                stored.stream().mapToInt(AgentPromptVersion::getVersionNo).max().orElseThrow());
    }

    @Test
    void listMarksVersionMatchingOnlinePromptAndOmitsFullText() {
        Agent a = agent("v1");
        service.recordCreated(a, AgentPromptVersionService.SOURCE_CREATE, null, dev);
        service.recordChange("agent-1", "v1", "v2", AgentPromptVersionService.SOURCE_PUBLISH, null, dev);

        List<Map<String, Object>> list = service.list("agent-1", dev);
        assertEquals(2, list.get(0).get("versionNo"));
        assertEquals(false, list.get(0).get("current"));   // 线上仍是 v1（例如被直接改回）
        assertEquals(true, list.get(1).get("current"));
        assertEquals("调试台发布", list.get(0).get("sourceLabel"));
        assertFalse(list.get(0).containsKey("systemPrompt"));
    }

    @Test
    void viewPermissionIsRequired() {
        Agent a = agent("p");
        when(authorizationService.canViewAgent(dev, a)).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> service.list("agent-1", dev));
        assertThrows(IllegalStateException.class, () -> service.get("agent-1", "apv-1", dev));
    }

    @Test
    void clientCannotClaimServerOnlySources() {
        assertEquals(AgentPromptVersionService.SOURCE_PUBLISH, AgentPromptVersionService.clientSource("PUBLISH"));
        assertEquals(AgentPromptVersionService.SOURCE_ROLLBACK, AgentPromptVersionService.clientSource("ROLLBACK"));
        assertEquals(AgentPromptVersionService.SOURCE_EDIT, AgentPromptVersionService.clientSource("ASSISTANT"));
        assertEquals(AgentPromptVersionService.SOURCE_EDIT, AgentPromptVersionService.clientSource(null));
        assertTrue(AgentPromptVersionService.trimNote("x".repeat(300)).length() <= AgentPromptVersionService.NOTE_MAX);
    }

    private Agent agentWithId(String prompt) {
        Agent a = new Agent();
        a.setId("agent-1");
        a.setSystemPrompt(prompt);
        return a;
    }
}
