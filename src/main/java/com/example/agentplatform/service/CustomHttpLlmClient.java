package com.example.agentplatform.service;

import com.example.agentplatform.config.OutboundUrlValidator;
import com.example.agentplatform.model.ChatGeneration;
import com.example.agentplatform.model.LlmProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CustomHttpLlmClient {

    private static final Logger log = LoggerFactory.getLogger(CustomHttpLlmClient.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OutboundUrlValidator outboundUrlValidator;

    public CustomHttpLlmClient(OutboundUrlValidator outboundUrlValidator) {
        this.outboundUrlValidator = outboundUrlValidator;
    }

    public OpenAiCompatibleClient.ProbeResult probe(String endpointUrl, String customConfigJson, int timeoutMs) {
        try {
            CustomConfig config = parseConfig(customConfigJson, endpointUrl);
            String url = config.endpointUrl();
            if (url == null || url.isBlank()) {
                return OpenAiCompatibleClient.ProbeResult.fail("自定义接口地址 (Endpoint URL) 不能为空");
            }
            outboundUrlValidator.validateProviderBaseUrl(url);

            String testPrompt = "测试连通性";
            String requestBody = buildRequestBody(config.bodyTemplate(), testPrompt, testPrompt, "你是一个AI助手");

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(3000, timeoutMs)));

            applyHeaders(builder, config.headers());

            if ("GET".equalsIgnoreCase(config.httpMethod())) {
                builder.GET();
            } else {
                builder.POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String responseBody = response.body();

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return OpenAiCompatibleClient.ProbeResult.fail("HTTP 状态码异常: " + response.statusCode() + " " + truncate(responseBody));
            }

            JsonNode root = objectMapper.readTree(responseBody);
            validateBusinessCode(root, config);

            String extractedResult = extractResultText(root, config.resultPath());
            if (extractedResult == null || extractedResult.isBlank()) {
                return OpenAiCompatibleClient.ProbeResult.fail("未在路径 [" + config.resultPath() + "] 中提取到有效文本，响应内容: " + truncate(responseBody));
            }

            String modelName = config.modelName() != null && !config.modelName().isBlank()
                    ? config.modelName().trim()
                    : "custom-model";
            return OpenAiCompatibleClient.ProbeResult.ok("连通测试通过！成功返回: " + truncate(extractedResult), List.of(modelName));
        } catch (IllegalArgumentException e) {
            return OpenAiCompatibleClient.ProbeResult.fail(e.getMessage());
        } catch (Exception e) {
            log.warn("Custom HTTP provider probe failed: {}", e.getMessage());
            return OpenAiCompatibleClient.ProbeResult.fail("测试失败: " + e.getMessage());
        }
    }

    public OpenAiCompatibleClient.ChatResult chat(LlmProvider provider,
                                                  List<Map<String, Object>> messages,
                                                  ChatGeneration generation,
                                                  int timeoutMs) {
        String customConfigJson = provider.getCustomConfig();
        CustomConfig config = parseConfig(customConfigJson, provider.getBaseUrl());
        String url = config.endpointUrl();
        if (url == null || url.isBlank()) {
            url = provider.getBaseUrl();
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("未配置自定义接口 URL");
        }
        outboundUrlValidator.validateProviderBaseUrl(url);

        String systemPrompt = extractSystemPrompt(messages);
        String userMessage = extractLastUserMessage(messages);
        String fullPrompt = buildFullPrompt(messages);

        try {
            String requestBody = buildRequestBody(config.bodyTemplate(), fullPrompt, userMessage, systemPrompt);

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(5000, timeoutMs)));

            applyHeaders(builder, config.headers());

            if ("GET".equalsIgnoreCase(config.httpMethod())) {
                builder.GET();
            } else {
                builder.POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String responseBody = response.body();

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " " + truncate(responseBody));
            }

            JsonNode root = objectMapper.readTree(responseBody);
            validateBusinessCode(root, config);

            String content = extractResultText(root, config.resultPath());
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("未能从响应路径 [" + config.resultPath() + "] 中提取到回答文本: " + truncate(responseBody));
            }

            int promptTokens = Math.max(1, (int) Math.round(fullPrompt.length() * 1.25));
            int completionTokens = Math.max(1, (int) Math.round(content.length() * 1.25));
            int totalTokens = promptTokens + completionTokens;

            return new OpenAiCompatibleClient.ChatResult(content, promptTokens, completionTokens, totalTokens, null);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("自定义接口调用失败: " + e.getMessage(), e);
        }
    }

    private String buildRequestBody(String bodyTemplate, String fullPrompt, String userMessage, String systemPrompt) throws Exception {
        if (bodyTemplate == null || bodyTemplate.isBlank()) {
            Map<String, Object> fallback = Map.of("prompt", fullPrompt);
            return objectMapper.writeValueAsString(fallback);
        }

        JsonNode root = objectMapper.readTree(bodyTemplate);
        interpolateNode(root, fullPrompt, userMessage, systemPrompt);
        return objectMapper.writeValueAsString(root);
    }

    private void interpolateNode(JsonNode node, String fullPrompt, String userMessage, String systemPrompt) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode val = entry.getValue();
                if (val.isTextual()) {
                    String replaced = replaceVariables(val.asText(), fullPrompt, userMessage, systemPrompt);
                    entry.setValue(new TextNode(replaced));
                } else if (val.isContainerNode()) {
                    interpolateNode(val, fullPrompt, userMessage, systemPrompt);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode val = arr.get(i);
                if (val.isTextual()) {
                    String replaced = replaceVariables(val.asText(), fullPrompt, userMessage, systemPrompt);
                    arr.set(i, new TextNode(replaced));
                } else if (val.isContainerNode()) {
                    interpolateNode(val, fullPrompt, userMessage, systemPrompt);
                }
            }
        }
    }

    private String replaceVariables(String text, String fullPrompt, String userMessage, String systemPrompt) {
        if (text == null) return "";
        return text.replace("{{prompt}}", fullPrompt != null ? fullPrompt : "")
                .replace("{{user_message}}", userMessage != null ? userMessage : "")
                .replace("{{system_prompt}}", systemPrompt != null ? systemPrompt : "");
    }

    private void applyHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        boolean hasContentType = false;
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null) {
                    if ("content-type".equalsIgnoreCase(entry.getKey())) {
                        hasContentType = true;
                    }
                    builder.header(entry.getKey().trim(), entry.getValue().trim());
                }
            }
        }
        if (!hasContentType) {
            builder.header("Content-Type", "application/json;charset=utf-8");
        }
    }

    private void validateBusinessCode(JsonNode root, CustomConfig config) {
        String codePath = config.errorCodePath() != null && !config.errorCodePath().isBlank()
                ? config.errorCodePath()
                : "code";
        JsonNode codeNode = resolvePath(root, codePath);
        if (codeNode != null && !codeNode.isMissingNode() && !codeNode.isNull()) {
            int successCode = config.successCode() != null ? config.successCode() : 200;
            int actualCode = codeNode.asInt(-9999);
            if (actualCode != successCode) {
                String errMsg = extractResultText(root, config.errorMessagePath());
                if (errMsg == null || errMsg.isBlank()) {
                    errMsg = "业务返回状态码 " + actualCode;
                }
                throw new IllegalStateException("第三方大模型返回业务错误 (code=" + actualCode + "): " + errMsg);
            }
        }
    }

    private String extractResultText(JsonNode root, String path) {
        if (root == null) return null;
        String effectivePath = (path != null && !path.isBlank()) ? path : "data.result";
        JsonNode target = resolvePath(root, effectivePath);
        if (target != null && !target.isMissingNode() && !target.isNull()) {
            if (target.isTextual()) {
                return target.asText();
            }
            return target.toString();
        }
        return null;
    }

    private JsonNode resolvePath(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) return null;
        String cleanPath = path.trim().replace("/", ".");
        if (cleanPath.startsWith(".")) cleanPath = cleanPath.substring(1);
        String[] parts = cleanPath.split("\\.");
        JsonNode curr = root;
        for (String part : parts) {
            if (curr == null || curr.isMissingNode() || curr.isNull()) return null;
            if (part.matches("\\d+")) {
                curr = curr.path(Integer.parseInt(part));
            } else {
                curr = curr.path(part);
            }
        }
        return curr;
    }

    private String extractSystemPrompt(List<Map<String, Object>> messages) {
        if (messages == null) return "";
        for (Map<String, Object> m : messages) {
            if ("system".equalsIgnoreCase(String.valueOf(m.get("role")))) {
                return String.valueOf(m.get("content"));
            }
        }
        return "";
    }

    private String extractLastUserMessage(List<Map<String, Object>> messages) {
        if (messages == null) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> m = messages.get(i);
            if ("user".equalsIgnoreCase(String.valueOf(m.get("role")))) {
                return String.valueOf(m.get("content"));
            }
        }
        return "";
    }

    private String buildFullPrompt(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        String systemPrompt = extractSystemPrompt(messages);
        if (!systemPrompt.isBlank()) {
            sb.append(systemPrompt.trim()).append("\n\n");
        }

        int count = 0;
        for (Map<String, Object> m : messages) {
            String role = String.valueOf(m.get("role"));
            if ("system".equalsIgnoreCase(role)) continue;
            String content = String.valueOf(m.get("content"));
            if ("user".equalsIgnoreCase(role)) {
                sb.append("用户: ").append(content).append("\n");
                count++;
            } else if ("assistant".equalsIgnoreCase(role)) {
                sb.append("助手: ").append(content).append("\n");
                count++;
            }
        }

        if (count == 1 && systemPrompt.isBlank()) {
            // 如果仅有单条用户消息且无系统提示词，直接返回该消息
            return extractLastUserMessage(messages);
        }
        return sb.toString().trim();
    }

    private CustomConfig parseConfig(String customConfigJson, String fallbackUrl) {
        String endpoint = fallbackUrl;
        String method = "POST";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json;charset=utf-8");
        String bodyTemplate = "{\n  \"prompt\": \"{{prompt}}\"\n}";
        Integer successCode = 200;
        String resultPath = "data.result";
        String errorCodePath = "code";
        String errorMessagePath = "msg";
        String modelName = "custom-model";

        if (customConfigJson != null && !customConfigJson.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(customConfigJson);
                if (root.hasNonNull("endpointUrl") && !root.path("endpointUrl").asText().isBlank()) {
                    endpoint = root.path("endpointUrl").asText().trim();
                }
                if (root.hasNonNull("httpMethod") && !root.path("httpMethod").asText().isBlank()) {
                    method = root.path("httpMethod").asText().trim().toUpperCase();
                }
                if (root.hasNonNull("headers")) {
                    headers = objectMapper.convertValue(root.path("headers"), new TypeReference<Map<String, String>>() {});
                }
                if (root.hasNonNull("bodyTemplate") && !root.path("bodyTemplate").asText().isBlank()) {
                    bodyTemplate = root.path("bodyTemplate").asText();
                }
                if (root.hasNonNull("successCode")) {
                    successCode = root.path("successCode").asInt(200);
                }
                if (root.hasNonNull("resultPath") && !root.path("resultPath").asText().isBlank()) {
                    resultPath = root.path("resultPath").asText().trim();
                }
                if (root.hasNonNull("errorCodePath") && !root.path("errorCodePath").asText().isBlank()) {
                    errorCodePath = root.path("errorCodePath").asText().trim();
                }
                if (root.hasNonNull("errorMessagePath") && !root.path("errorMessagePath").asText().isBlank()) {
                    errorMessagePath = root.path("errorMessagePath").asText().trim();
                }
                if (root.hasNonNull("modelName") && !root.path("modelName").asText().isBlank()) {
                    modelName = root.path("modelName").asText().trim();
                }
            } catch (Exception e) {
                log.warn("Failed to parse custom config JSON: {}", e.getMessage());
            }
        }

        return new CustomConfig(endpoint, method, headers, bodyTemplate, successCode, resultPath, errorCodePath, errorMessagePath, modelName);
    }

    private static String truncate(String text) {
        if (text == null) return "";
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() > 200 ? compact.substring(0, 200) + "..." : compact;
    }

    public record CustomConfig(
            String endpointUrl,
            String httpMethod,
            Map<String, String> headers,
            String bodyTemplate,
            Integer successCode,
            String resultPath,
            String errorCodePath,
            String errorMessagePath,
            String modelName
    ) {}
}
