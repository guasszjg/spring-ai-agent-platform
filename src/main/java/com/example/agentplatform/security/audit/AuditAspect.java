package com.example.agentplatform.security.audit;

import com.example.agentplatform.service.AuditRecorder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);
    private final AuditRecorder auditRecorder;

    public AuditAspect(AuditRecorder auditRecorder) {
        this.auditRecorder = auditRecorder;
    }

    @Around("@annotation(auditedAction)")
    public Object auditMethod(ProceedingJoinPoint joinPoint, AuditedAction auditedAction) throws Throwable {
        String action = auditedAction.action();
        String resourceType = auditedAction.resourceType();
        String riskLevel = auditedAction.riskLevel();

        String resourceId = null;
        Object[] args = joinPoint.getArgs();
        if (args != null && args.length > 0) {
            if (args[0] instanceof String s && !s.isBlank()) {
                resourceId = s;
            }
        }

        try {
            Object result = joinPoint.proceed();
            if (resourceId == null && result instanceof Map<?, ?> map) {
                if (map.containsKey("userId")) {
                    resourceId = String.valueOf(map.get("userId"));
                } else if (map.containsKey("id")) {
                    resourceId = String.valueOf(map.get("id"));
                }
            }
            auditRecorder.record(action, resourceType, resourceId, "SUCCESS", null, riskLevel, null);
            return result;
        } catch (Throwable t) {
            log.warn("Audited action failed: action={}, err={}", action, t.getMessage());
            auditRecorder.record(action, resourceType, resourceId, "ERROR", t.getClass().getSimpleName(), "HIGH", t.getMessage());
            throw t;
        }
    }
}
