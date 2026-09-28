package com.example.agentplatform.service;

import com.example.agentplatform.model.ChatGeneration;
import com.example.agentplatform.config.OutboundUrlValidator;
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

    public ChatResult chatWithTools(String baseUrl, String apiKey, String model, List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools, com.example.agentplatform.tool.AgentToolRegistry toolRegistry,
                                    ChatGeneration generation, int timeoutMs) {
        return chatWithTools(baseUrl, apiKey, model, null, messages, tools, toolRegistry, generation, timeoutMs);
    }

    public ChatResult chatWithTools(String baseUrl, String apiKey, String model,
                                    Map<String, String> customHeaders,
                                    List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools,
                                    com.example.agentplatform.tool.AgentToolRegistry toolRegistry,
                                    ChatGeneration generation, int timeoutMs) {
        return chatWithTools(baseUrl, apiKey, model, customHeaders, false, messages, tools, toolRegistry, generation, timeoutMs);
    }

    public ChatResult chatWithTools(String baseUrl, String apiKey, String model,
                                    Map<String, String> customHeaders,
                                    boolean defaultWebSearch,
                                    List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools,
                                    com.example.agentplatform.tool.AgentToolRegistry toolRegistry,
                                    ChatGeneration generation, int timeoutMs) {
        outboundUrlValidator.validateProviderBaseUrl(baseUrl);
        String url = resolveChatUrl(baseUrl);
        try {
            List<Map<String, Object>> currentMessages = new ArrayList<>(messages);
            int promptTokens = 0;
            int completionTokens = 0;
            int totalTokens = 0;
            String toolCalled = null;

            int maxTurns = 3;
            for (int turn = 0; turn < maxTurns; turn++) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("model", model);
                payload.put("messages", currentMessages);
                if (tools != null && !tools.isEmpty()) {
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
                if (toolCalls.isArray() && !toolCalls.isEmpty() && toolRegistry != null) {
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
                    if (toolCalled == null) {
                        toolCalled = String.join(" | ", summaries);
                    }
                    continue;
                }

                String content = cleanAnswer(messageNode.path("content").asText(""));
                return new ChatResult(content, promptTokens, completionTokens, totalTokens, toolCalled);
            }
            return new ChatResult("工具调用轮次达到上限", promptTokens, completionTokens, totalTokens, toolCalled);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("模型调用失败: " + e.getMessage(), e);
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
                return parseStream(reader, onContent, cancelled);
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
                                                       List<com.example.agentplatform.model.ChatMessage> history) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt.trim()));
        }
        if (history != null) {
            for (com.example.agentplatform.model.ChatMessage item : history) {
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
