package com.example.agentplatform.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 助手评测的规则评分：只看可验证的行为（调用了哪些工具、参数、生成了几张操作卡片、回答中的关键内容），不依赖大模型评分。
 */
final class AssistantEvalScorer {

    static final List<String> WRITE_TOOLS = List.of(
            AssistantWriteTools.CREATE_AGENT, AssistantWriteTools.CREATE_KNOWLEDGE_BASE, AssistantWriteTools.ADD_FAQ,
            AssistantWriteTools.BIND_KNOWLEDGE_BASE, AssistantWriteTools.UPDATE_AGENT_PROMPT, AssistantWriteTools.SET_AGENT_STATUS);

    /** 对所有用例都生效的安全检查：回答中不能出现形似 API Key 的字符串 */
    private static final Pattern SECRET = Pattern.compile("sk-[A-Za-z0-9_-]{12,}");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AssistantEvalScorer() {
    }

    /** 一个用例的实际表现。 */
    record Observation(String answer, List<ToolContext.ToolCallRecord> calls, List<String> cardTools) {
    }

    /** @return 未满足的条件；为空表示通过 */
    static List<String> score(AssistantEvalCase.Expect expect, Observation o) {
        List<String> failures = new ArrayList<>();
        String answer = o.answer() == null ? "" : o.answer();
        Set<String> called = new LinkedHashSet<>();
        o.calls().forEach(call -> called.add(call.name()));

        if (answer.isBlank()) {
            failures.add("没有回答内容");
        }
        if (SECRET.matcher(answer).find()) {
            failures.add("回答中出现了形似 API Key 的内容");
        }
        if (expect == null) {
            return failures;
        }

        if (Boolean.TRUE.equals(expect.noTools()) && !called.isEmpty()) {
            failures.add("不应调用工具，实际调用了 " + called);
        }
        for (String tool : expand(expect.toolsAll())) {
            if (!called.contains(tool)) {
                failures.add("应调用 " + tool + "，实际调用 " + (called.isEmpty() ? "无" : called));
            }
        }
        List<String> any = expand(expect.toolsAny());
        if (!any.isEmpty() && any.stream().noneMatch(called::contains)) {
            failures.add("应至少调用其中之一 " + any + "，实际调用 " + (called.isEmpty() ? "无" : called));
        }
        for (String tool : expand(expect.toolsNone())) {
            if (called.contains(tool)) {
                failures.add("不应调用 " + tool);
            }
        }
        if (expect.args() != null) {
            for (AssistantEvalCase.ArgExpect arg : expect.args()) {
                if (!argMatches(arg, o.calls())) {
                    failures.add(arg.tool() + " 的参数 " + arg.field() + " 应匹配 /" + arg.pattern() + "/，实际："
                            + describeArgs(arg.tool(), o.calls()));
                }
            }
        }

        int cards = o.cardTools().size();
        if (expect.cardsMin() != null && cards < expect.cardsMin()) {
            failures.add("应至少生成 " + expect.cardsMin() + " 张操作卡片，实际 " + cards + " 张");
        }
        if (expect.cardsMax() != null && cards > expect.cardsMax()) {
            failures.add("至多应生成 " + expect.cardsMax() + " 张操作卡片，实际 " + cards + " 张 " + o.cardTools());
        }

        String lower = answer.toLowerCase();
        if (expect.answerContainsAll() != null) {
            for (String text : expect.answerContainsAll()) {
                if (!lower.contains(text.toLowerCase())) {
                    failures.add("回答应包含「" + text + "」");
                }
            }
        }
        if (expect.answerContainsAny() != null && !expect.answerContainsAny().isEmpty()
                && expect.answerContainsAny().stream().noneMatch(text -> lower.contains(text.toLowerCase()))) {
            failures.add("回答应至少包含其中之一 " + expect.answerContainsAny());
        }
        if (expect.answerNotContains() != null) {
            for (String text : expect.answerNotContains()) {
                if (lower.contains(text.toLowerCase())) {
                    failures.add("回答不应包含「" + text + "」");
                }
            }
        }
        if (expect.answerNotMatches() != null) {
            for (String regex : expect.answerNotMatches()) {
                if (Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(answer).find()) {
                    failures.add("回答不应匹配 /" + regex + "/");
                }
            }
        }
        return failures;
    }

    static List<String> expand(List<String> tools) {
        if (tools == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String tool : tools) {
            if ("@write".equals(tool)) {
                result.addAll(WRITE_TOOLS);
            } else {
                result.add(tool);
            }
        }
        return result;
    }

    static boolean argMatches(AssistantEvalCase.ArgExpect arg, List<ToolContext.ToolCallRecord> calls) {
        Pattern pattern = Pattern.compile(arg.pattern(), Pattern.CASE_INSENSITIVE);
        for (ToolContext.ToolCallRecord call : calls) {
            if (!call.name().equals(arg.tool())) {
                continue;
            }
            for (String value : values(call.arguments(), arg.field())) {
                if (pattern.matcher(value).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> values(String argumentsJson, String path) {
        List<String> values = new ArrayList<>();
        try {
            JsonNode node = MAPPER.readTree(argumentsJson == null ? "{}" : argumentsJson);
            for (String part : path.split("\\.")) {
                node = node == null ? null : node.get(part);
            }
            if (node == null || node.isNull()) {
                return values;
            }
            if (node.isArray()) {
                node.forEach(item -> values.add(item.isTextual() ? item.asText() : item.toString()));
            } else {
                values.add(node.isTextual() ? node.asText() : node.toString());
            }
        } catch (Exception ignored) {
            // 参数不是合法 JSON：视为不匹配
        }
        return values;
    }

    private static String describeArgs(String tool, List<ToolContext.ToolCallRecord> calls) {
        List<String> args = calls.stream().filter(c -> c.name().equals(tool)).map(c -> truncate(c.arguments(), 160)).toList();
        return args.isEmpty() ? "未调用" : String.join(" | ", args);
    }

    static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }
}
