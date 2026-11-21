package com.shimeta.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shimeta.model.UserRole;
import com.shimeta.security.CurrentActor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能力清单（capabilities.json）单测守门（4.1）：
 * 1. 菜单名与前端构建导出的 frontend-menus.json 一一对应；
 * 2. 每个 READ / WRITE 条目对应的工具在注册表中真实存在且读写属性一致；
 * 3. 每个带 docAnchor 的条目（含全部 DOC 条目）都能在内置文档的真实标题中找到；
 * 4. 动态生成的菜单总览按角色正确过滤。
 */
class AssistantCapabilitiesTest {

    private static final Set<String> VALID_HANDLING = Set.of("DOC", "READ", "WRITE", "GUIDE");
    private static final Set<String> VALID_ROLES = Set.of("SUPER_ADMIN", "DEVELOPER", "VIEWER");
    private static final Set<String> VALID_RISKS = Set.of("A", "B", "C", "D");
    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,4})\\s+(.+?)\\s*$");

    private final AssistantCapabilities capabilities = AssistantCapabilities.defaultInstance();

    @Test
    void frontendMenusMatchCapabilitiesMenusOneToOne() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode frontendMenus;
        try (InputStream in = new ClassPathResource("assistant/frontend-menus.json").getInputStream()) {
            frontendMenus = mapper.readTree(in);
        }
        assertTrue(frontendMenus.isArray() && !frontendMenus.isEmpty(), "frontend-menus.json 不能为空");

        List<AssistantCapabilities.MenuGroup> backendMenus = capabilities.menus();
        assertEquals(frontendMenus.size(), backendMenus.size(), "菜单分组数量必须与前端一致");

        Set<String> allTabIds = new LinkedHashSet<>();
        for (int i = 0; i < frontendMenus.size(); i++) {
            JsonNode fg = frontendMenus.get(i);
            AssistantCapabilities.MenuGroup bg = backendMenus.get(i);
            assertEquals(fg.path("id").asText(), bg.id(), "分组 ID 不一致");
            assertEquals(fg.path("title").asText(), bg.title(), "分组标题不一致");
            String fgNonAdmin = fg.hasNonNull("nonAdminTitle") ? fg.path("nonAdminTitle").asText() : null;
            assertEquals(fgNonAdmin, bg.nonAdminTitle(), "非超管分组标题不一致");

            JsonNode fItems = fg.path("items");
            assertEquals(fItems.size(), bg.items().size(), "分组 " + bg.id() + " 的菜单项数量不一致");
            for (int j = 0; j < fItems.size(); j++) {
                JsonNode fi = fItems.get(j);
                AssistantCapabilities.MenuItem bi = bg.items().get(j);
                assertEquals(fi.path("id").asText(), bi.id(), "菜单项 ID 不一致");
                assertEquals(fi.path("name").asText(), bi.name(), "菜单项名称不一致");
                assertEquals(fi.path("superAdminOnly").asBoolean(), bi.superAdminOnly(),
                        "菜单项 " + bi.id() + " 的 superAdminOnly 不一致");
                allTabIds.add(bi.id());

                JsonNode fChildren = fi.path("children");
                if (fChildren.isArray()) {
                    assertEquals(fChildren.size(), bi.children().size(), "子菜单数量不一致: " + bi.id());
                    for (int k = 0; k < fChildren.size(); k++) {
                        JsonNode fc = fChildren.get(k);
                        AssistantCapabilities.MenuChild bc = bi.children().get(k);
                        assertEquals(fc.path("id").asText(), bc.id(), "子菜单 ID 不一致");
                        assertEquals(fc.path("name").asText(), bc.name(), "子菜单名称不一致");
                        allTabIds.add(bc.id());
                    }
                }
            }
        }

        // 每个菜单 tab 在能力清单中至少覆盖 1 条能力
        Set<String> coveredTabs = new LinkedHashSet<>();
        for (AssistantCapabilities.CapabilityEntry entry : capabilities.capabilities()) {
            assertTrue(allTabIds.contains(entry.tabId()),
                    "能力条目 " + entry.id() + " 引用了不存在的 tabId: " + entry.tabId());
            coveredTabs.add(entry.tabId());
        }
        for (String tabId : allTabIds) {
            assertTrue(coveredTabs.contains(tabId), "前端菜单 tab 未被能力清单覆盖: " + tabId);
        }
    }

    @Test
    void everyReadAndWriteCapabilityReferencesAnExistingRegisteredTool() {
        AssistantQueryTools queryTools = new AssistantQueryTools(
                Mockito.mock(com.shimeta.service.AgentService.class),
                Mockito.mock(com.shimeta.service.KnowledgeBaseService.class),
                Mockito.mock(com.shimeta.repository.KnowledgeBaseRepository.class),
                Mockito.mock(com.shimeta.repository.KnowledgeIndexVersionRepository.class),
                Mockito.mock(com.shimeta.service.LlmGatewayService.class),
                Mockito.mock(com.shimeta.service.EmbeddingConfigService.class),
                Mockito.mock(com.shimeta.service.DifyConfigService.class),
                Mockito.mock(com.shimeta.service.ResourceAuthorizationService.class),
                Mockito.mock(com.shimeta.repository.AgentDailyStatRepository.class),
                Mockito.mock(com.shimeta.service.AgentTemplateService.class),
                Mockito.mock(PlatformDocsService.class)
        );
        AssistantExtendedQueryTools extendedQueryTools = new AssistantExtendedQueryTools(
                queryTools,
                Mockito.mock(com.shimeta.service.AgentService.class),
                Mockito.mock(com.shimeta.service.KnowledgeBaseService.class),
                Mockito.mock(com.shimeta.service.AgentTemplateService.class),
                Mockito.mock(com.shimeta.service.PlatformToolService.class),
                Mockito.mock(com.shimeta.service.AgentPromptVersionService.class),
                Mockito.mock(com.shimeta.service.OpenApiKeyService.class),
                Mockito.mock(com.shimeta.service.SecurityPlatformService.class),
                Mockito.mock(com.shimeta.repository.AuditEventRepository.class),
                Mockito.mock(com.shimeta.service.AlertService.class),
                Mockito.mock(com.shimeta.service.RagEvaluationService.class),
                Mockito.mock(com.shimeta.service.UserAdminService.class),
                Mockito.mock(com.shimeta.service.AgentConversationService.class),
                Mockito.mock(com.shimeta.service.ToolUsageService.class)
        );
        AssistantWriteTools writeTools = new AssistantWriteTools(
                queryTools,
                Mockito.mock(com.shimeta.service.AgentService.class),
                Mockito.mock(com.shimeta.service.AgentTemplateService.class),
                Mockito.mock(com.shimeta.service.KnowledgeBaseService.class),
                Mockito.mock(com.shimeta.repository.KnowledgeBaseRepository.class),
                Mockito.mock(com.shimeta.service.EmbeddingConfigService.class),
                Mockito.mock(com.shimeta.service.ResourceAuthorizationService.class),
                Mockito.mock(com.shimeta.repository.AssistantActionRepository.class),
                Mockito.mock(com.shimeta.service.OpenApiKeyService.class)
        );

        AssistantExtendedWriteTools extendedWriteTools = new AssistantExtendedWriteTools(
                queryTools,
                Mockito.mock(com.shimeta.service.AgentService.class),
                Mockito.mock(com.shimeta.service.AgentTemplateService.class),
                Mockito.mock(com.shimeta.service.KnowledgeBaseService.class),
                Mockito.mock(com.shimeta.repository.KnowledgeBaseRepository.class),
                Mockito.mock(com.shimeta.service.ResourceAuthorizationService.class),
                Mockito.mock(com.shimeta.service.LlmGatewayService.class),
                Mockito.mock(com.shimeta.service.EmbeddingConfigService.class),
                Mockito.mock(com.shimeta.service.OpenApiKeyService.class),
                Mockito.mock(com.shimeta.repository.AssistantActionRepository.class)
        );

        Map<String, AssistantTool> toolByName = new LinkedHashMap<>();
        for (AssistantTool t : queryTools.tools()) {
            toolByName.put(t.name(), t);
        }
        for (AssistantTool t : extendedQueryTools.tools()) {
            toolByName.put(t.name(), t);
        }
        for (AssistantTool t : writeTools.tools()) {
            toolByName.put(t.name(), t);
        }
        for (AssistantTool t : extendedWriteTools.tools()) {
            toolByName.put(t.name(), t);
        }

        Set<String> seenIds = new LinkedHashSet<>();
        assertTrue(capabilities.capabilities().size() >= 100,
                "第一版能力清单应全面覆盖现有菜单，当前仅 " + capabilities.capabilities().size() + " 条");

        for (AssistantCapabilities.CapabilityEntry entry : capabilities.capabilities()) {
            assertFalse(entry.id().isBlank(), "能力条目 ID 不能为空");
            assertTrue(seenIds.add(entry.id()), "能力条目 ID 重复: " + entry.id());
            assertTrue(VALID_HANDLING.contains(entry.handling()), "非法 handling: " + entry.id() + " -> " + entry.handling());
            assertFalse(entry.roles().isEmpty(), "角色列表不能为空: " + entry.id());
            for (String r : entry.roles()) {
                assertTrue(VALID_ROLES.contains(r), "非法角色 " + r + " in " + entry.id());
            }

            if ("READ".equals(entry.handling())) {
                assertNotNull(entry.tool(), "READ 条目必须指定 tool: " + entry.id());
                AssistantTool tool = toolByName.get(entry.tool());
                assertNotNull(tool, "READ 条目引用的工具未注册: " + entry.id() + " -> " + entry.tool());
                assertFalse(tool.write(), "READ 条目引用了写工具: " + entry.id() + " -> " + entry.tool());
            } else if ("WRITE".equals(entry.handling())) {
                assertNotNull(entry.tool(), "WRITE 条目必须指定 tool: " + entry.id());
                assertNotNull(entry.risk(), "WRITE 条目必须指定 risk(A~D): " + entry.id());
                assertTrue(VALID_RISKS.contains(entry.risk()), "非法 risk: " + entry.id() + " -> " + entry.risk());
                AssistantTool tool = toolByName.get(entry.tool());
                assertNotNull(tool, "WRITE 条目引用的工具未注册: " + entry.id() + " -> " + entry.tool());
                assertTrue(tool.write(), "WRITE 条目引用了只读工具: " + entry.id() + " -> " + entry.tool());
            }
        }

        // 反向校验：所有已注册的工具都至少被一个能力条目覆盖
        Set<String> referencedTools = new LinkedHashSet<>();
        capabilities.capabilities().stream()
                .filter(e -> e.tool() != null)
                .forEach(e -> referencedTools.add(e.tool()));
        for (String toolName : toolByName.keySet()) {
            assertTrue(referencedTools.contains(toolName), "已注册工具未被能力清单收录: " + toolName);
        }
    }

    @Test
    void everyDocAnchorExistsInBundledPlatformDocs() throws Exception {
        Map<String, String> docs = PlatformDocsService.readAllBundledDocs();
        Set<String> anchors = extractAllHeadingAnchors(docs);

        for (AssistantCapabilities.CapabilityEntry entry : capabilities.capabilities()) {
            if ("DOC".equals(entry.handling())) {
                assertNotNull(entry.docAnchor(), "DOC 条目必须有 docAnchor: " + entry.id());
                assertFalse(entry.docAnchor().isBlank(), "DOC 条目 docAnchor 不能为空: " + entry.id());
            }
            if (entry.docAnchor() != null && !entry.docAnchor().isBlank()) {
                assertTrue(anchors.contains(normalizeAnchor(entry.docAnchor())),
                        "能力条目 " + entry.id() + " 的 docAnchor 在内置文档中找不到: [" + entry.docAnchor() + "]");
            }
        }
    }

    @Test
    void platformOverviewIsRoleAware() {
        CurrentActor admin = new CurrentActor("u1", "admin", UserRole.SUPER_ADMIN);
        CurrentActor dev = new CurrentActor("u2", "dev", UserRole.DEVELOPER);

        String adminOverview = capabilities.buildPlatformOverview(admin);
        assertTrue(adminOverview.contains("模型网关"));
        assertTrue(adminOverview.contains("助手评测"));
        assertTrue(adminOverview.contains("大语言模型"));
        assertTrue(adminOverview.contains("规则与安全"));

        String devOverview = capabilities.buildPlatformOverview(dev);
        assertTrue(devOverview.contains("规则与安全"));
        assertTrue(devOverview.contains("开放与安全"));
        assertFalse(devOverview.contains("助手评测"));
        assertFalse(devOverview.contains("模型网关（"));
    }

    private static Set<String> extractAllHeadingAnchors(Map<String, String> docs) {
        Set<String> anchors = new LinkedHashSet<>();
        for (String markdown : docs.values()) {
            List<String> stack = new ArrayList<>();
            for (String line : markdown.split("\\R")) {
                Matcher m = MD_HEADING.matcher(line);
                if (!m.matches()) {
                    continue;
                }
                int level = m.group(1).length();
                String title = normalizeAnchor(m.group(2));
                while (stack.size() >= level) {
                    stack.remove(stack.size() - 1);
                }
                while (stack.size() < level - 1) {
                    stack.add("");
                }
                stack.add(title);
                anchors.add(title);
                if (level >= 3 && !stack.get(level - 2).isEmpty()) {
                    anchors.add(stack.get(level - 2) + "/" + title);
                }
            }
        }
        return anchors;
    }

    private static String normalizeAnchor(String raw) {
        return raw.replace("`", "").trim();
    }
}
