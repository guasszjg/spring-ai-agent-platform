package com.example.agentplatform.service;

import com.example.agentplatform.model.ChatGeneration;
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

    public OpenAiCompatibleClient() {
    }

    public ProbeResult probe(String baseUrl, String apiKey, int timeoutMs) {
        String url = normalizeBase(baseUrl) + "/models";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(3000, timeoutMs)))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                List<String> models = parseModelIds(response.body());
                String message = models.isEmpty()
                        ? "连通正常，但未返回模型列表，可稍后手动刷新"
                        : "连通正常，已拉取 " + models.size() + " 个模型";
                return ProbeResult.ok(message, models);
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return ProbeResult.fail("鉴权失败，请检查 API Key 是否有效");
            }
            return ProbeResult.fail("探测失败，HTTP " + response.statusCode() + " " + truncate(response.body()));
        } catch (IllegalArgumentException e) {
            return ProbeResult.fail("Base URL 无效");
        } catch (Exception e) {
            log.warn("LLM provider probe failed: {}", e.getMessage());
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
        String url = normalizeBase(baseUrl) + "/chat/completions";
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("messages", messages);
            applyGeneration(payload, generation);
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(5000, timeoutMs)))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json");
            applyExtraHeaders(builder, generation);
            HttpRequest request = builder
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " " + truncate(response.body()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
            int completionTokens = root.path("usage").path("completion_tokens").asInt(0);
            int totalTokens = root.path("usage").path("total_tokens").asInt(promptTokens + completionTokens);
            return new ChatResult(content, promptTokens, completionTokens, totalTokens);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("模型调用失败: " + e.getMessage(), e);
        }
    }

    private void applyGeneration(Map<String, Object> payload, ChatGeneration generation) {
        if (generation == null) {
            return;
        }
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
        if (generation.getWebSearch() != null) {
            payload.put("enable_search", generation.getWebSearch());
        }
        if (generation.getThinking() != null) {
            boolean thinkingOn = Boolean.TRUE.equals(generation.getThinking());
            payload.put("enable_thinking", thinkingOn);
            payload.put("thinking", Map.of("type", thinkingOn ? "enabled" : "disabled"));
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

    public record ChatResult(String content, int promptTokens, int completionTokens, int totalTokens) {
    }

    public static List<Map<String, String>> toMessages(String systemPrompt, String userMessage,
                                                       List<com.example.agentplatform.model.ChatMessage> history) {
        List<Map<String, String>> messages = new ArrayList<>();
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
