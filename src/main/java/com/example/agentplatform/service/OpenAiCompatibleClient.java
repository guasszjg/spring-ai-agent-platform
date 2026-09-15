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

                String content = messageNode.path("content").asText("");
                return new ChatResult(content, promptTokens, completionTokens, totalTokens, toolCalled);
            }
            return new ChatResult("工具调用轮次达到上限", promptTokens, completionTokens, totalTokens, toolCalled);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("模型调用失败: " + e.getMessage(), e);
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
}
