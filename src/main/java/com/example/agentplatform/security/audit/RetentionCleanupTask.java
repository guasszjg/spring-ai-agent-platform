package com.example.agentplatform.security.audit;

import com.example.agentplatform.repository.AssistantConversationRepository;
import com.example.agentplatform.repository.AuditEventRepository;
import com.example.agentplatform.repository.OpenApiCallLogRepository;
import com.example.agentplatform.service.AuditRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Scheduled cleanup task for data retention compliance.
 * Purges audit events and open api call logs older than configured retention days.
 */
@Component
public class RetentionCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupTask.class);

    private final AuditEventRepository auditEventRepository;
    private final OpenApiCallLogRepository openApiCallLogRepository;
    private final AuditRecorder auditRecorder;

    @Value("${app.security.retention.audit-days:90}")
    private int auditDays = 90;

    @Value("${app.security.retention.call-log-days:90}")
    private int callLogDays = 90;

    @Value("${app.assistant.retention-days:90}")
    private int assistantDays = 90;

    private AssistantConversationRepository assistantConversationRepository;

    public RetentionCleanupTask(AuditEventRepository auditEventRepository,
                                OpenApiCallLogRepository openApiCallLogRepository,
                                AuditRecorder auditRecorder) {
        this.auditEventRepository = auditEventRepository;
        this.openApiCallLogRepository = openApiCallLogRepository;
        this.auditRecorder = auditRecorder;
    }

    /** 可选注入（setter 方式，避免改动已有构造器签名）：平台 AI 助手会话按保留天数清理。 */
    @Autowired(required = false)
    public void setAssistantConversationRepository(AssistantConversationRepository assistantConversationRepository) {
        this.assistantConversationRepository = assistantConversationRepository;
    }

    // Runs every day at 03:30 AM
    @Scheduled(cron = "0 30 3 * * ?")
    @Transactional
    public void cleanupExpiredLogs() {
        executeCleanup();
    }

    @Transactional
    public void executeCleanup() {
        try {
            LocalDateTime cutoffAudit = LocalDateTime.now().minusDays(Math.max(1, auditDays));
            long deletedAudits = auditEventRepository.deleteByOccurredAtBefore(cutoffAudit);

            LocalDateTime cutoffCallLogs = LocalDateTime.now().minusDays(Math.max(1, callLogDays));
            long deletedCallLogs = openApiCallLogRepository.deleteByTsBefore(cutoffCallLogs);

            long deletedConversations = 0;
            if (assistantConversationRepository != null) {
                LocalDateTime cutoffAssistant = LocalDateTime.now().minusDays(Math.max(1, assistantDays));
                deletedConversations = assistantConversationRepository.deleteByUpdatedAtBefore(cutoffAssistant);
            }

            if (deletedAudits > 0 || deletedCallLogs > 0 || deletedConversations > 0) {
                log.info("Retention cleanup completed: purged {} audit_events, {} call_logs, {} assistant_conversations",
                        deletedAudits, deletedCallLogs, deletedConversations);
                auditRecorder.record("system.retention_cleanup", "SYSTEM", "RETENTION", "SUCCESS", null, "LOW",
                        String.format("Purged %d audit events, %d call logs, %d assistant conversations",
                                deletedAudits, deletedCallLogs, deletedConversations));
            }
        } catch (Exception e) {
            log.error("Retention cleanup error: {}", e.getMessage(), e);
        }
    }
}
