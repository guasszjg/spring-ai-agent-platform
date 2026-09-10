package com.example.agentplatform.service;

import com.example.agentplatform.model.AuditEvent;
import com.example.agentplatform.repository.AuditEventRepository;
import com.example.agentplatform.security.CurrentActor;
import com.example.agentplatform.security.OpenApiContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditRecorder {

    private final AuditEventRepository auditEventRepository;

    public AuditRecorder(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String resourceType, String resourceId, String result,
                       String reasonCode, String riskLevel, String sanitizedDiff) {
        AuditEvent event = new AuditEvent();
        event.setAction(action);
        event.setResourceType(resourceType);
        event.setResourceId(resourceId);
        event.setResult(result);
        event.setReasonCode(reasonCode);
        event.setRiskLevel(riskLevel != null ? riskLevel : ("DENIED".equals(result) ? "MEDIUM" : "LOW"));
        event.setSanitizedDiff(sanitizedDiff);

        OpenApiContext open = OpenApiContext.get();
        if (open != null) {
            event.setActorType("API_KEY");
            event.setActorUserId(open.getOwner().getId());
            event.setOwnerId(open.getOwner().getId());
            event.setApiKeyId(open.getKey().getId());
            event.setRequestId(open.getRequestId());
            event.setClientIp(open.getIp());
            if (open.getClient() != null) {
                event.setClientCredentialId(open.getClient().getId());
            }
        } else {
            CurrentActor actor = CurrentActor.get();
            event.setActorType("USER");
            if (actor != null) {
                event.setActorUserId(actor.getUserId());
                event.setOwnerId(actor.getUserId());
            }
        }
        auditEventRepository.save(event);
    }
}
