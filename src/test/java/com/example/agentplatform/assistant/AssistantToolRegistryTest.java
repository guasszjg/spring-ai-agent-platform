package com.example.agentplatform.assistant;

import com.example.agentplatform.model.UserRole;
import com.example.agentplatform.security.CurrentActor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantToolRegistryTest {

    private final CurrentActor actor = new CurrentActor("u1", "dev", UserRole.DEVELOPER);

    private AssistantToolRegistry registry(AssistantTool.Handler handler) {
        return new AssistantToolRegistry(List.of(new AssistantTool("echo", "回显", "测试工具",
                Map.of("type", "object", "properties", Map.of()), handler)));
    }

    @Test
    void passesParsedArgumentsAndCurrentUserToHandler() throws Exception {
        var registry = registry((args, who) -> Map.of("ok", true, "user", who.getUserId(), "q", args.path("q").asText()));

        var outcome = registry.execute("echo", "{\"q\":\"hi\"}", actor);

        assertTrue(outcome.ok());
        assertEquals("回显", outcome.label());
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(outcome.content());
        assertEquals("u1", node.path("user").asText());
        assertEquals("hi", node.path("q").asText());
    }

    @Test
    void unknownToolAndInvalidArgumentsAreReportedToTheModel() {
        var registry = registry((args, who) -> Map.of("ok", true));

        var unknown = registry.execute("delete_everything", "{}", actor);
        assertFalse(unknown.ok());
        assertTrue(unknown.content().contains("未知工具"));

        var badJson = registry.execute("echo", "{not json", actor);
        assertFalse(badJson.ok());
        assertTrue(badJson.content().contains("参数不是合法的 JSON"));
    }

    @Test
    void businessErrorsAreExplainedButUnexpectedErrorsAreNot() {
        var denied = registry((args, who) -> {
            throw new IllegalStateException("权限不足：无权访问该智能体");
        }).execute("echo", "{}", actor);
        assertFalse(denied.ok());
        assertTrue(denied.content().contains("权限不足"));

        var crashed = registry((args, who) -> {
            throw new NullPointerException("secret internal detail");
        }).execute("echo", "{}", actor);
        assertFalse(crashed.ok());
        assertFalse(crashed.content().contains("secret internal detail"));
    }

    @Test
    void errorMessagesWithQuotesAndNewlinesStayValidJson() throws Exception {
        var outcome = registry((args, who) -> {
            throw new IllegalArgumentException("第一行 \"引号\"\n第二行");
        }).execute("echo", "{}", actor);

        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(outcome.content());
        assertEquals("第一行 \"引号\"\n第二行", node.path("error").asText());
    }

    @Test
    void handlerReportingOkFalseIsMarkedFailed() {
        var outcome = registry((args, who) -> Map.of("ok", false, "error", "没有找到")).execute("echo", "{}", actor);
        assertFalse(outcome.ok());
    }

    @Test
    void largeResultsAreTruncated() {
        var outcome = registry((args, who) -> Map.of("ok", true, "text", "x".repeat(20_000))).execute("echo", "{}", actor);
        assertTrue(outcome.content().length() < AssistantToolRegistry.MAX_RESULT_CHARS + 50);
        assertTrue(outcome.content().endsWith("请缩小查询范围）"));
    }

    @Test
    void noToolsWithoutLoggedInUser() {
        var registry = registry((args, who) -> Map.of("ok", true));
        assertTrue(registry.definitions(null).isEmpty());
        assertEquals(1, registry.definitions(actor).size());
        assertFalse(registry.execute("echo", "{}", null).ok());
    }
}
