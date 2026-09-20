package com.example.agentplatform.tool;

import com.example.agentplatform.config.OutboundUrlValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AgentToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentToolRegistry.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    private final ThreadLocal<String> currentContextBochaApiKey = new ThreadLocal<>();
    private final ThreadLocal<Map<String, Map<String, Object>>> currentPlatformConfigs = new ThreadLocal<>();
    private final ThreadLocal<List<HttpToolSpec>> currentHttpTools = new ThreadLocal<>();
    private final String configuredBochaApiKey;
    private final OutboundUrlValidator urlValidator;

    public AgentToolRegistry(@Value("${app.bocha.api-key:}") String configuredBochaApiKey,
                             OutboundUrlValidator urlValidator) {
        this.configuredBochaApiKey = configuredBochaApiKey == null ? "" : configuredBochaApiKey.trim();
        this.urlValidator = urlValidator;
    }

    public void setCurrentContextBochaApiKey(String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            currentContextBochaApiKey.set(apiKey.trim());
        } else {
            currentContextBochaApiKey.remove();
        }
    }

    public void setPlatformConfigs(Map<String, Map<String, Object>> configs) {
        if (configs == null || configs.isEmpty()) {
            currentPlatformConfigs.remove();
        } else {
            currentPlatformConfigs.set(configs);
        }
    }

    public void setHttpTools(List<HttpToolSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            currentHttpTools.remove();
        } else {
            currentHttpTools.set(specs);
        }
    }

    public void clearCurrentContext() {
        currentContextBochaApiKey.remove();
        currentPlatformConfigs.remove();
        currentHttpTools.remove();
    }

    public List<Map<String, Object>> getToolDefinitions(List<String> enabledToolNames) {
        List<Map<String, Object>> list = new ArrayList<>();

        addIfEnabled(list, enabledToolNames, "获取当前时间", "time_get_current_time",
                "获取当前系统的精确日期和时间（包含年月日、当前多少号、时分秒以及星期几）。当用户询问“今天多少号”、“今天几号”、“现在几点”、“当前时间”、“今天日期”等任何当前时钟与日期问题时必须调用本工具。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "timezone", Map.of(
                                        "type", "string",
                                        "description", "目标时区ID，默认 Asia/Shanghai (北京时间)。若用户未特别指定其他城市时区，请直接填 Asia/Shanghai",
                                        "default", "Asia/Shanghai"
                                )
                        )
                ));

        addIfEnabled(list, enabledToolNames, "星期几计算器", "time_calculate_weekday",
                "计算历史上或未来的某个具体日期是星期几。当用户询问某一天（例如2028-08-08、明天、下周五等转换为YYYY-MM-DD后）是周几或星期几时调用。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "date", Map.of(
                                        "type", "string",
                                        "description", "目标具体日期，格式为 YYYY-MM-DD，例如 2028-08-08"
                                )
                        ),
                        "required", List.of("date")
                ));

        addIfEnabled(list, enabledToolNames, "时区转换", "time_convert_timezone",
                "将指定时间在不同时区之间进行换算转换。例如将北京时间转换为纽约时间、东京时间或伦敦时间。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "datetime", Map.of(
                                        "type", "string",
                                        "description", "源时间字符串，格式 yyyy-MM-dd HH:mm:ss"
                                ),
                                "from_timezone", Map.of(
                                        "type", "string",
                                        "description", "源时区，默认 Asia/Shanghai"
                                ),
                                "to_timezone", Map.of(
                                        "type", "string",
                                        "description", "目标时区，如 America/New_York, Europe/London, Asia/Tokyo"
                                )
                        ),
                        "required", List.of("datetime", "to_timezone")
                ));

        addIfEnabled(list, enabledToolNames, "时间戳转换", "time_timestamp_converter",
                "Unix时间戳与格式化时间字符串之间的相互转换。可将秒级/毫秒级时间戳转为日期时间，或将日期时间转为时间戳。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "timestamp", Map.of(
                                        "type", "number",
                                        "description", "秒级(10位)或毫秒级(13位)Unix时间戳，将其转为日期时间字符串"
                                ),
                                "datetime", Map.of(
                                        "type", "string",
                                        "description", "格式化日期时间字符串（yyyy-MM-dd HH:mm:ss），将其转为Unix时间戳"
                                )
                        )
                ));

        addIfEnabled(list, enabledToolNames, "获取时间戳", "time_date_calculator",
                "计算两个日期之间相隔的天数，或者计算基准日期增加/减少若干天后的新日期。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "base_date", Map.of(
                                        "type", "string",
                                        "description", "基准日期，格式 yyyy-MM-dd，留空则默认为今天"
                                ),
                                "days_offset", Map.of(
                                        "type", "integer",
                                        "description", "偏移天数，正数表示往后推算，负数表示往前推算（例如 10 表示 10 天后，-5 表示 5 天前）"
                                ),
                                "target_date", Map.of(
                                        "type", "string",
                                        "description", "目标日期，格式 yyyy-MM-dd。若提供则计算 base_date 与 target_date 之间相隔天数"
                                )
                        )
                ));

        addIfEnabled(list, enabledToolNames, "联网检索", "bocha_web_search",
                "博查 AI 联网搜索引擎。当用户询问最新时事、实时天气、新闻事件、实时数据或任何需要获取最新互联网真实信息的场景时，必须调用本工具检索权威事实。注意：严禁用本工具查询“今天几号”、“现在几点”等系统时钟问题（当前日期时间请直接使用系统基准时间或获取当前时间工具）。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "query", Map.of(
                                        "type", "string",
                                        "description", "搜索关键词或查询短句，例如：深圳明天天气预报、最新国际新闻等"
                                )
                        ),
                        "required", List.of("query")
                ));

        List<HttpToolSpec> httpTools = currentHttpTools.get();
        if (httpTools != null) {
            for (HttpToolSpec spec : httpTools) {
                if (spec.getCode() == null || spec.getName() == null) {
                    continue;
                }
                addIfEnabled(list, enabledToolNames, spec.getName(), spec.getCode(),
                        spec.getDescription() != null ? spec.getDescription() : spec.getName(),
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "query", Map.of(
                                                "type", "string",
                                                "description", "调用该自定义工具时需要传入的查询内容或业务参数"
                                        )
                                ),
                                "required", List.of("query")
                        ));
            }
        }

        return list;
    }

    private void addIfEnabled(List<Map<String, Object>> list, List<String> enabledToolNames,
                              String uiName, String functionName, String description, Map<String, Object> parameters) {
        if (enabledToolNames != null && !enabledToolNames.isEmpty()) {
            boolean matched = enabledToolNames.stream().anyMatch(name ->
                    name.equalsIgnoreCase(uiName)
                            || name.equalsIgnoreCase(functionName)
                            || uiName.contains(name)
                            || name.contains(uiName));
            if (!matched) {
                return;
            }
        }

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", functionName);
        fn.put("description", description);
        fn.put("parameters", parameters);
        tool.put("function", fn);
        list.add(tool);
    }

    public String execute(String toolName, String argumentsJson) {
        try {
            JsonNode root = (argumentsJson != null && !argumentsJson.isBlank())
                    ? objectMapper.readTree(argumentsJson)
                    : objectMapper.createObjectNode();

            return switch (toolName) {
                case "time_get_current_time", "get_current_time" -> handleGetCurrentTime(root);
                case "time_calculate_weekday", "calculate_weekday" -> handleCalculateWeekday(root);
                case "time_convert_timezone", "convert_timezone" -> handleConvertTimezone(root);
                case "time_timestamp_converter", "timestamp_converter" -> handleTimestampConverter(root);
                case "time_date_calculator", "date_calculator" -> handleDateCalculator(root);
                case "bocha_web_search", "web_search" -> handleBochaWebSearch(root);
                default -> handleCustomOrUnknown(toolName, root);
            };
        } catch (Exception e) {
            log.error("工具执行异常 [{}]: {}", toolName, e.getMessage(), e);
            return "工具执行失败: " + e.getMessage();
        }
    }

    public String formatToolCallSummary(String toolName, String argumentsJson) {
        String cleanName = toolName.replace('_', '.');
        try {
            JsonNode node = objectMapper.readTree(argumentsJson);
            if (node.isObject() && node.size() > 0) {
                List<String> parts = new ArrayList<>();
                node.fields().forEachRemaining(e -> parts.add(e.getKey() + "=" + e.getValue().asText()));
                return cleanName + " (" + String.join(", ", parts) + ")";
            }
        } catch (Exception ignored) {
        }
        return cleanName;
    }

    private String handleGetCurrentTime(JsonNode root) {
        String tz = firstText(root, "timezone", platformText("time_get_current_time", "timezone", "Asia/Shanghai"));
        ZoneId zone = safeZone(tz);
        tz = zone.getId();
        ZonedDateTime now = ZonedDateTime.now(zone);
        String formatted = now.format(dateTimeFormatter(platformText("time_get_current_time", "format", "yyyy-MM-dd HH:mm:ss")));
        String weekday = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINESE);
        return String.format("当前系统时间查询成功：【%s】时间为 %s (%s, 时区偏移: %s)",
                tz, formatted, weekday, now.getOffset().getId());
    }

    private String handleCalculateWeekday(JsonNode root) {
        String dateStr = root.path("date").asText();
        if (dateStr == null || dateStr.isBlank()) {
            dateStr = LocalDate.now().toString();
        }
        LocalDate date = LocalDate.parse(dateStr.trim().replace("/", "-"));
        String weekday = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINESE);
        return String.format("星期计算成功：%s 是 %s", dateStr, weekday);
    }

    private String handleConvertTimezone(JsonNode root) {
        String dtStr = root.path("datetime").asText();
        String fromTz = firstText(root, "from_timezone", platformText("time_convert_timezone", "timezone", "Asia/Shanghai"));
        String toTz = root.path("to_timezone").asText("America/New_York");
        DateTimeFormatter fmt = dateTimeFormatter(platformText("time_convert_timezone", "format", "yyyy-MM-dd HH:mm:ss"));
        LocalDateTime ldt = LocalDateTime.parse(dtStr.replace("T", " ").trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        ZonedDateTime srcZdt = ldt.atZone(ZoneId.of(fromTz));
        ZonedDateTime dstZdt = srcZdt.withZoneSameInstant(ZoneId.of(toTz));
        return String.format("时区换算成功：原时间 %s (%s) 对应 %s 的时间为：%s (%s)",
                dtStr, fromTz, toTz, dstZdt.format(fmt), dstZdt.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINESE));
    }

    private String handleTimestampConverter(JsonNode root) {
        if (root.has("timestamp") && !root.path("timestamp").isNull()) {
            long ts = root.path("timestamp").asLong();
            Instant instant = ts > 9999999999L ? Instant.ofEpochMilli(ts) : Instant.ofEpochSecond(ts);
            ZoneId zone = safeZone(platformText("time_timestamp_converter", "timezone", "Asia/Shanghai"));
            ZonedDateTime zdt = instant.atZone(zone);
            return String.format("时间戳转换成功：Unix时间戳 %d 对应的 %s 时间为：%s",
                    ts, zone.getId(), zdt.format(dateTimeFormatter(platformText("time_timestamp_converter", "format", "yyyy-MM-dd HH:mm:ss"))));
        } else if (root.has("datetime")) {
            String dt = root.path("datetime").asText();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime ldt = LocalDateTime.parse(dt.replace("T", " "), fmt);
            long epochSec = ldt.atZone(safeZone(platformText("time_timestamp_converter", "timezone", "Asia/Shanghai"))).toEpochSecond();
            return String.format("时间转换成功：%s 对应的Unix时间戳为：%d (秒级) / %d (毫秒级)",
                    dt, epochSec, epochSec * 1000L);
        }
        return "时间戳转换错误：请提供 timestamp 或 datetime 参数";
    }

    private String handleDateCalculator(JsonNode root) {
        LocalDate base = root.has("base_date") && !root.path("base_date").asText().isBlank()
                ? LocalDate.parse(root.path("base_date").asText().trim().replace("/", "-"))
                : LocalDate.now();

        if (root.has("target_date") && !root.path("target_date").asText().isBlank()) {
            LocalDate target = LocalDate.parse(root.path("target_date").asText().trim().replace("/", "-"));
            long days = ChronoUnit.DAYS.between(base, target);
            return String.format("日期跨度计算成功：%s 与 %s 之间相差 %d 天", base, target, Math.abs(days));
        } else if (root.has("days_offset")) {
            long offset = root.path("days_offset").asLong();
            LocalDate res = base.plusDays(offset);
            String weekday = res.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINESE);
            return String.format("日期推算成功：%s 偏移 %d 天后的日期为：%s (%s)",
                    base, offset, res, weekday);
        }
        return String.format("当前日期基准：%s (星期 %s)", base, base.getDayOfWeek().getValue());
    }

    private String handleBochaWebSearch(JsonNode root) {
        String query = root.path("query").asText("");
        String apiKey = root.path("apiKey").asText("");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = currentContextBochaApiKey.get();
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = configuredBochaApiKey;
        }
        if (apiKey == null || apiKey.isBlank()) {
            return String.format("【联网检索插件】已触发 Bocha Web Search，检索关键词: [%s]。提示：尚未检测到有效的 Bocha API Key，请到左侧「工具管理」配置联网检索的平台密钥。", query);
        }
        if (apiKey.equalsIgnoreCase("mock") || apiKey.startsWith("test-mock") || apiKey.startsWith("demo-")) {
            return String.format("【联网检索结果 (模拟通道)】针对关键词 [%s] 获取到最新权威信息：\n" +
                    "1. 标题: 深圳市气象台最新天气预报与气象灾害预警\n" +
                    "   摘要: 深圳明天白天多云转阴天，有阵雨或局部中雨，气温24℃-31℃，偏南风2-3级，相对湿度70%%-95%%，外出建议携带雨具。\n" +
                    "   来源: 深圳市气象局官网 (http://weather.sz.gov.cn)\n" +
                    "2. 标题: 华南主要城市近期天气与降水展望\n" +
                    "   摘要: 华南沿海受偏南暖湿气流影响，局地对流活跃，早晚温差适宜，午后雷阵雨概率较高。\n" +
                    "   来源: 中国天气网", query);
        }
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "query", query,
                    "count", platformInt("bocha_web_search", "count", 5),
                    "freshness", platformText("bocha_web_search", "freshness", "noLimit"),
                    "summary", platformBoolean("bocha_web_search", "summary", true)
            ));
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.bochaai.com/v1/web-search"))
                    .timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return parseBochaResponse(query, response.body());
            } else {
                return String.format("【联网检索提示】Bocha API 返回状态码 %d: %s", response.statusCode(), truncate(response.body(), 200));
            }
        } catch (Exception e) {
            log.error("Bocha web search 异常 [query={}]: {}", query, e.getMessage(), e);
            return String.format("【联网检索异常】请求 Bocha 接口失败: %s", e.getMessage());
        }
    }

    private String parseBochaResponse(String query, String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode values = root.path("data").path("webPages").path("value");
            if (values.isArray() && !values.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
                String todayStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                sb.append(String.format("【联网检索结果】（当前物理世界基准日期：%s）针对关键词 [%s]，Bocha 检索到以下最新互联网资讯（提示：列表中发布时间为各网页的历史发布时刻，切勿误当做今天的真实日期）：\n\n", todayStr, query));
                int idx = 1;
                for (JsonNode item : values) {
                    String title = item.path("name").asText("");
                    String summary = item.hasNonNull("summary") ? item.path("summary").asText("") : item.path("snippet").asText("");
                    String url = item.path("url").asText("");
                    String siteName = item.path("siteName").asText("");
                    String date = item.path("datePublished").asText("");

                    sb.append(idx++).append(". ").append(title.isBlank() ? "互联网网页资讯" : title);
                    if (!siteName.isBlank()) {
                        sb.append("（来源: ").append(siteName).append("）");
                    }
                    if (!date.isBlank()) {
                        String shortDate = date.length() > 10 ? date.substring(0, 10) : date;
                        sb.append(" [").append(shortDate).append("]");
                    }
                    sb.append("\n");
                    if (!summary.isBlank()) {
                        sb.append("   摘要内容: ").append(summary.trim()).append("\n");
                    }
                    if (!url.isBlank()) {
                        sb.append("   网页参考: ").append(url.trim()).append("\n");
                    }
                    sb.append("\n");
                    if (idx > 5) break;
                }
                return sb.toString().trim();
            }

            if (root.has("data") && root.get("data").isTextual()) {
                return String.format("【联网检索结果】针对关键词 [%s]：\n%s", query, root.get("data").asText());
            }
            return String.format("【联网检索完成】未检索到关于 [%s] 的详细网页记录。", query);
        } catch (Exception e) {
            log.warn("解析 Bocha 响应数据异常: {}", e.getMessage());
            return String.format("【联网检索数据已返回】针对关键词 [%s]，原始内容如下：\n%s", query, truncate(responseBody, 800));
        }
    }

    public Map<String, Object> testBochaConnection(String customApiKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        String effectiveKey = (customApiKey != null && !customApiKey.isBlank())
                ? customApiKey.trim()
                : currentContextBochaApiKey.get();

        if (effectiveKey == null || effectiveKey.isBlank()) {
            effectiveKey = configuredBochaApiKey;
        }

        if (effectiveKey == null || effectiveKey.isBlank()) {
            result.put("success", false);
            result.put("message", "测试未通过：尚未填写 API Key，且系统环境变量 BOCHA_API_KEY 未配置。");
            result.put("code", 400);
            return result;
        }

        if (effectiveKey.equalsIgnoreCase("mock") || effectiveKey.startsWith("test-mock") || effectiveKey.startsWith("demo-")) {
            result.put("success", true);
            result.put("message", "【模拟测试通过】Bocha API 密钥有效，模拟通道响应正常！");
            result.put("latencyMs", 68L);
            result.put("code", 200);
            return result;
        }

        long start = System.currentTimeMillis();
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "query", "ping test",
                    "count", 1,
                    "freshness", "noLimit",
                    "summary", false
            ));

            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.bochaai.com/v1/web-search"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + effectiveKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long latency = System.currentTimeMillis() - start;
            result.put("latencyMs", latency);
            result.put("code", response.statusCode());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                result.put("success", true);
                result.put("message", String.format("连接成功！Bocha 搜索服务可用，响应耗时 %d ms", latency));
            } else if (response.statusCode() == 401 || response.statusCode() == 403) {
                result.put("success", false);
                result.put("message", String.format("鉴权失败 (HTTP %d)：API Key 无效或未授权，请检查填写的密钥", response.statusCode()));
            } else {
                result.put("success", false);
                result.put("message", String.format("连接异常 (HTTP %d)：%s", response.statusCode(), truncate(response.body(), 120)));
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            result.put("success", false);
            result.put("latencyMs", latency);
            result.put("message", "网络连接失败：" + e.getMessage());
        }
        return result;
    }

    private String handleCustomOrUnknown(String toolName, JsonNode root) {
        HttpToolSpec spec = findHttpTool(toolName);
        if (spec != null) {
            return handleHttpTool(spec, root.path("query").asText(""));
        }
        return "未知工具: " + toolName;
    }

    private HttpToolSpec findHttpTool(String toolName) {
        List<HttpToolSpec> specs = currentHttpTools.get();
        if (specs == null || toolName == null) {
            return null;
        }
        return specs.stream()
                .filter(spec -> toolName.equalsIgnoreCase(spec.getCode()) || toolName.equalsIgnoreCase(spec.getName()))
                .findFirst()
                .orElse(null);
    }

    public Map<String, Object> testHttpTool(HttpToolSpec spec) {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            String body = handleHttpTool(spec, "ping");
            result.put("success", body != null && !body.startsWith("【自定义工具失败】"));
            result.put("message", result.get("success").equals(true) ? "自定义工具接口可访问" : body);
            result.put("latencyMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            result.put("latencyMs", System.currentTimeMillis() - start);
        }
        return result;
    }

    private String handleHttpTool(HttpToolSpec spec, String query) {
        if (spec.getUrl() == null || spec.getUrl().isBlank()) {
            return "【自定义工具失败】未配置 HTTP 接口地址";
        }
        if (urlValidator != null) {
            urlValidator.validateProviderBaseUrl(spec.getUrl());
        }
        String method = spec.getMethod() == null || spec.getMethod().isBlank() ? "POST" : spec.getMethod().trim().toUpperCase(Locale.ROOT);
        String url = spec.getUrl().replace("{{query}}", query == null ? "" : query);
        int timeout = spec.getTimeoutSeconds() > 0 ? spec.getTimeoutSeconds() : 8;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout));
            if (spec.getApiKey() != null && !spec.getApiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + spec.getApiKey());
            }
            if ("GET".equals(method)) {
                if (!url.contains("{{query}}") && query != null && !query.isBlank() && !url.contains("query=")) {
                    String sep = url.contains("?") ? "&" : "?";
                    builder.uri(URI.create(url + sep + "query=" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8)));
                }
                builder.GET();
            } else {
                String payload = objectMapper.writeValueAsString(Map.of("query", query == null ? "" : query));
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return String.format("【自定义工具 %s】调用成功：\n%s", spec.getName(), truncate(response.body(), 1200));
            }
            return String.format("【自定义工具失败】HTTP %d：%s", response.statusCode(), truncate(response.body(), 240));
        } catch (IllegalArgumentException e) {
            return "【自定义工具失败】" + e.getMessage();
        } catch (Exception e) {
            log.error("自定义 HTTP 工具异常 [{}]: {}", spec.getCode(), e.getMessage(), e);
            return "【自定义工具失败】" + e.getMessage();
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private String firstText(JsonNode root, String field, String fallback) {
        String value = root.path(field).asText("");
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private String platformText(String toolCode, String key, String fallback) {
        Map<String, Map<String, Object>> configs = currentPlatformConfigs.get();
        if (configs == null) {
            return fallback;
        }
        Map<String, Object> config = configs.get(toolCode);
        if (config == null || config.get(key) == null) {
            return fallback;
        }
        String value = String.valueOf(config.get(key)).trim();
        return value.isBlank() ? fallback : value;
    }

    private int platformInt(String toolCode, String key, int fallback) {
        try {
            return Integer.parseInt(platformText(toolCode, key, String.valueOf(fallback)));
        } catch (Exception e) {
            return fallback;
        }
    }

    private boolean platformBoolean(String toolCode, String key, boolean fallback) {
        String value = platformText(toolCode, key, String.valueOf(fallback));
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private ZoneId safeZone(String tz) {
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private DateTimeFormatter dateTimeFormatter(String pattern) {
        if (pattern == null || pattern.isBlank() || "ISO-8601".equalsIgnoreCase(pattern)) {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        }
        try {
            return DateTimeFormatter.ofPattern(pattern);
        } catch (Exception e) {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        }
    }
}
