package com.shimeta.assistant;

import com.shimeta.model.AssistantAction;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AssistantUiEffectsTest {

    @Test
    void readListAgentsEmitsSearchEffectWithFilters() {
        AssistantToolRegistry.Outcome ok = new AssistantToolRegistry.Outcome(
                "list_agents", "查询智能体", true, "{\"total\":1,\"agents\":[]}");
        Map<String, Object> effect = AssistantUiEffects.fromReadTool(
                "list_agents",
                "{\"keyword\":\"客服\",\"status\":\"RUNNING\",\"scope\":\"mine\"}",
                ok
        );
        assertNotNull(effect);
        assertEquals("search", effect.get("kind"));
        assertEquals("agents", effect.get("target"));
        assertEquals("客服", effect.get("keyword"));
        assertEquals("RUNNING", effect.get("status"));
        assertEquals("mine", effect.get("scope"));
        assertTrue(String.valueOf(effect.get("label")).contains("客服"));
    }

    @Test
    void failedReadToolEmitsNoUiEffect() {
        AssistantToolRegistry.Outcome fail = AssistantToolRegistry.Outcome.failure("list_agents", "查询智能体", "error");
        assertNull(AssistantUiEffects.fromReadTool("list_agents", "{\"keyword\":\"x\"}", fail));
    }

    @Test
    void listResourcesMapsFaqToolsAndUsers() {
        AssistantToolRegistry.Outcome ok = new AssistantToolRegistry.Outcome(
                "list_resources", "查询资源", true, "{\"knowledgeBaseId\":\"kb-1\"}");
        Map<String, Object> faq = AssistantUiEffects.fromReadTool(
                "list_resources",
                "{\"resourceType\":\"faq\",\"keyword\":\"退货\"}",
                ok
        );
        assertNotNull(faq);
        assertEquals("focus_kb_tab", faq.get("kind"));
        assertEquals("knowledge", faq.get("target"));
        assertEquals("faqs", faq.get("subTab"));
        assertEquals("kb-1", faq.get("kbId"));
        assertEquals("退货", faq.get("keyword"));

        Map<String, Object> users = AssistantUiEffects.fromReadTool(
                "list_resources",
                "{\"resourceType\":\"user\",\"keyword\":\"alice\"}",
                new AssistantToolRegistry.Outcome("list_resources", "查询资源", true, "{}")
        );
        assertNotNull(users);
        assertEquals("search", users.get("kind"));
        assertEquals("users", users.get("target"));
        assertEquals("alice", users.get("keyword"));
    }

    @Test
    void executedUpdatePromptActionEmitsLivePromptSyncEffect() {
        AssistantAction action = new AssistantAction();
        action.setStatus(AssistantAction.EXECUTED);
        action.setToolName("update_agent_prompt");
        action.setResourceId("agent-42");
        action.setPayload("{\"agentId\":\"agent-42\",\"systemPrompt\":\"你是专业售后客服\"}");

        Map<String, Object> effect = AssistantUiEffects.fromExecutedAction(action, Map.of("message", "ok"));
        assertNotNull(effect);
        assertEquals("agent_prompt_updated", effect.get("kind"));
        assertEquals("agent_debug", effect.get("target"));
        assertEquals("agent-42", effect.get("agentId"));
        assertEquals("你是专业售后客服", effect.get("systemPrompt"));
    }

    @Test
    void pendingActionEmitsNoExecutedEffect() {
        AssistantAction action = new AssistantAction();
        action.setStatus(AssistantAction.PENDING);
        action.setToolName("create_agent");
        assertNull(AssistantUiEffects.fromExecutedAction(action, Map.of()));
    }

    @Test
    void deleteResourceMapsTargetByResourceType() {
        AssistantAction action = new AssistantAction();
        action.setStatus(AssistantAction.EXECUTED);
        action.setToolName("delete_resource");
        action.setResourceType("skill");
        action.setResourceId("sk-1");
        action.setPayload("{\"resourceType\":\"skill\",\"resourceId\":\"sk-1\"}");

        Map<String, Object> effect = AssistantUiEffects.fromExecutedAction(action, Map.of());
        assertNotNull(effect);
        assertEquals("resource_deleted", effect.get("kind"));
        assertEquals("skills", effect.get("target"));
        assertEquals("sk-1", effect.get("resourceId"));
    }

    @Test
    void navigateToPageEmitsCrossRouteUiEffect() {
        AssistantToolRegistry.Outcome ok = new AssistantToolRegistry.Outcome(
                "navigate_to_page", "跳转平台页面", true,
                "{\"ok\":true,\"page\":\"agent_debug\",\"agentId\":\"agent-9\",\"title\":\"智能体「广告机测试」调试页\",\"url\":\"/debug/agent-9\",\"subTab\":\"logs\"}");
        Map<String, Object> effect = AssistantUiEffects.fromReadTool(
                "navigate_to_page",
                "{\"page\":\"agent_debug\",\"agent\":\"广告机测试\",\"subTab\":\"logs\"}",
                ok
        );
        assertNotNull(effect);
        assertEquals("navigate", effect.get("kind"));
        assertEquals("agent_debug", effect.get("target"));
        assertEquals("/debug/agent-9", effect.get("url"));
        assertEquals("agent-9", effect.get("agentId"));
        assertEquals("logs", effect.get("subTab"));
        assertTrue(String.valueOf(effect.get("label")).contains("广告机测试"));
    }

    @Test
    void executedManageVariableEmitsModalChoreographyEffect() {
        AssistantAction action = new AssistantAction();
        action.setStatus(AssistantAction.EXECUTED);
        action.setToolName("manage_agent_variable");
        action.setResourceId("agent-7");
        action.setPayload("{\"agentId\":\"agent-7\",\"operation\":\"add\",\"variable\":{\"key\":\"store_name\",\"label\":\"门店名称\",\"type\":\"text\",\"required\":true,\"defaultValue\":\"深圳南山店\"}}");

        Map<String, Object> effect = AssistantUiEffects.fromExecutedAction(action, Map.of("message", "ok"));
        assertNotNull(effect);
        assertEquals("agent_variable_mutated", effect.get("kind"));
        assertEquals("agent_debug", effect.get("target"));
        assertEquals("agent-7", effect.get("agentId"));
        assertEquals("add", effect.get("operation"));
        @SuppressWarnings("unchecked")
        Map<String, Object> varMap = (Map<String, Object>) effect.get("variable");
        assertNotNull(varMap);
        assertEquals("store_name", varMap.get("key"));
        assertEquals("门店名称", varMap.get("label"));
    }

    @Test
    void executedCreateKbAndFaqAttachFormDataForModalChoreography() {
        AssistantAction kbAction = new AssistantAction();
        kbAction.setStatus(AssistantAction.EXECUTED);
        kbAction.setToolName("create_knowledge_base");
        kbAction.setResourceId("kb-99");
        kbAction.setPayload("{\"name\":\"售后排障手册\",\"description\":\"覆盖常见硬件故障\"}");

        Map<String, Object> kbEffect = AssistantUiEffects.fromExecutedAction(kbAction, Map.of());
        assertNotNull(kbEffect);
        assertEquals("resource_created", kbEffect.get("kind"));
        assertEquals("knowledge", kbEffect.get("target"));
        @SuppressWarnings("unchecked")
        Map<String, Object> kbForm = (Map<String, Object>) kbEffect.get("formData");
        assertNotNull(kbForm);
        assertEquals("售后排障手册", kbForm.get("name"));

        AssistantAction userAction = new AssistantAction();
        userAction.setStatus(AssistantAction.EXECUTED);
        userAction.setToolName("manage_user");
        userAction.setResourceId("u-5");
        userAction.setPayload("{\"operation\":\"create\",\"username\":\"bob\",\"nickname\":\"Bob\",\"role\":\"DEVELOPER\",\"password\":\"Secret123!\"}");

        Map<String, Object> userEffect = AssistantUiEffects.fromExecutedAction(userAction, Map.of());
        assertNotNull(userEffect);
        @SuppressWarnings("unchecked")
        Map<String, Object> userForm = (Map<String, Object>) userEffect.get("formData");
        assertNotNull(userForm);
        assertEquals("bob", userForm.get("username"));
        assertFalse(userForm.containsKey("password"), "密码绝不能透传到前端 uiEffect");
    }
}
