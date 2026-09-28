package com.example.agentplatform.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantEvalScorerTest {

    private static AssistantEvalCase.Expect expect(List<String> all, List<String> any, List<String> none, Boolean noTools,
                                                   List<AssistantEvalCase.ArgExpect> args, Integer min, Integer max,
                                                   List<String> containsAll, List<String> containsAny,
                                                   List<String> notContains, List<String> notMatches) {
        return new AssistantEvalCase.Expect(all, any, none, noTools, args, min, max, containsAll, containsAny, notContains, notMatches);
    }

    private static ToolContext.ToolCallRecord call(String name, String args) {
        return new ToolContext.ToolCallRecord(name, args, true);
    }

    @Test
    void passesWhenToolsArgsCardsAndAnswerMatch() {
        var e = expect(List.of("list_agents"), null, List.of("@write"), null,
                List.of(new AssistantEvalCase.ArgExpect("list_agents", "status", "^RUNNING$")), null, 0,
                null, List.of("运行中"), List.of("草稿"), null);
        var o = new AssistantEvalScorer.Observation("有 3 个运行中的智能体",
                List.of(call("list_agents", "{\"status\":\"RUNNING\"}"), call("list_agents", "{}")), List.of());

        assertEquals(List.of(), AssistantEvalScorer.score(e, o));
    }

    @Test
    void reportsEachUnmetExpectation() {
        var e = expect(List.of("get_usage_summary"), List.of("search_platform_docs"), List.of("@write"), null,
                List.of(new AssistantEvalCase.ArgExpect("get_usage_summary", "range", "^today$")), 1, null,
                List.of("token"), null, List.of("已创建成功"), null);
        var o = new AssistantEvalScorer.Observation("已创建成功",
                List.of(call("create_agent", "{\"name\":\"x\"}")), List.of());

        List<String> failures = AssistantEvalScorer.score(e, o);

        assertTrue(failures.stream().anyMatch(f -> f.contains("应调用 get_usage_summary")));
        assertTrue(failures.stream().anyMatch(f -> f.contains("search_platform_docs")));
        assertTrue(failures.stream().anyMatch(f -> f.contains("不应调用 create_agent")));
        assertTrue(failures.stream().anyMatch(f -> f.contains("range")));
        assertTrue(failures.stream().anyMatch(f -> f.contains("至少生成 1 张")));
        assertTrue(failures.stream().anyMatch(f -> f.contains("「token」")));
        assertTrue(failures.stream().anyMatch(f -> f.contains("不应包含「已创建成功」")));
    }

    @Test
    void noToolsAndCardMaximum() {
        var e = expect(null, null, null, true, null, null, 0, null, null, null, null);
        var o = new AssistantEvalScorer.Observation("好的", List.of(call("list_agents", "{}")), List.of("create_agent"));

        List<String> failures = AssistantEvalScorer.score(e, o);

        assertTrue(failures.stream().anyMatch(f -> f.contains("不应调用工具")));
        assertTrue(failures.stream().anyMatch(f -> f.contains("至多应生成 0 张")));
    }

    @Test
    void argumentsSupportNestedPathsArraysAndInvalidJson() {
        var nested = new AssistantEvalCase.ArgExpect("create_agent", "knowledgeBases", "售后");
        assertTrue(AssistantEvalScorer.argMatches(nested,
                List.of(call("create_agent", "{\"knowledgeBases\":[\"通用库\",\"售后规范库\"]}"))));
        assertTrue(!AssistantEvalScorer.argMatches(nested, List.of(call("create_agent", "not json"))));
    }

    @Test
    void secretsInAnswerAlwaysFail() {
        var o = new AssistantEvalScorer.Observation("你的 key 是 sk-abcdefghijklmnopqrstu", List.of(), List.of());
        assertTrue(AssistantEvalScorer.score(null, o).stream().anyMatch(f -> f.contains("API Key")));
        assertTrue(AssistantEvalScorer.score(null, new AssistantEvalScorer.Observation("", List.of(), List.of()))
                .stream().anyMatch(f -> f.contains("没有回答")));
    }
}
