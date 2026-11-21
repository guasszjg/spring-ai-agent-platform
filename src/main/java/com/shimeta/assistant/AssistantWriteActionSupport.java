package com.shimeta.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shimeta.model.AssistantAction;
import com.shimeta.repository.AssistantActionRepository;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 写工具公共框架支撑（4.4 写工具框架化）：
 * 统一封装 A～D 级待确认操作卡片生成、D 级规则护栏（5 分钟 TTL、每轮最多 1 张高危卡片、禁止 pendingActionId 串联）、
 * 预览构建（fields / diff / diffs / impacts / confirmTargetName / secretInput）以及乐观锁基线哈希。
 */
final class AssistantWriteActionSupport {

    static final int STANDARD_TTL_MINUTES = 10;
    static final int HIGH_RISK_TTL_MINUTES = 5;
    static final Pattern ACTION_REF = Pattern.compile("^act-[0-9a-f]{8,32}$");

    private final AssistantActionRepository actionRepository;
    private final ObjectMapper objectMapper;

    AssistantWriteActionSupport(AssistantActionRepository actionRepository, ObjectMapper objectMapper) {
        this.actionRepository = actionRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 统一保存待确认操作卡片，并返回给模型的标准结果。
     * <ul>
     *   <li>D 级高危卡片强制校验：每轮最多 1 张；payload 中不得引用 act-* 未确认卡片；TTL 为 5 分钟；必须带 confirmTargetName。</li>
     *   <li>A / B / C 级卡片 TTL 为 10 分钟。</li>
     * </ul>
     */
    Map<String, Object> createPendingCard(ToolContext ctx,
                                          String toolName,
                                          String riskLevel,
                                          String title,
                                          Map<String, Object> payload,
                                          Map<String, Object> preview,
                                          String resourceType) {
        boolean highRisk = "D".equalsIgnoreCase(riskLevel);
        if (highRisk) {
            boolean alreadyHasHighRisk = ctx.createdActions().stream()
                    .anyMatch(a -> "D".equalsIgnoreCase(a.getRiskLevel()));
            if (alreadyHasHighRisk) {
                throw new IllegalArgumentException("每轮对话最多只能生成 1 张高危（D 级）操作卡片，请先处理当前卡片后再发起下一项高危操作");
            }
            ensureNoActionRefInPayload(payload);
            Object targetName = preview != null ? preview.get("confirmTargetName") : null;
            if (!(targetName instanceof String s) || s.isBlank()) {
                throw new IllegalStateException("高危卡片必须指定 confirmTargetName");
            }
        }
        int ttl = highRisk ? HIGH_RISK_TTL_MINUTES : STANDARD_TTL_MINUTES;

        AssistantAction action = new AssistantAction();
        action.setConversationId(ctx.conversationId());
        action.setUserId(ctx.actor().getUserId());
        action.setToolName(toolName);
        action.setRiskLevel(riskLevel);
        action.setTitle(title.length() > 200 ? title.substring(0, 199) + "…" : title);
        action.setPayload(toJson(payload));
        action.setPreview(toJson(preview));
        action.setStatus(AssistantAction.PENDING);
        action.setResourceType(resourceType);
        action.setExpiresAt(LocalDateTime.now().plusMinutes(ttl));
        action = actionRepository.save(action);
        ctx.createdActions().add(action);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("pendingActionId", action.getId());
        res.put("riskLevel", riskLevel);
        res.put("title", action.getTitle());
        if (highRisk) {
            res.put("confirmTargetName", preview.get("confirmTargetName"));
            res.put("message", "已生成高危（D 级）待确认操作卡片（" + ttl + " 分钟内有效）。请提醒用户在卡片中核对影响范围、手动输入「"
                    + preview.get("confirmTargetName") + "」"
                    + (preview.containsKey("secretInput") ? "并在卡片密码框中填写密钥/密码" : "")
                    + "后点击确认；用户确认前绝不要声称操作已完成。");
        } else {
            res.put("message", "已生成待确认操作卡片（" + ttl + " 分钟内有效）。请提醒用户在卡片中核对内容并点击确认；用户确认前不要声称操作已完成。");
        }
        return res;
    }

    private static void ensureNoActionRefInPayload(Object value) {
        if (value instanceof String s && ACTION_REF.matcher(s.trim()).matches()) {
            throw new IllegalArgumentException("高危（D 级）操作卡片不能依赖其他未确认的卡片（不支持 pendingActionId 串联），请先确认前置卡片后再发起");
        }
        if (value instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                ensureNoActionRefInPayload(v);
            }
        } else if (value instanceof Iterable<?> it) {
            for (Object v : it) {
                ensureNoActionRefInPayload(v);
            }
        }
    }

    static String sha256(String text) {
        return PlatformDocsService.sha256((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, Object> field(String label, String value) {
        return Map.of("label", label, "value", value == null ? "" : value);
    }

    static Map<String, Object> longField(String label, String value) {
        return Map.of("label", label, "value", value == null ? "" : value, "multiline", true);
    }

    static Map<String, Object> diff(String label, String before, String after) {
        return Map.of("label", label, "before", before == null ? "" : before, "after", after == null ? "" : after);
    }

    static Map<String, Object> secretInput(String label, String placeholder, boolean required, int minLength) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("placeholder", placeholder);
        m.put("required", required);
        if (minLength > 0) {
            m.put("minLength", minLength);
        }
        return m;
    }

    static PreviewBuilder previewBuilder() {
        return new PreviewBuilder();
    }

    static final class PreviewBuilder {
        private final List<Map<String, Object>> fields = new ArrayList<>();
        private final List<Map<String, Object>> diffs = new ArrayList<>();
        private Map<String, Object> singleDiff;
        private final List<String> impacts = new ArrayList<>();
        private String confirmTargetName;
        private Map<String, Object> secretInput;
        private String note;

        PreviewBuilder addField(String label, String value) {
            fields.add(field(label, value));
            return this;
        }

        PreviewBuilder addLongField(String label, String value) {
            fields.add(longField(label, value));
            return this;
        }

        PreviewBuilder setDiff(String label, String before, String after) {
            this.singleDiff = diff(label, before, after);
            return this;
        }

        PreviewBuilder addDiff(String label, String before, String after) {
            diffs.add(diff(label, before, after));
            return this;
        }

        PreviewBuilder addImpact(String impact) {
            if (impact != null && !impact.isBlank()) {
                impacts.add(impact);
            }
            return this;
        }

        PreviewBuilder addImpacts(List<String> list) {
            if (list != null) {
                list.forEach(this::addImpact);
            }
            return this;
        }

        PreviewBuilder confirmTargetName(String name) {
            this.confirmTargetName = name;
            return this;
        }

        PreviewBuilder secretInput(String label, String placeholder, boolean required, int minLength) {
            this.secretInput = AssistantWriteActionSupport.secretInput(label, placeholder, required, minLength);
            return this;
        }

        PreviewBuilder note(String note) {
            this.note = note;
            return this;
        }

        Map<String, Object> build() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fields", fields);
            if (singleDiff != null) {
                m.put("diff", singleDiff);
            }
            if (!diffs.isEmpty()) {
                m.put("diffs", diffs);
                // 兼容只读 diff 单项的旧调用方：仅有 1 项时同步写入 diff
                if (singleDiff == null && diffs.size() == 1) {
                    m.put("diff", diffs.get(0));
                }
            }
            if (!impacts.isEmpty()) {
                m.put("impacts", impacts);
            }
            if (confirmTargetName != null && !confirmTargetName.isBlank()) {
                m.put("confirmTargetName", confirmTargetName);
            }
            if (secretInput != null) {
                m.put("secretInput", secretInput);
            }
            if (note != null && !note.isBlank()) {
                m.put("note", note);
            }
            return m;
        }
    }

    static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        if (required.length > 0) {
            m.put("required", List.of(required));
        }
        return m;
    }

    static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    static Map<String, Object> enumProp(String description, String... values) {
        return Map.of("type", "string", "enum", List.of(values), "description", description);
    }

    static String text(JsonNode args, String field) {
        JsonNode node = args == null ? null : args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        return value.isEmpty() ? null : value;
    }

    static String requiredText(JsonNode args, String field, int max, String label) {
        String val = text(args, field);
        if (val == null) {
            throw new IllegalArgumentException("请提供" + label);
        }
        if (val.length() > max) {
            throw new IllegalArgumentException(label + "不能超过 " + max + " 字，当前 " + val.length() + " 字");
        }
        return val;
    }

    static Double number(JsonNode args, String field) {
        JsonNode node = args == null ? null : args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        try {
            return Double.parseDouble(node.asText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " 必须是数字");
        }
    }

    static Boolean bool(JsonNode args, String field) {
        JsonNode node = args == null ? null : args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        String s = node.asText("").trim().toLowerCase();
        if ("true".equals(s)) return true;
        if ("false".equals(s)) return false;
        throw new IllegalArgumentException(field + " 必须是布尔值 true/false");
    }

    static List<String> textList(JsonNode args, String field) {
        JsonNode node = args == null ? null : args.get(field);
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (node.isArray()) {
            node.forEach(item -> {
                String v = item.asText("").trim();
                if (!v.isEmpty()) {
                    values.add(v);
                }
            });
        } else if (!node.asText("").isBlank()) {
            for (String part : node.asText().split("[,，、]")) {
                if (!part.isBlank()) {
                    values.add(part.trim());
                }
            }
        }
        return values;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("操作参数序列化失败");
        }
    }
}
