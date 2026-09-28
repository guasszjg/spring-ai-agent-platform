package com.example.agentplatform.assistant;

import com.example.agentplatform.model.UserRole;
import com.example.agentplatform.security.CurrentActor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantToolRegistryTest {

    private final CurrentActor developer = new CurrentActor("u1", "dev", UserRole.DEVELOPER);
    private final CurrentActor viewer = new CurrentActor("u2", "viewer", UserRole.VIEWER);
    private final ToolContext ctx = ToolContext.of(developer, "asc-1");

    private AssistantToolRegistry registry(AssistantTool.Handler handler) {
        return new AssistantToolRegistry(List.of(AssistantTool.read("echo", "回显", "测试工具",
                Map.of("type", "object", "properties", Map.of()), handler)));
    }

    @Test
    void passesParsedArgumentsAndContextToHandler() throws Exception {
        var registry = registry((args, c) -> Map.of("ok", true, "user", c.actor().getUserId(),
                "conversation", c.conversationId(), "q", args.path("q").asText()));

        var outcome = registry.execute("echo", "{\"q\":\"hi\"}", ctx);

        assertTrue(outcome.ok());
        assertEquals("回显", outcome.label());
        var node = new ObjectMapper().readTree(outcome.content());
        assertEquals("u1", node.path("user").asText());
        assertEquals("asc-1", node.path("conversation").asText());
        assertEquals("hi", node.path("q").asText());
    }

    @Test
    void unknownToolAndInvalidArgumentsAreReportedToTheModel() {
        var registry = registry((args, c) -> Map.of("ok", true));

        var unknown = registry.execute("delete_everything", "{}", ctx);
        assertFalse(unknown.ok());
        assertTrue(unknown.content().contains("未知工具"));

        var badJson = registry.execute("echo", "{not json", ctx);
        assertFalse(badJson.ok());
        assertTrue(badJson.content().contains("参数不是合法的 JSON"));
    }

    @Test
    void businessErrorsAreExplainedButUnexpectedErrorsAreNot() {
        var denied = registry((args, c) -> {
            throw new IllegalStateException("权限不足：无权访问该智能体");
        }).execute("echo", "{}", ctx);
        assertFalse(denied.ok());
        assertTrue(denied.content().contains("权限不足"));

        var crashed = registry((args, c) -> {
            throw new NullPointerException("secret internal detail");
        }).execute("echo", "{}", ctx);
        assertFalse(crashed.ok());
        assertFalse(crashed.content().contains("secret internal detail"));
    }

    @Test
    void errorMessagesWithQuotesAndNewlinesStayValidJson() throws Exception {
        var outcome = registry((args, c) -> {
            throw new IllegalArgumentException("第一行 \"引号\"\n第二行");
        }).execute("echo", "{}", ctx);

        var node = new ObjectMapper().readTree(outcome.content());
        assertEquals("第一行 \"引号\"\n第二行", node.path("error").asText());
    }

    @Test
    void handlerReportingOkFalseIsMarkedFailed() {
        var outcome = registry((args, c) -> Map.of("ok", false, "error", "没有找到")).execute("echo", "{}", ctx);
        assertFalse(outcome.ok());
    }

    @Test
    void largeResultsAreTruncated() {
        var outcome = registry((args, c) -> Map.of("ok", true, "text", "x".repeat(20_000))).execute("echo", "{}", ctx);
        assertTrue(outcome.content().length() < AssistantToolRegistry.MAX_RESULT_CHARS + 50);
        assertTrue(outcome.content().endsWith("请缩小查询范围）"));
    }

    @Test
    void noToolsWithoutLoggedInUser() {
        var registry = registry((args, c) -> Map.of("ok", true));
        assertTrue(registry.definitions(null).isEmpty());
        assertEquals(1, registry.definitions(developer).size());
        assertFalse(registry.execute("echo", "{}", ToolContext.of(null, "asc-1")).ok());
    }

    @Test
    void viewersNeverGetWriteToolsAndCannotCallThemByName() {
        boolean[] called = {false};
        var registry = new AssistantToolRegistry(List.of(
                AssistantTool.read("echo", "回显", "读", Map.of("type", "object"), (args, c) -> Map.of("ok", true)),
                AssistantTool.write("create_agent", "生成创建智能体操作", "写", Map.of("type", "object"), (args, c) -> {
                    called[0] = true;
                    return Map.of("ok", true);
                })));

        assertEquals(2, registry.definitions(developer).size());
        assertEquals(1, registry.definitions(viewer).size());
        assertTrue(registry.isWriteTool("create_agent"));

        var outcome = registry.execute("create_agent", "{}", ToolContext.of(viewer, "asc-1"));
        assertFalse(outcome.ok());
        assertTrue(outcome.content().contains("只读观察员"));
        assertFalse(called[0]);
    }
}
