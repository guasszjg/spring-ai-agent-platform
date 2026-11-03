package com.shimeta.service;

import com.shimeta.model.ChatGeneration;
import com.shimeta.config.OutboundUrlValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiCompatibleClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OutboundUrlValidator outboundUrlValidator;

    /** 流式读取的空闲超时：连续这么久没收到任何 data 行就判定通道卡死或在排队，中断读取（HttpClient 的 timeout 只管到响应头）。 */
    static final long DEFAULT_STREAM_IDLE_TIMEOUT_MS = 45_000;
    long streamIdleTimeoutMs = DEFAULT_STREAM_IDLE_TIMEOUT_MS;
    private static final java.util.concurrent.ScheduledExecutorService STREAM_WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "llm-stream-watchdog");
                t.setDaemon(true);
                return t;
            });

    public OpenAiCompatibleClient(OutboundUrlValidator outboundUrlValidator) {
        this.outboundUrlValidator = outboundUrlValidator;
    }

    public ProbeResult probe(String baseUrl, String apiKey, int timeoutMs) {
        return probe(baseUrl, apiKey, null, null, timeoutMs);
    }

    public ProbeResult probe(String baseUrl, String apiKey, Map<String, String> customHeaders, String defaultModel, int timeoutMs) {
        outboundUrlValidator.validateProviderBaseUrl(baseUrl);
        boolean isSpecificEndpoint = isSpecificEndpoint(baseUrl);
        if (isSpecificEndpoint) {
            return chatPing(baseUrl, apiKey, customHeaders, defaultModel, timeoutMs);
        }

        String url = normalizeBase(baseUrl) + "/models";
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(3000, timeoutMs)));
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey.trim());
            }
            if (customHeaders != null) {
                customHeaders.forEach((k, v) -> {
                    if (k != null && !k.isBlank() && v != null && !"content-type".equalsIgnoreCase(k)) {
                        builder.header(k.trim(), v.trim());
                    }
                });
            }
            HttpRequest request = builder.GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                List<String> models = parseModelIds(response.body());
                String message = models.isEmpty()
                        ? "连通正常，但未返回模型列表，可稍后手动刷新"
                        : "连通正常，已拉取 " + models.size() + " 个模型";
                return ProbeResult.ok(message, models);
            }
            // If /models returned 404, 405, 400 etc., fall back to chat ping
            log.info("GET /models returned HTTP {}, falling back to chat ping probe", response.statusCode());
            return chatPing(baseUrl, apiKey, customHeaders, defaultModel, timeoutMs);
        } catch (IllegalArgumentException e) {
            return ProbeResult.fail("Base URL 无效");
        } catch (Exception e) {
            log.warn("LLM provider /models probe failed: {}, falling back to chat ping", e.getMessage());
            return chatPing(baseUrl, apiKey, customHeaders, defaultModel, timeoutMs);
        }
    }

    private ProbeResult chatPing(String baseUrl, String apiKey, Map<String, String> customHeaders, String defaultModel, int timeoutMs) {
        String chatUrl = resolveChatUrl(baseUrl);
        String model = (defaultModel != null && !defaultModel.isBlank()) ? defaultModel.trim() : "deepseek-v4-flash";
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("messages", List.of(Map.of("role", "user", "content", "ping")));
            payload.put("max_tokens", 5);

            String body = objectMapper.writeValueAsString(payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(chatUrl))
                    .timeout(Duration.ofMillis(Math.max(4000, timeoutMs)))
                    .header("Content-Type", "application/json");

            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey.trim());
            }
            if (customHeaders != null) {
                customHeaders.forEach((k, v) -> {
                    if (k != null && !k.isBlank() && v != null && !"content-type".equalsIgnoreCase(k)) {
                        builder.header(k.trim(), v.trim());
                    }
                });
            }

            HttpRequest request = builder
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String respBody = response.body();
                String detectedModel = model;
                try {
                    JsonNode node = objectMapper.readTree(respBody);
                    if (node.has("model") && !node.get("model").asText().isBlank()) {
                        detectedModel = node.get("model").asText();
                    }
                } catch (Exception ignored) {
                }
                return ProbeResult.ok("连通测试通过！模型响应正常", List.of(detectedModel));
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return ProbeResult.fail("鉴权失败 (HTTP " + response.statusCode() + ")，请检查 API Key 或请求头配置");
            }
            return ProbeResult.fail("探测失败，HTTP " + response.statusCode() + " " + truncate(response.body()));
        } catch (Exception e) {
            log.warn("LLM chat ping probe failed: {}", e.getMessage());
            return ProbeResult.fail("无法连接供应商: " + e.getMessage());
        }
    }

    public ChatResult chat(String baseUrl, String apiKey, String model, List<Map<String, String>> messages,
                           Double temperature, int timeoutMs) {
        ChatGeneration generation = new ChatGeneration();
        generation.setTemperature(temperature);
        return chat(baseUrl, apiKey, model, messages, generation, timeoutMs);
    }

    public ChatResult chat(String baseUrl, String apiKey, String model, List<Map<String, String>> messages,
                           ChatGeneration generation, int timeoutMs) {
        List<Map<String, Object>> objectMessages = new ArrayList<>();
        if (messages != null) {
            for (Map<String, String> m : messages) {
                objectMessages.add(new LinkedHashMap<>(m));
            }
        }
        return chatWithTools(baseUrl, apiKey, model, null, objectMessages, null, null, generation, timeoutMs);
    }

    /**
     * 一次回答默认最多执行几轮工具调用（平台设置可调）。用完后再请求一次：不再提供工具定义，并提示模型根据已查到的资料作答。
     * （DeepSeek 对 tool_choice=none 不生效，会把工具调用以文本标记输出，所以改为不提供工具）
     * 只限制回答一个问题时反复调用工具，不限制提问次数。
     */
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 5;
    /** 最后一轮模型仍未作答时的兜底回复（不把内部状态暴露给用户） */
    static final String TOOL_LIMIT_FALLBACK = "抱歉，这个问题需要查询的资料较多，暂时没能整理出答案，请换个更具体的问法再试。";
    static final String FINAL_ROUND_NUDGE = "（系统提示）检索次数已达上限，不能再调用任何工具。请直接根据以上已获得的检索结果回答用户的问题，资料不足的部分如实说明。";

    /** 最后一轮的消息：在已有对话后追加作答提示 */
    private static List<Map<String, Object>> withFinalNudge(List<Map<String, Object>> messages) {
        List<Map<String, Object>> copy = new ArrayList<>(messages);
        copy.add(Map.of("role", "user", "content", FINAL_ROUND_NUDGE));
        return copy;
    }

    /** 模型把工具调用以文本标记输出（如 DeepSeek 的 DSML 标记），不能当作回答 */
    static boolean looksLikeToolMarkup(String content) {
        return content != null && (content.contains("DSML") || content.contains("<tool_call>")
                || content.contains("<｜tool▁call"));
    }

    private static String appendToolSummary(String existing, List<String> summaries) {
        String joined = String.join(" | ", summaries);
        return existing == null || existing.isBlank() ? joined : existing + " | " + joined;
    }

    public ChatResult chatWithTools(String baseUrl, String apiKey, String model, List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools, com.shimeta.tool.AgentToolRegistry toolRegistry,
                                    ChatGeneration generation, int timeoutMs) {
        return chatWithTools(baseUrl, apiKey, model, null, messages, tools, toolRegistry, generation, timeoutMs);
    }

    public ChatResult chatWithTools(String baseUrl, String apiKey, String model,
                                    Map<String, String> customHeaders,
                                    List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools,
                                    com.shimeta.tool.AgentToolRegistry toolRegistry,
                                    ChatGeneration generation, int timeoutMs) {
        return chatWithTools(baseUrl, apiKey, model, customHeaders, false, messages, tools, toolRegistry, generation, timeoutMs);
    }

    public ChatResult chatWithTools(String baseUrl, String apiKey, String model,
                                    Map<String, String> customHeaders,
                                    boolean defaultWebSearch,
                                    List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools,
                                    com.shimeta.tool.AgentToolRegistry toolRegistry,
                                    ChatGeneration generation, int timeoutMs) {
        return chatWithTools(baseUrl, apiKey, model, customHeaders, defaultWebSearch, messages, tools, toolRegistry,
                generation, timeoutMs, DEFAULT_MAX_TOOL_ROUNDS);
    }

    /** @param maxToolRounds 回答一个问题最多调用几轮工具（至少 1 轮） */
    public ChatResult chatWithTools(String baseUrl, String apiKey, String model,
                                    Map<String, String> customHeaders,
                                    boolean defaultWebSearch,
                                    List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools,
                                    com.shimeta.tool.AgentToolRegistry toolRegistry,
                                    ChatGeneration generation, int timeoutMs, int maxToolRounds) {
        outboundUrlValidator.validateProviderBaseUrl(baseUrl);
        String url = resolveChatUrl(baseUrl);
        int maxRounds = Math.max(1, maxToolRounds);
        try {
            List<Map<String, Object>> currentMessages = new ArrayList<>(messages);
            int promptTokens = 0;
            int completionTokens = 0;
            int totalTokens = 0;
            String toolCalled = null;

            for (int turn = 0; turn <= maxRounds; turn++) {
                boolean finalTurn = turn == maxRounds;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("model", model);
                payload.put("messages", finalTurn ? withFinalNudge(currentMessages) : currentMessages);
                if (tools != null && !tools.isEmpty() && !finalTurn) {
                    payload.put("tools", tools);
                }
                applyGeneration(payload, generation, defaultWebSearch);
                String body = objectMapper.writeValueAsString(payload);
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMillis(Math.max(5000, timeoutMs)))
                        .header("Content-Type", "application/json");
                if (apiKey != null && !apiKey.isBlank()) {
                    builder.header("Authorization", "Bearer " + apiKey.trim());
                }
                if (customHeaders != null) {
                    customHeaders.forEach((k, v) -> {
                        if (k != null && !k.isBlank() && v != null && !"content-type".equalsIgnoreCase(k)) {
                            builder.header(k.trim(), v.trim());
                        }
                    });
                }
                applyExtraHeaders(builder, generation);
                HttpRequest request = builder
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("HTTP " + response.statusCode() + " " + truncate(response.body()));
                }
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode choice = root.path("choices").path(0);
                JsonNode messageNode = choice.path("message");

                promptTokens += root.path("usage").path("prompt_tokens").asInt(0);
                completionTokens += root.path("usage").path("completion_tokens").asInt(0);
                totalTokens = promptTokens + completionTokens;

                JsonNode toolCalls = messageNode.path("tool_calls");
                if (toolCalls.isArray() && !toolCalls.isEmpty() && toolRegistry != null && !finalTurn) {
                    Map<String, Object> assistantMsg = new LinkedHashMap<>();
                    assistantMsg.put("role", "assistant");
                    if (messageNode.has("content") && !messageNode.get("content").isNull()) {
                        assistantMsg.put("content", messageNode.get("content").asText());
                    }
                    assistantMsg.put("tool_calls", objectMapper.convertValue(toolCalls, Object.class));
                    currentMessages.add(assistantMsg);

                    List<String> summaries = new ArrayList<>();
                    for (JsonNode callNode : toolCalls) {
                        String callId = callNode.path("id").asText();
                        String fnName = callNode.path("function").path("name").asText();
                        String fnArgs = callNode.path("function").path("arguments").asText("{}");

                        log.info("LLM autonomously selected tool [{}], args: {}", fnName, fnArgs);
                        String toolResult = toolRegistry.execute(fnName, fnArgs);
                        summaries.add(toolRegistry.formatToolCallSummary(fnName, fnArgs));

                        Map<String, Object> toolMsg = new LinkedHashMap<>();
                        toolMsg.put("role", "tool");
                        toolMsg.put("tool_call_id", callId);
                        toolMsg.put("content", toolResult);
                        currentMessages.add(toolMsg);
                    }
                    toolCalled = appendToolSummary(toolCalled, summaries);
                    continue;
                }

                String content = cleanAnswer(messageNode.path("content").asText(""));
                if (finalTurn && (content == null || content.isBlank() || looksLikeToolMarkup(content))) {
                    log.warn("工具调用达到 {} 轮且模型未给出回答，返回兜底回复", maxRounds);
                    content = TOOL_LIMIT_FALLBACK;
                }
                return new ChatResult(content, promptTokens, completionTokens, totalTokens, toolCalled);
            }
            return new ChatResult(TOOL_LIMIT_FALLBACK, promptTokens, completionTokens, totalTokens, toolCalled);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("模型调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 流式版的 {@link #chatWithTools}：模型输出的文字边生成边通过 onContent 推送；模型发起工具调用时执行工具、
     * 把结果交回模型再继续流式生成；工具最多 maxToolRounds 轮（默认 {@value #DEFAULT_MAX_TOOL_ROUNDS}），用完后不再提供工具、让模型作答。
     * 返回值与 chatWithTools 相同（内容已清理引用角标，Token 为各轮之和）。
     *
     * @param cancelled 返回 true 时停止读取（调用方已断开或输出被护栏拦截）
     */
    public ChatResult chatWithToolsStreaming(String baseUrl, String apiKey, String model,
                                             Map<String, String> customHeaders,
                                             boolean defaultWebSearch,
                                             List<Map<String, Object>> messages,
                                             List<Map<String, Object>> tools,
                                             com.shimeta.tool.AgentToolRegistry toolRegistry,
                                             ChatGeneration generation, int timeoutMs,
                                             java.util.function.Consumer<String> onContent,
                                             java.util.function.BooleanSupplier cancelled) {
        return chatWithToolsStreaming(baseUrl, apiKey, model, customHeaders, defaultWebSearch, messages, tools,
                toolRegistry, generation, timeoutMs, onContent, cancelled, DEFAULT_MAX_TOOL_ROUNDS);
    }

    public ChatResult chatWithToolsStreaming(String baseUrl, String apiKey, String model,
                                             Map<String, String> customHeaders,
                                             boolean defaultWebSearch,
                                             List<Map<String, Object>> messages,
                                             List<Map<String, Object>> tools,
                                             com.shimeta.tool.AgentToolRegistry toolRegistry,
                                             ChatGeneration generation, int timeoutMs,
                                             java.util.function.Consumer<String> onContent,
                                             java.util.function.BooleanSupplier cancelled,
                                             int maxToolRounds) {
        int maxRounds = Math.max(1, maxToolRounds);
        List<Map<String, Object>> currentMessages = new ArrayList<>(messages);
        int promptTokens = 0;
        int completionTokens = 0;
        String toolCalled = null;
        for (int turn = 0; turn <= maxRounds; turn++) {
            boolean finalTurn = turn == maxRounds;
            // 最后一轮先缓冲：模型可能把工具调用写成文本标记，确认是正常回答后再推送
            StringBuilder finalBuffer = new StringBuilder();
            StreamResult result = streamRoundWithRetry(baseUrl, apiKey, model, customHeaders, defaultWebSearch,
                    finalTurn ? withFinalNudge(currentMessages) : currentMessages,
                    finalTurn ? null : tools, generation, timeoutMs,
                    finalTurn ? finalBuffer::append : onContent, cancelled, turn > 0);
            promptTokens += result.promptTokens();
            completionTokens += result.completionTokens();
            boolean stop = cancelled != null && cancelled.getAsBoolean();
            if (!result.hasToolCalls() || toolRegistry == null || stop || finalTurn) {
                String content = cleanAnswer(result.content());
                if (finalTurn && !stop) {
                    if (content == null || content.isBlank() || looksLikeToolMarkup(content)) {
                        log.warn("工具调用达到 {} 轮且模型未给出回答，返回兜底回复（流式）", maxRounds);
                        content = TOOL_LIMIT_FALLBACK;
                        if (onContent != null) {
                            onContent.accept(content);
                        }
                    } else if (onContent != null && finalBuffer.length() > 0) {
                        onContent.accept(finalBuffer.toString());
                    }
                }
                return new ChatResult(content, promptTokens, completionTokens,
                        promptTokens + completionTokens, toolCalled);
            }
            Map<String, Object> assistantMsg = new LinkedHashMap<>();
            assistantMsg.put("role", "assistant");
            if (!result.content().isEmpty()) {
                assistantMsg.put("content", result.content());
            }
            List<Map<String, Object>> calls = new ArrayList<>();
            for (ToolCall call : result.toolCalls()) {
                calls.add(Map.of("id", call.id(), "type", "function",
                        "function", Map.of("name", call.name(), "arguments", call.arguments())));
            }
            assistantMsg.put("tool_calls", calls);
            currentMessages.add(assistantMsg);
            List<String> summaries = new ArrayList<>();
            for (ToolCall call : result.toolCalls()) {
                log.info("LLM autonomously selected tool [{}] (streaming), args: {}", call.name(), call.arguments());
                String toolResult = toolRegistry.execute(call.name(), call.arguments());
                summaries.add(toolRegistry.formatToolCallSummary(call.name(), call.arguments()));
                Map<String, Object> toolMsg = new LinkedHashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", call.id());
                toolMsg.put("content", toolResult);
                currentMessages.add(toolMsg);
            }
            toolCalled = appendToolSummary(toolCalled, summaries);
        }
        return new ChatResult(TOOL_LIMIT_FALLBACK, promptTokens, completionTokens, promptTokens + completionTokens, toolCalled);
    }

    /**
     * 跑一轮流式请求。工具执行后的轮次（retry=true）若在本轮推送任何内容之前就失败（如通道卡住超时），原样重试一次：
     * 此时已经推送过前面轮次的内容，外层不能再整体重试，只能在这里补救。首轮失败交给外层的重试/降级。
     */
    private StreamResult streamRoundWithRetry(String baseUrl, String apiKey, String model,
                                              Map<String, String> customHeaders, boolean defaultWebSearch,
                                              List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                                              ChatGeneration generation, int timeoutMs,
                                              java.util.function.Consumer<String> onContent,
                                              java.util.function.BooleanSupplier cancelled, boolean retry) {
        boolean[] emitted = {false};
        java.util.function.Consumer<String> tracking = onContent == null ? null : text -> {
            emitted[0] = true;
            onContent.accept(text);
        };
        try {
            return streamChat(baseUrl, apiKey, model, customHeaders, defaultWebSearch, messages, tools,
                    generation, timeoutMs, tracking, cancelled);
        } catch (RuntimeException e) {
            if (!retry || emitted[0] || (cancelled != null && cancelled.getAsBoolean())) {
                throw e;
            }
            log.warn("工具调用后的流式轮次失败，重试一次: {}", e.getMessage());
            return streamChat(baseUrl, apiKey, model, customHeaders, defaultWebSearch, messages, tools,
                    generation, timeoutMs, tracking, cancelled);
        }
    }

    /**
     * 流式对话（单轮）：增量内容通过 onContent 回调推送；模型发起的工具调用不执行，拼装后原样返回给调用方决定如何处理。
     * 通道忽略 stream 参数直接返回完整 JSON 时也能正确解析；不支持 stream_options 的通道自动去掉该参数重试一次。
     *
     * @param cancelled 返回 true 时停止读取（如前端已断开），返回已收到的部分结果
     */
    public StreamResult streamChat(String baseUrl, String apiKey, String model,
                                   Map<String, String> customHeaders,
                                   boolean defaultWebSearch,
                                   List<Map<String, Object>> messages,
                                   List<Map<String, Object>> tools,
                                   ChatGeneration generation, int timeoutMs,
                                   java.util.function.Consumer<String> onContent,
                                   java.util.function.BooleanSupplier cancelled) {
        outboundUrlValidator.validateProviderBaseUrl(baseUrl);
        try {
            return doStreamChat(baseUrl, apiKey, model, customHeaders, defaultWebSearch, messages, tools,
                    generation, timeoutMs, onContent, cancelled, true);
        } catch (StreamOptionsRejected e) {
            return doStreamChat(baseUrl, apiKey, model, customHeaders, defaultWebSearch, messages, tools,
                    generation, timeoutMs, onContent, cancelled, false);
        }
    }

    private StreamResult doStreamChat(String baseUrl, String apiKey, String model,
                                      Map<String, String> customHeaders, boolean defaultWebSearch,
                                      List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                                      ChatGeneration generation, int timeoutMs,
                                      java.util.function.Consumer<String> onContent,
                                      java.util.function.BooleanSupplier cancelled,
                                      boolean includeUsage) {
        String url = resolveChatUrl(baseUrl);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("messages", messages);
            if (tools != null && !tools.isEmpty()) {
                payload.put("tools", tools);
            }
            applyGeneration(payload, generation, defaultWebSearch);
            payload.put("stream", true);
            if (includeUsage) {
                payload.put("stream_options", Map.of("include_usage", true));
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(5000, timeoutMs)))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream");
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey.trim());
            }
            if (customHeaders != null) {
                customHeaders.forEach((k, v) -> {
                    if (k != null && !k.isBlank() && v != null && !"content-type".equalsIgnoreCase(k)
                            && !"accept".equalsIgnoreCase(k)) {
                        builder.header(k.trim(), v.trim());
                    }
                });
            }
            applyExtraHeaders(builder, generation);
            HttpRequest request = builder
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<java.io.InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            StreamWatchdog watchdog = new StreamWatchdog(response.body(), cancelled, streamIdleTimeoutMs);
            try (java.io.InputStream body = response.body();
                 java.io.BufferedReader reader = new java.io.BufferedReader(
                         new java.io.InputStreamReader(body, StandardCharsets.UTF_8))) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String error = reader.lines().limit(50).collect(java.util.stream.Collectors.joining(" "));
                    if (includeUsage && response.statusCode() == 400 && error.contains("stream_options")) {
                        throw new StreamOptionsRejected();
                    }
                    throw new IllegalStateException("HTTP " + response.statusCode() + " " + truncate(error));
                }
                return parseStream(reader, onContent, cancelled, watchdog::touch);
            } catch (Exception e) {
                if (watchdog.idleTimedOut()) {
                    throw new IllegalStateException("模型流式输出超过 " + streamIdleTimeoutMs / 1000 + " 秒无响应，已中断", e);
                }
                if (watchdog.abortedByCancel()) {
                    return new StreamResult("", List.of(), 0, 0); // 调用方已断开，结果不再使用
                }
                throw e;
            } finally {
                watchdog.stop();
            }
        } catch (StreamOptionsRejected | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("模型调用失败: " + e.getMessage(), e);
        }
    }

    /** 解析 OpenAI 兼容的 SSE 流；首个非空行不是 SSE（通道忽略了 stream 参数）时按完整 JSON 解析。 */
    StreamResult parseStream(java.io.BufferedReader reader,
                             java.util.function.Consumer<String> onContent,
                             java.util.function.BooleanSupplier cancelled) throws java.io.IOException {
        return parseStream(reader, onContent, cancelled, null);
    }

    /** @param onData 每收到一条 data 行回调一次（看门狗据此判断通道是否还在输出；keep-alive 注释行不算） */
    StreamResult parseStream(java.io.BufferedReader reader,
                             java.util.function.Consumer<String> onContent,
                             java.util.function.BooleanSupplier cancelled,
                             Runnable onData) throws java.io.IOException {
        StringBuilder content = new StringBuilder();
        java.util.TreeMap<Integer, String[]> toolParts = new java.util.TreeMap<>(); // index -> [id, name, arguments]
        int promptTokens = 0;
        int completionTokens = 0;
        boolean sawEvent = false;
        String line;
        while ((line = reader.readLine()) != null) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                break;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith(":")) {
                continue;
            }
            if (!sawEvent && trimmed.startsWith("{")) {
                // 非流式响应：读完剩余内容按普通 completion 解析
                StringBuilder full = new StringBuilder(trimmed);
                String rest;
                while ((rest = reader.readLine()) != null) {
                    full.append(rest);
                }
                return parseCompletion(full.toString(), onContent);
            }
            if (!trimmed.startsWith("data:")) {
                continue; // event: / id: 等字段
            }
            sawEvent = true;
            if (onData != null) {
                onData.run();
            }
            String data = trimmed.substring(5).trim();
            if ("[DONE]".equals(data)) {
                break;
            }
            JsonNode chunk;
            try {
                chunk = objectMapper.readTree(data);
            } catch (Exception e) {
                continue;
            }
            JsonNode usage = chunk.path("usage");
            if (usage.isObject()) {
                promptTokens = Math.max(promptTokens, usage.path("prompt_tokens").asInt(0));
                completionTokens = Math.max(completionTokens, usage.path("completion_tokens").asInt(0));
            }
            JsonNode delta = chunk.path("choices").path(0).path("delta");
            JsonNode text = delta.path("content");
            if (text.isTextual() && !text.asText().isEmpty()) {
                content.append(text.asText());
                if (onContent != null) {
                    onContent.accept(text.asText());
                }
            }
            JsonNode calls = delta.path("tool_calls");
            if (calls.isArray()) {
                for (JsonNode call : calls) {
                    int index = call.path("index").asInt(toolParts.size());
                    String[] parts = toolParts.computeIfAbsent(index, k -> new String[]{null, null, ""});
                    if (call.hasNonNull("id") && !call.get("id").asText().isBlank()) {
                        parts[0] = call.get("id").asText();
                    }
                    JsonNode fn = call.path("function");
                    if (fn.hasNonNull("name") && !fn.get("name").asText().isBlank()) {
                        parts[1] = fn.get("name").asText();
                    }
                    if (fn.hasNonNull("arguments")) {
                        parts[2] = parts[2] + fn.get("arguments").asText();
                    }
                }
            }
        }
        List<ToolCall> toolCalls = new ArrayList<>();
        toolParts.forEach((index, parts) -> {
            if (parts[1] != null) {
                String id = parts[0] != null ? parts[0] : "call_" + index;
                toolCalls.add(new ToolCall(id, parts[1], parts[2].isBlank() ? "{}" : parts[2]));
            }
        });
        return new StreamResult(content.toString(), toolCalls, promptTokens, completionTokens);
    }

    private StreamResult parseCompletion(String body, java.util.function.Consumer<String> onContent) throws java.io.IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode message = root.path("choices").path(0).path("message");
        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode calls = message.path("tool_calls");
        if (calls.isArray()) {
            int i = 0;
            for (JsonNode call : calls) {
                String name = call.path("function").path("name").asText("");
                if (!name.isBlank()) {
                    toolCalls.add(new ToolCall(call.path("id").asText("call_" + i), name,
                            call.path("function").path("arguments").asText("{}")));
                }
                i++;
            }
        }
        String content = message.path("content").isTextual() ? message.path("content").asText() : "";
        if (!content.isEmpty() && onContent != null) {
            onContent.accept(content);
        }
        return new StreamResult(content, toolCalls,
                root.path("usage").path("prompt_tokens").asInt(0),
                root.path("usage").path("completion_tokens").asInt(0));
    }

    /**
     * 盯住流式响应体：每秒检查一次，连续 idleTimeoutMs 没收到 data 行（通道繁忙时只发 keep-alive 注释，不算输出）、
     * 或调用方已取消（且读取卡住超过 2 秒）时，
     * 关闭响应体并中断读取线程，让阻塞在 readLine 的线程抛出异常返回。
     */
    private static final class StreamWatchdog {
        private final java.io.InputStream raw;
        private final java.util.function.BooleanSupplier cancelled;
        private final long idleTimeoutMs;
        private final Thread reader = Thread.currentThread();
        private final Object lock = new Object();
        private volatile long lastActivity = System.currentTimeMillis();
        private boolean stopped;
        private boolean fired;
        private volatile boolean idleTimedOut;
        private volatile boolean abortedByCancel;
        private final java.util.concurrent.ScheduledFuture<?> task;

        StreamWatchdog(java.io.InputStream raw, java.util.function.BooleanSupplier cancelled, long idleTimeoutMs) {
            this.raw = raw;
            this.cancelled = cancelled;
            this.idleTimeoutMs = idleTimeoutMs;
            long period = Math.min(1000, Math.max(50, idleTimeoutMs / 4));
            this.task = STREAM_WATCHDOG.scheduleWithFixedDelay(this::check, period, period,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        /** 收到有效数据时调用，重置空闲计时 */
        void touch() {
            lastActivity = System.currentTimeMillis();
        }

        private void check() {
            long idle = System.currentTimeMillis() - lastActivity;
            boolean timeout = idle >= idleTimeoutMs;
            boolean cancel = !timeout && idle >= 2000 && cancelled != null && cancelled.getAsBoolean();
            if (!timeout && !cancel) {
                return;
            }
            synchronized (lock) {
                if (stopped || fired) {
                    return;
                }
                fired = true;
                idleTimedOut = timeout;
                abortedByCancel = cancel;
                log.warn(timeout ? "模型流式输出 {}ms 无数据，中断读取" : "调用方已取消且流式读取阻塞 {}ms，中断读取", idle);
                try {
                    raw.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响后面的中断
                }
                reader.interrupt();
            }
            if (task != null) {
                task.cancel(false);
            }
        }

        boolean idleTimedOut() {
            return idleTimedOut;
        }

        boolean abortedByCancel() {
            return abortedByCancel;
        }

        void stop() {
            task.cancel(false);
            synchronized (lock) {
                stopped = true;
                if (fired) {
                    Thread.interrupted(); // 清掉看门狗留下的中断标记，免得影响线程池里的下一个任务
                }
            }
        }
    }

    /** 通道不支持 stream_options 参数（仅内部用于去掉该参数重试） */
    private static class StreamOptionsRejected extends RuntimeException {
        StreamOptionsRejected() {
            super(null, null, false, false);
        }
    }

    public record ToolCall(String id, String name, String arguments) {
    }

    public record StreamResult(String content, List<ToolCall> toolCalls, int promptTokens, int completionTokens) {
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    private void applyGeneration(Map<String, Object> payload, ChatGeneration generation) {
        applyGeneration(payload, generation, false);
    }

    private void applyGeneration(Map<String, Object> payload, ChatGeneration generation, boolean defaultWebSearch) {
        if (generation != null) {
            if (generation.getTemperature() != null) {
                payload.put("temperature", generation.getTemperature());
            }
            if (generation.getMaxTokens() != null) {
                payload.put("max_tokens", generation.getMaxTokens());
            }
            if (generation.getTopP() != null) {
                payload.put("top_p", generation.getTopP());
            }
            if (generation.getN() != null && generation.getN() > 0) {
                payload.put("n", generation.getN());
            }
            if (generation.getFrequencyPenalty() != null) {
                payload.put("frequency_penalty", generation.getFrequencyPenalty());
            }
            if (generation.getResponseFormat() != null && !generation.getResponseFormat().isBlank()
                    && !"text".equalsIgnoreCase(generation.getResponseFormat())) {
                payload.put("response_format", Map.of("type", generation.getResponseFormat()));
            }
            if (generation.getThinking() != null) {
                boolean thinkingOn = Boolean.TRUE.equals(generation.getThinking());
                payload.put("enable_thinking", thinkingOn);
                payload.put("thinking", Map.of("type", thinkingOn ? "enabled" : "disabled"));
            }
        }

        // 联网搜索生效判定：只要通道默认启用联网搜索，或者智能体显式开启了联网搜索，均予以激活
        boolean searchOn = defaultWebSearch || (generation != null && Boolean.TRUE.equals(generation.getWebSearch()));

        if (searchOn) {
            payload.put("enable_search", true);
            payload.put("web_search", true);

            Map<String, Object> location = new LinkedHashMap<>();
            location.put("type", "approximate");
            location.put("country", "CN");
            location.put("region", "Guangdong");
            location.put("city", "Shenzhen");
            location.put("timezone", "Asia/Shanghai");

            Map<String, Object> searchOptions = new LinkedHashMap<>();
            searchOptions.put("enable", true);
            searchOptions.put("search_source", "lite");
            searchOptions.put("user_location", location);
            payload.put("web_search_options", searchOptions);
        }
    }

    private void applyExtraHeaders(HttpRequest.Builder builder, ChatGeneration generation) {
        if (generation == null || generation.getExtraHeaders() == null || generation.getExtraHeaders().isBlank()) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(generation.getExtraHeaders());
            if (!node.isObject()) {
                return;
            }
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                if (key == null || key.isBlank()) {
                    return;
                }
                String lower = key.toLowerCase();
                if ("authorization".equals(lower) || "content-type".equals(lower)) {
                    return;
                }
                if (entry.getValue().isTextual() || entry.getValue().isNumber() || entry.getValue().isBoolean()) {
                    builder.header(key, entry.getValue().asText());
                }
            });
        } catch (Exception e) {
            log.warn("Invalid extra headers JSON: {}", e.getMessage());
        }
    }

    public static String normalizeBase(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Base URL 不能为空");
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public static boolean isSpecificEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String base = normalizeBase(baseUrl);
        if (base.endsWith("/chat/completions")) {
            return true;
        }
        try {
            URI uri = URI.create(base);
            String path = uri.getPath();
            if (path != null && !path.isBlank() && !path.equals("/")) {
                String cleanPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                if (!cleanPath.matches(".*/v\\d+(?:beta\\d*)?$")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static String resolveChatUrl(String baseUrl) {
        String base = normalizeBase(baseUrl);
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        if (isSpecificEndpoint(base)) {
            return base;
        }
        return base + "/chat/completions";
    }

    private List<String> parseModelIds(String body) {
        List<String> ids = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            if (data.isArray()) {
                for (JsonNode item : data) {
                    String id = item.path("id").asText("");
                    if (!id.isBlank()) {
                        ids.add(id.trim());
                    }
                }
            } else if (root.isArray()) {
                for (JsonNode item : root) {
                    String id = item.isTextual() ? item.asText() : item.path("id").asText("");
                    if (!id.isBlank()) {
                        ids.add(id.trim());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse model list: {}", e.getMessage());
        }
        List<String> chatModels = ids.stream()
                .filter(OpenAiCompatibleClient::isLikelyChatModel)
                .distinct()
                .toList();
        return chatModels.isEmpty() ? ids.stream().distinct().toList() : chatModels;
    }

    private static boolean isLikelyChatModel(String id) {
        String name = id.toLowerCase();
        return !name.contains("embed")
                && !name.contains("whisper")
                && !name.contains("tts")
                && !name.contains("dall")
                && !name.contains("rerank")
                && !name.contains("image");
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() > 180 ? compact.substring(0, 180) + "..." : compact;
    }

    public record ProbeResult(boolean success, String message, List<String> models) {
        static ProbeResult ok(String message, List<String> models) {
            return new ProbeResult(true, message, models != null ? models : List.of());
        }

        static ProbeResult fail(String message) {
            return new ProbeResult(false, message, List.of());
        }
    }

    public record ChatResult(String content, int promptTokens, int completionTokens, int totalTokens, String toolCalled) {
        public ChatResult(String content, int promptTokens, int completionTokens, int totalTokens) {
            this(content, promptTokens, completionTokens, totalTokens, null);
        }
    }

    public static List<Map<String, Object>> toMessages(String systemPrompt, String userMessage,
                                                       List<com.shimeta.model.ChatMessage> history) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt.trim()));
        }
        if (history != null) {
            for (com.shimeta.model.ChatMessage item : history) {
                if (item.getRole() == null || item.getContent() == null) {
                    continue;
                }
                String role = item.getRole().equalsIgnoreCase("assistant") ? "assistant" : "user";
                messages.add(Map.of("role", role, "content", item.getContent()));
            }
        }
        messages.add(Map.of("role", "user", "content", userMessage));
        return messages;
    }

    public static String cleanAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return answer;
        }
        return answer
                .replaceAll("\\[\\d+\\]", "")
                .replaceAll("【\\d+】", "")
                .replaceAll("[¹²³⁴⁵⁶⁷⁸⁹⁰]+", "")
                .trim();
    }
}
