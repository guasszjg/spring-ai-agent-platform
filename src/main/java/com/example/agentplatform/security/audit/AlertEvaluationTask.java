package com.example.agentplatform.security.audit;

import com.example.agentplatform.service.AlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled evaluation task for security and operational alert rules.
 * Runs every minute to assess thresholds and trigger Webhooks.
 */
@Component
public class AlertEvaluationTask {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluationTask.class);

    private final AlertService alertService;

    public AlertEvaluationTask(AlertService alertService) {
        this.alertService = alertService;
    }

    // Evaluates every minute
    @Scheduled(cron = "0 */1 * * * ?")
    public void scheduleEvaluation() {
        try {
            int triggered = alertService.evaluateAllRules();
            if (triggered > 0) {
                log.info("Alert evaluation completed: triggered {} alert rules", triggered);
            }
        } catch (Exception e) {
            log.error("Alert evaluation job error: {}", e.getMessage(), e);
        }
    }
}
