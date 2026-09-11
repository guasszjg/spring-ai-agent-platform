package com.example.agentplatform.security.guardrail;

public class InputGuardResult {
    private final boolean blocked;
    private final String denyCode;
    private final String denyMessage;
    private final String processedMessage;
    private final String matchedTerm;
    private final String warningReason;

    private InputGuardResult(boolean blocked, String denyCode, String denyMessage, String processedMessage, String matchedTerm, String warningReason) {
        this.blocked = blocked;
        this.denyCode = denyCode;
        this.denyMessage = denyMessage;
        this.processedMessage = processedMessage;
        this.matchedTerm = matchedTerm;
        this.warningReason = warningReason;
    }

    public static InputGuardResult allow(String processedMessage, String warningReason, String matchedTerm) {
        return new InputGuardResult(false, null, null, processedMessage, matchedTerm, warningReason);
    }

    public static InputGuardResult block(String denyCode, String denyMessage, String matchedTerm) {
        return new InputGuardResult(true, denyCode, denyMessage, null, matchedTerm, null);
    }

    public boolean isBlocked() { return blocked; }
    public String getDenyCode() { return denyCode; }
    public String getReasonCode() { return denyCode; }
    public String getDenyMessage() { return denyMessage; }
    public String getMessage() { return denyMessage; }
    public String getProcessedMessage() { return processedMessage; }
    public String getProcessedText() { return processedMessage; }
    public String getMatchedTerm() { return matchedTerm; }
    public String getWarningReason() { return warningReason; }
    public String getAuditType() { return warningReason; }
}
