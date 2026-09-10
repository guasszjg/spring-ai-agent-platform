package com.example.agentplatform.service;

import com.example.agentplatform.model.OpenApiCallLog;
import com.example.agentplatform.model.UsageDaily;
import com.example.agentplatform.repository.OpenApiCallLogRepository;
import com.example.agentplatform.repository.UsageDailyRepository;
import com.example.agentplatform.security.OpenApiContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(UsageRecorder.class);

    private final OpenApiCallLogRepository callLogRepository;
    private final UsageDailyRepository usageDailyRepository;

    public UsageRecorder(OpenApiCallLogRepository callLogRepository, UsageDailyRepository usageDailyRepository) {
        this.callLogRepository = callLogRepository;
        this.usageDailyRepository = usageDailyRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String endpoint, String agentId, String conversationId, int httpStatus,
                       String denyReason, int latencyMs, int promptTokens, int completionTokens, String model) {
        OpenApiContext ctx = OpenApiContext.get();
        if (ctx == null || ctx.getOwner() == null) {
            return;
        }
        String ownerId = ctx.getOwner().getId();
        String keyId = ctx.getKey() != null ? ctx.getKey().getId() : "";
        String clientId = ctx.getClient() != null ? ctx.getClient().getId() : "";
        String endUser = ctx.getEndUser();
        String ip = ctx.getIp();
        String requestId = ctx.getRequestId();

        try {
            // 1. 保存调用明细日志
            OpenApiCallLog callLog = new OpenApiCallLog();
            callLog.setTs(LocalDateTime.now());
            callLog.setOwnerId(ownerId);
            callLog.setApiKeyId(keyId);
            callLog.setClientCredentialId(clientId);
            callLog.setEndUser(endUser);
            callLog.setAgentId(agentId != null ? agentId : "");
            callLog.setConversationId(conversationId);
            callLog.setEndpoint(endpoint);
            callLog.setHttpStatus(httpStatus);
            callLog.setDenyReason(denyReason);
            callLog.setLatencyMs(latencyMs);
            callLog.setPromptTokens(promptTokens);
            callLog.setCompletionTokens(completionTokens);
            callLog.setModel(model);
            callLog.setIp(ip);
            callLog.setRequestId(requestId);
            callLogRepository.save(callLog);

            // 2. 累加多维用量事实表
            LocalDate today = LocalDate.now();
            String recordId = UsageDaily.buildId(today, ownerId, agentId, keyId, clientId);
            UsageDaily daily = usageDailyRepository.findById(recordId).orElseGet(() -> {
                UsageDaily d = new UsageDaily();
                d.setId(recordId);
                d.setStatDate(today);
                d.setOwnerId(ownerId);
                d.setAgentId(agentId != null ? agentId : "");
                d.setApiKeyId(keyId);
                d.setClientCredentialId(clientId);
                return d;
            });

            daily.setCalls(daily.getCalls() + 1);
            if (endpoint != null && endpoint.contains("chat")) {
                daily.setChatCalls(daily.getChatCalls() + 1);
                daily.setMessages(daily.getMessages() + (httpStatus == 200 ? 2 : 1));
            }
            daily.setPromptTokens(daily.getPromptTokens() + Math.max(0, promptTokens));
            daily.setCompletionTokens(daily.getCompletionTokens() + Math.max(0, completionTokens));
            daily.setLatencySumMs(daily.getLatencySumMs() + Math.max(0, latencyMs));

            if (httpStatus == 403 || httpStatus == 422 || httpStatus == 429) {
                daily.setDenied(daily.getDenied() + 1);
            } else if (httpStatus >= 400) {
                daily.setErrors(daily.getErrors() + 1);
            }

            usageDailyRepository.save(daily);
        } catch (Exception e) {
            log.warn("Failed to record open api usage: {}", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getSummary(String ownerId, LocalDate from, LocalDate to) {
        Object[] raw = (ownerId != null && !ownerId.isBlank())
                ? usageDailyRepository.getAggregatedSummary(ownerId, from, to)
                : usageDailyRepository.getAggregatedSummaryAll(from, to);
        Map<String, Object> summary = new LinkedHashMap<>();
        if (raw != null && raw.length > 0 && raw[0] instanceof Object[] arr) {
            summary.put("calls", arr[0]);
            summary.put("chatCalls", arr[1]);
            summary.put("messages", arr[2]);
            summary.put("promptTokens", arr[3]);
            summary.put("completionTokens", arr[4]);
            summary.put("denied", arr[5]);
            summary.put("errors", arr[6]);
            summary.put("latencySumMs", arr[7]);
        } else {
            summary.put("calls", 0);
            summary.put("chatCalls", 0);
            summary.put("messages", 0);
            summary.put("promptTokens", 0);
            summary.put("completionTokens", 0);
            summary.put("denied", 0);
            summary.put("errors", 0);
            summary.put("latencySumMs", 0);
        }
        return summary;
    }

    @Transactional(readOnly = true)
    public List<UsageDaily> getDailyList(String ownerId, LocalDate from, LocalDate to) {
        return (ownerId != null && !ownerId.isBlank())
                ? usageDailyRepository.findByOwnerIdAndStatDateBetweenOrderByStatDateDesc(ownerId, from, to)
                : usageDailyRepository.findByStatDateBetweenOrderByStatDateDesc(from, to);
    }
}
