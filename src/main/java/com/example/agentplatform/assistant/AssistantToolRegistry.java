package com.example.agentplatform.assistant;

import com.example.agentplatform.security.CurrentActor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 平台 AI 助手的工具注册表。
 *
 * <p>与 {@link com.example.agentplatform.tool.AgentToolRegistry} 完全独立：平台操作能力只开放给助手，
 * 不会出现在普通智能体（包括通过开放 API 调用的智能体）的工具列表里。
 * P1 只有只读工具，模型发起的调用直接以当前用户身份执行。
 */
@Component
public class AssistantToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(AssistantToolRegistry.class);

    /** 单个工具结果回传给模型的最大字符数，控制上下文开销 */
    static final int MAX_RESULT_CHARS = 8000;

    private final Map<String, AssistantTool> tools;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public AssistantToolRegistry(AssistantQueryTools queryTools) {
        this(queryTools.tools());
    }

    AssistantToolRegistry(List<AssistantTool> tools) {
        this.tools = tools.stream().collect(Collectors.toMap(AssistantTool::name, Function.identity(),
                (a, b) -> a, LinkedHashMap::new));
    }

    /** 当前用户可用的工具定义。P1 全部为只读工具，权限在工具内部按用户过滤数据。 */
    public List<Map<String, Object>> definitions(CurrentActor actor) {
        if (actor == null) {
            return List.of();
        }
        return tools.values().stream().map(AssistantTool::definition).toList();
    }

    public String labelOf(String name) {
        AssistantTool tool = tools.get(name);
        return tool != null ? tool.label() : name;
    }

    /**
     * 执行工具并返回回传给模型的 JSON。任何异常都转成 {ok:false,error} 交给模型解释，不向上抛。
     */
    public Outcome execute(String name, String argumentsJson, CurrentActor actor) {
        AssistantTool tool = tools.get(name);
        if (tool == null) {
            return Outcome.failure(name, name, "未知工具：" + name);
        }
        if (actor == null) {
            return Outcome.failure(name, tool.label(), "未登录");
        }
        JsonNode args;
        try {
            args = argumentsJson == null || argumentsJson.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(argumentsJson);
            if (args == null || !args.isObject()) {
                args = objectMapper.createObjectNode();
            }
        } catch (Exception e) {
            return Outcome.failure(name, tool.label(), "参数不是合法的 JSON，请修正后重试");
        }
        try {
            Map<String, Object> result = tool.handler().handle(args, actor);
            boolean ok = !(result.get("ok") instanceof Boolean b) || b;
            return new Outcome(name, tool.label(), ok, toJson(result));
        } catch (IllegalArgumentException | IllegalStateException e) {
            // 业务校验与权限不足：原因可以告诉用户
            return Outcome.failure(name, tool.label(), e.getMessage());
        } catch (Exception e) {
            log.warn("Assistant tool [{}] failed: {}", name, e.getMessage(), e);
            return Outcome.failure(name, tool.label(), "查询失败，请稍后重试");
        }
    }

    private String toJson(Map<String, Object> result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            if (json.length() > MAX_RESULT_CHARS) {
                return json.substring(0, MAX_RESULT_CHARS) + "…（结果过长已截断，请缩小查询范围）";
            }
            return json;
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"结果序列化失败\"}";
        }
    }

    /**
     * @param content 回传给模型的 JSON
     */
    public record Outcome(String name, String label, boolean ok, String content) {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        static Outcome failure(String name, String label, String error) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", false);
            body.put("error", error == null || error.isBlank() ? "未知错误" : error);
            try {
                return new Outcome(name, label, false, MAPPER.writeValueAsString(body));
            } catch (Exception e) {
                return new Outcome(name, label, false, "{\"ok\":false,\"error\":\"未知错误\"}");
            }
        }
    }
}
