package com.example.agentplatform.security.guardrail;

public class OutputGuardResult {
    private final boolean blocked;
    private final String denyCode;
    private final String denyMessage;
    private final String processedReply;
    private final String matchedTerm;

    private OutputGuardResult(boolean blocked, String denyCode, String denyMessage, String processedReply, String matchedTerm) {
        this.blocked = blocked;
        this.denyCode = denyCode;
        this.denyMessage = denyMessage;
        this.processedReply = processedReply;
        this.matchedTerm = matchedTerm;
    }

    public static OutputGuardResult allow(String processedReply) {
        return new OutputGuardResult(false, null, null, processedReply, null);
    }

    public static OutputGuardResult block(String denyCode, String denyMessage, String matchedTerm) {
        return new OutputGuardResult(true, denyCode, denyMessage, null, matchedTerm);
    }

    public boolean isBlocked() { return blocked; }
    public String getDenyCode() { return denyCode; }
    public String getReasonCode() { return denyCode; }
    public String getDenyMessage() { return denyMessage; }
    public String getMessage() { return denyMessage; }
    public String getProcessedReply() { return processedReply; }
    public String getProcessedText() { return processedReply; }
    public String getMatchedTerm() { return matchedTerm; }
}
