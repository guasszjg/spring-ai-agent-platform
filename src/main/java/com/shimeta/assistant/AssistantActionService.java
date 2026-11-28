package com.shimeta.assistant;

import com.shimeta.model.AssistantAction;
import com.shimeta.repository.AssistantActionRepository;
import com.shimeta.security.CurrentActor;
import com.shimeta.service.AuditRecorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 待确认操作的确认、取消与展示（P2）。
 *
 * <p>确认执行的校验规则（设计文档 7.2）：操作属于当前用户；状态为 PENDING 且未过期；执行时由各 Service 以当前用户身份
 * <b>重新</b>做权限校验；同一操作只能执行一次（条件更新抢占，防重复点击）。执行不放在同一个事务里：抢占、执行、记录结果
 * 各自提交，业务执行失败不会回滚"已抢占"的状态。
 */
@Service
public class AssistantActionService {

    private static final Logger log = LoggerFactory.getLogger(AssistantActionService.class);

    private final AssistantActionRepository actionRepository;
    private final AssistantWriteTools writeTools;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private AssistantExtendedWriteTools extendedWriteTools;

    public AssistantActionService(AssistantActionRepository actionRepository,
                                  AssistantWriteTools writeTools,
                                  AuditRecorder auditRecorder) {
        this.actionRepository = actionRepository;
        this.writeTools = writeTools;
        this.auditRecorder = auditRecorder;
    }

    @Autowired(required = false)
    public void setExtendedWriteTools(AssistantExtendedWriteTools extendedWriteTools) {
        this.extendedWriteTools = extendedWriteTools;
    }

    /** 依赖的前序卡片尚未执行成功：拒绝确认，卡片保持 PENDING（不消耗）。 */
    public static class DependencyNotMetException extends IllegalStateException {
        public DependencyNotMetException(String message) {
            super(message);
        }
    }

    /** D 级高危卡片名称校验未通过或必填密码框为空：拒绝确认，卡片保持 PENDING（不消耗）。 */
    public static class ConfirmationMismatchException extends IllegalArgumentException {
        public ConfirmationMismatchException(String message) {
            super(message);
        }
    }

    public Map<String, Object> confirm(String actionId, CurrentActor actor) {
        return confirm(actionId, null, actor);
    }

    /**
     * 用户确认执行，返回最新的卡片数据，前端据此刷新卡片。
     * 执行结果中的敏感信息（如新签发的 API Key 明文）只放在本次返回的卡片 secret 字段中，不落库；
     * 请求中的密码框输入（secretValue）仅直接传给目标 Service，不落库、不进审计与对话记录。
     *
     * @throws DependencyNotMetException     依赖的前序卡片还未确认执行成功
     * @throws ConfirmationMismatchException D 级高危卡片名称未输入正确或必填密码框为空（不消耗卡片）
     */
    public Map<String, Object> confirm(String actionId, AssistantActionConfirmRequest request, CurrentActor actor) {
        AssistantAction action = load(actionId, actor);
        if (!AssistantAction.PENDING.equals(action.getStatus())) {
            return card(action);
        }
        String unmet = writeTools.unmetDependency(action, actor);
        if (unmet != null) {
            throw new DependencyNotMetException(unmet);
        }
        validateHighRiskConfirmation(action, request);
        String payload = confirmedPayload(action, request);
        if (actionRepository.claim(actionId, actor.getUserId(), LocalDateTime.now()) != 1) {
            // 并发确认、刚好过期或已被取消：以数据库中的最新状态为准
            return card(load(actionId, actor));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        String secret = null;
        Map<String, Object> uiResult = Map.of();
        try {
            AssistantWriteTools.ExecutionResult executed = (extendedWriteTools != null
                    && extendedWriteTools.supports(action.getToolName()))
                    ? extendedWriteTools.execute(action.getToolName(), payload, request, actor)
                    : writeTools.execute(action.getToolName(), payload, actor);
            action.setPayload(payload);
            action.setStatus(AssistantAction.EXECUTED);
            action.setResourceType(executed.resourceType());
            action.setResourceId(executed.resourceId());
            secret = executed.secret();
            uiResult = executed.uiResult();
            result.put("message", executed.message());
            if (executed.readback() != null && !executed.readback().isBlank()) {
                result.put("readback", executed.readback());
            }
            if (executed.linkUrl() != null) {
                result.put("link", Map.of("label", executed.linkLabel(), "url", executed.linkUrl()));
            }
            audit(action, "SUCCESS", null);
        } catch (IllegalArgumentException | IllegalStateException e) {
            action.setStatus(AssistantAction.FAILED);
            result.put("error", e.getMessage());
            audit(action, "FAILED", e.getMessage());
        } catch (Exception e) {
            log.warn("Assistant action [{}] {} failed: {}", action.getId(), action.getToolName(), e.getMessage(), e);
            action.setStatus(AssistantAction.FAILED);
            result.put("error", "执行失败，请稍后重试");
            audit(action, "FAILED", e.getMessage());
        }
        action.setResult(toJson(result));
        action.setExecutedAt(LocalDateTime.now());
        AssistantAction saved = actionRepository.save(action);
        Map<String, Object> card = card(saved);
        if (secret != null) {
            card.put("secret", secret);
        }
        Map<String, Object> uiEffect = AssistantUiEffects.fromExecutedAction(saved, uiResult);
        if (uiEffect != null) {
            card.put("uiEffect", uiEffect);
        }
        return card;
    }

    /** D 级高危卡片：校验手输资源名称（必须完全一致）与卡片密码框必填项；不通过抛出异常且不消耗卡片。 */
    private String confirmedPayload(AssistantAction action, AssistantActionConfirmRequest request) {
        if (request == null || request.formData() == null || request.formData().isNull()) {
            return action.getPayload();
        }
        JsonNode form = request.formData();
        if (!(AssistantWriteTools.ADD_FAQ.equals(action.getToolName())
                || AssistantExtendedWriteTools.UPDATE_FAQ.equals(action.getToolName())) || !form.isObject()) {
            throw new ConfirmationMismatchException("该操作不支持提交表单草稿");
        }
        if (!(readJsonNode(action.getPayload()) instanceof com.fasterxml.jackson.databind.node.ObjectNode payload)) {
            throw new ConfirmationMismatchException("操作卡片的配置无法读取");
        }
        try {
            for (var field : form.properties()) {
                String name = field.getKey();
                JsonNode value = field.getValue();
                switch (name) {
                    case "question" -> setFaqText(payload, name, value, AssistantWriteTools.QUESTION_MAX);
                    case "answer" -> setFaqText(payload, name, value, AssistantWriteTools.ANSWER_MAX);
                    case "category" -> setFaqText(payload, name, value, 100);
                    case "imageUrls", "videoUrls" -> {
                        if (!value.isArray()) throw new IllegalArgumentException("附件必须是链接列表");
                        boolean image = "imageUrls".equals(name);
                        var urls = AssistantWriteTools.parseMediaUrls(form, name,
                                image ? com.shimeta.service.KnowledgeBaseService.MAX_FAQ_IMAGES
                                        : com.shimeta.service.KnowledgeBaseService.MAX_FAQ_VIDEOS,
                                image ? "图片" : "视频");
                        payload.set(name, objectMapper.valueToTree(urls));
                    }
                    default -> throw new IllegalArgumentException("FAQ 表单包含不支持的字段");
                }
            }
        } catch (IllegalArgumentException e) {
            throw new ConfirmationMismatchException(e.getMessage());
        }
        return payload.toString();
    }

    private static void setFaqText(com.fasterxml.jackson.databind.node.ObjectNode payload, String field,
                                   JsonNode value, int maxLength) {
        if (!value.isTextual() || value.asText().isBlank() || value.asText().trim().length() > maxLength) {
            throw new IllegalArgumentException("FAQ 字段格式或长度有误：" + field);
        }
        payload.put(field, value.asText().trim());
    }

    private void validateHighRiskConfirmation(AssistantAction action, AssistantActionConfirmRequest request) {
        if (!"D".equalsIgnoreCase(action.getRiskLevel())) {
            return;
        }
        JsonNode previewNode = readJsonNode(action.getPreview());
        String expectedName = previewNode != null ? previewNode.path("confirmTargetName").asText("").trim() : "";
        if (!expectedName.isEmpty()) {
            String supplied = request != null && request.confirmName() != null ? request.confirmName().trim() : "";
            if (!expectedName.equals(supplied)) {
                throw new ConfirmationMismatchException("高危操作确认失败：请手动输入完整名称「" + expectedName + "」后再确认");
            }
        }
        JsonNode secretInput = previewNode != null ? previewNode.get("secretInput") : null;
        if (secretInput != null && secretInput.isObject()) {
            boolean required = secretInput.path("required").asBoolean(false);
            String label = secretInput.path("label").asText("密钥或密码");
            int minLength = secretInput.path("minLength").asInt(0);
            String secretVal = request != null && request.secretValue() != null ? request.secretValue().trim() : "";
            if (required && secretVal.isEmpty()) {
                throw new ConfirmationMismatchException("请在卡片输入框中填写" + label);
            }
            if (!secretVal.isEmpty() && minLength > 0 && secretVal.length() < minLength) {
                throw new ConfirmationMismatchException(label + "长度不能少于 " + minLength + " 位");
            }
        }
    }

    public Map<String, Object> cancel(String actionId, CurrentActor actor) {
        load(actionId, actor);
        actionRepository.cancel(actionId, actor.getUserId());
        return card(load(actionId, actor));
    }

    /** 会话中的全部操作卡片（调用方需已校验会话属于当前用户）。 */
    public List<Map<String, Object>> cardsForConversation(String conversationId) {
        return actionRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream().map(this::card).toList();
    }

    public void attachMessage(String messageId, Collection<AssistantAction> actions) {
        if (messageId == null || actions == null || actions.isEmpty()) {
            return;
        }
        actionRepository.attachMessage(messageId, actions.stream().map(AssistantAction::getId).toList());
    }

    /**
     * 会话中操作的最新状态，作为模型上下文：用户确认后在后续对话里模型才知道资源已创建（以及新资源的 ID）。
     */
    public List<String> statusNotes(String conversationId) {
        List<String> notes = new ArrayList<>();
        for (AssistantAction action : actionRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)) {
            String status = effectiveStatus(action);
            String note = "「" + action.getTitle() + "」：" + statusLabel(status);
            if (AssistantAction.PENDING.equals(status)) {
                // 模型在后续轮次中编排依赖此卡片的操作时需要卡片 ID
                note += "（pendingActionId " + action.getId() + "）";
            }
            if (AssistantAction.EXECUTED.equals(status)) {
                if (action.getResourceId() != null) {
                    note += "（资源 ID " + action.getResourceId() + "）";
                }
                if (action.getResult() != null) {
                    JsonNode resNode = readJsonNode(action.getResult());
                    String readback = resNode != null ? resNode.path("readback").asText("") : "";
                    if (!readback.isBlank()) {
                        note += "；实际生效：" + readback;
                    }
                }
            }
            if (AssistantAction.FAILED.equals(status) && action.getResult() != null) {
                try {
                    String error = objectMapper.readTree(action.getResult()).path("error").asText("");
                    if (!error.isBlank()) {
                        note += "，原因：" + error;
                    }
                } catch (Exception ignored) {
                    // 结果格式异常时只给出状态
                }
            }
            notes.add(note);
        }
        return notes;
    }

    /** 卡片数据：标题、风险级别、字段、修改前后对比、状态、执行结果与过期时间。 */
    public Map<String, Object> card(AssistantAction action) {
        String status = effectiveStatus(action);
        if (!status.equals(action.getStatus())) {
            action.setStatus(status);
            action = actionRepository.save(action);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", action.getId());
        m.put("messageId", action.getMessageId());
        m.put("toolName", action.getToolName());
        m.put("title", action.getTitle());
        m.put("riskLevel", action.getRiskLevel());
        m.put("resourceType", action.getResourceType());
        m.put("resourceId", action.getResourceId());
        m.put("status", status);
        m.put("preview", readJson(action.getPreview()));
        m.put("result", readJson(action.getResult()));
        m.put("dependsOn", writeTools.dependencies(action));
        if (AssistantAction.PENDING.equals(status)) {
            Map<String, Object> previewEffect = AssistantUiEffects.fromPendingAction(action);
            if (previewEffect != null) m.put("previewUiEffect", previewEffect);
        }
        // 时间统一输出 ISO 字符串：卡片既走 SSE（普通 ObjectMapper）也走 REST，两边格式一致
        m.put("expiresAt", iso(action.getExpiresAt()));
        if (AssistantAction.PENDING.equals(status) && action.getExpiresAt() != null) {
            // 显式返回剩余秒数，避免服务端与浏览器时区/时钟不一致导致卡片刚生成就被前端误判为已过期
            long remaining = Math.max(0L, Duration.between(LocalDateTime.now(), action.getExpiresAt()).getSeconds());
            m.put("remainingSeconds", remaining);
        }
        m.put("createdAt", iso(action.getCreatedAt()));
        m.put("executedAt", iso(action.getExecutedAt()));
        return m;
    }

    private static String iso(LocalDateTime time) {
        return time == null ? null : time.toString();
    }

    /** 未处理且已超过有效期的操作视为过期（懒更新：展示或确认时落库）。 */
    static String effectiveStatus(AssistantAction action) {
        if (AssistantAction.PENDING.equals(action.getStatus())
                && action.getExpiresAt() != null && LocalDateTime.now().isAfter(action.getExpiresAt())) {
            return AssistantAction.EXPIRED;
        }
        return action.getStatus();
    }

    static String statusLabel(String status) {
        return switch (status) {
            case AssistantAction.PENDING -> "等待用户确认";
            case AssistantAction.EXECUTING -> "执行中";
            case AssistantAction.EXECUTED -> "用户已确认并执行成功";
            case AssistantAction.FAILED -> "用户已确认但执行失败";
            case AssistantAction.CANCELLED -> "用户已取消";
            case AssistantAction.EXPIRED -> "已过期未执行";
            default -> status;
        };
    }

    private AssistantAction load(String actionId, CurrentActor actor) {
        if (actor == null || actor.getUserId() == null) {
            throw new IllegalArgumentException("操作不存在");
        }
        return actionRepository.findByIdAndUserId(actionId, actor.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("操作不存在或已失效"));
    }

    private void audit(AssistantAction action, String result, String error) {
        StringBuilder detail = new StringBuilder();
        detail.append("via=assistant; tool=").append(action.getToolName())
                .append("; risk=").append(action.getRiskLevel())
                .append("; action=").append(action.getId())
                .append("; conversation=").append(action.getConversationId())
                .append("; title=").append(action.getTitle());
        if ("D".equalsIgnoreCase(action.getRiskLevel())) {
            JsonNode previewNode = readJsonNode(action.getPreview());
            if (previewNode != null) {
                String targetName = previewNode.path("confirmTargetName").asText("");
                if (!targetName.isBlank()) {
                    detail.append("; targetName=").append(targetName);
                }
                JsonNode impacts = previewNode.get("impacts");
                if (impacts != null && impacts.isArray() && !impacts.isEmpty()) {
                    List<String> impactList = new ArrayList<>();
                    impacts.forEach(n -> {
                        String t = n.asText("").trim();
                        if (!t.isEmpty()) {
                            impactList.add(t);
                        }
                    });
                    if (!impactList.isEmpty()) {
                        detail.append("; impacts=").append(String.join(" | ", impactList));
                    }
                }
            }
        }
        if (error != null) {
            detail.append("; error=").append(error);
        }
        String auditRisk = switch (action.getRiskLevel() == null ? "" : action.getRiskLevel().toUpperCase(java.util.Locale.ROOT)) {
            case "D" -> "HIGH";
            case "B", "W2" -> "MEDIUM";
            default -> "LOW";
        };
        try {
            auditRecorder.record("assistant.action." + action.getToolName(),
                    action.getResourceType() != null ? action.getResourceType() : "ASSISTANT",
                    action.getResourceId() != null ? action.getResourceId() : action.getId(),
                    result, error != null ? "execution_failed" : null,
                    auditRisk, detail.toString());
        } catch (Exception e) {
            // 审计写入失败不能影响执行结果的记录
            log.warn("Failed to audit assistant action [{}]: {}", action.getId(), e.getMessage());
        }
    }

    private JsonNode readJsonNode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private Object readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
