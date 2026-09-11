package com.example.agentplatform.security.audit;

import com.example.agentplatform.repository.AuditEventRepository;
import com.example.agentplatform.repository.OpenApiCallLogRepository;
import com.example.agentplatform.service.AuditRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public RetentionCleanupTask(AuditEventRepository auditEventRepository,
                                OpenApiCallLogRepository openApiCallLogRepository,
                                AuditRecorder auditRecorder) {
        this.auditEventRepository = auditEventRepository;
        this.openApiCallLogRepository = openApiCallLogRepository;
        this.auditRecorder = auditRecorder;
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

            if (deletedAudits > 0 || deletedCallLogs > 0) {
                log.info("Retention cleanup completed: purged {} audit_events, {} call_logs", deletedAudits, deletedCallLogs);
                auditRecorder.record("system.retention_cleanup", "SYSTEM", "RETENTION", "SUCCESS", null, "LOW",
                        String.format("Purged %d audit events, %d call logs", deletedAudits, deletedCallLogs));
            }
        } catch (Exception e) {
            log.error("Retention cleanup error: {}", e.getMessage(), e);
        }
    }
}
