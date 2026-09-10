package com.example.agentplatform.model;

import com.example.agentplatform.config.StringListJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "guardrail_policies")
public class GuardrailPolicy {

    @Id
    @Column(name = "owner_id", length = 64)
    private String ownerId;

    @Column(name = "client_policy", length = 32)
    private String clientPolicy = "OFF";

    @Column(name = "default_rpm")
    private Integer defaultRpm = 120;

    @Column(name = "default_daily_tokens")
    private Long defaultDailyTokens;

    @Column(name = "max_input_chars")
    private Integer maxInputChars = 8000;

    @Column(name = "max_history_turns")
    private Integer maxHistoryTurns = 30;

    @Column(name = "pii_mask")
    private Boolean piiMask = false;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "sensitive_words", columnDefinition = "TEXT")
    private List<String> sensitiveWords = new ArrayList<>();

    @Column(name = "sensitive_action", length = 16)
    private String sensitiveAction = "BLOCK";

    @Column(name = "prompt_injection", length = 16)
    private String promptInjection = "LOG";

    @Column(name = "output_guard")
    private Boolean outputGuard = true;

    @Column(name = "allowed_hours", length = 64)
    private String allowedHours;

    @Column(name = "kill_switch")
    private Boolean killSwitch = false;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    public void touch() {
        updatedAt = LocalDateTime.now();
        if (clientPolicy == null) clientPolicy = "OFF";
        if (defaultRpm == null) defaultRpm = 120;
        if (maxInputChars == null) maxInputChars = 8000;
        if (maxHistoryTurns == null) maxHistoryTurns = 30;
        if (piiMask == null) piiMask = false;
        if (outputGuard == null) outputGuard = true;
        if (killSwitch == null) killSwitch = false;
        if (sensitiveAction == null) sensitiveAction = "BLOCK";
        if (promptInjection == null) promptInjection = "LOG";
    }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getClientPolicy() { return clientPolicy != null ? clientPolicy : "OFF"; }
    public void setClientPolicy(String clientPolicy) { this.clientPolicy = clientPolicy; }
    public Integer getDefaultRpm() { return defaultRpm != null ? defaultRpm : 120; }
    public void setDefaultRpm(Integer defaultRpm) { this.defaultRpm = defaultRpm; }
    public Long getDefaultDailyTokens() { return defaultDailyTokens; }
    public void setDefaultDailyTokens(Long defaultDailyTokens) { this.defaultDailyTokens = defaultDailyTokens; }
    public Integer getMaxInputChars() { return maxInputChars != null ? maxInputChars : 8000; }
    public void setMaxInputChars(Integer maxInputChars) { this.maxInputChars = maxInputChars; }
    public Integer getMaxHistoryTurns() { return maxHistoryTurns != null ? maxHistoryTurns : 30; }
    public void setMaxHistoryTurns(Integer maxHistoryTurns) { this.maxHistoryTurns = maxHistoryTurns; }
    public Boolean getPiiMask() { return Boolean.TRUE.equals(piiMask); }
    public void setPiiMask(Boolean piiMask) { this.piiMask = piiMask; }
    public List<String> getSensitiveWords() { return sensitiveWords != null ? sensitiveWords : new ArrayList<>(); }
    public void setSensitiveWords(List<String> sensitiveWords) { this.sensitiveWords = sensitiveWords != null ? sensitiveWords : new ArrayList<>(); }
    public String getSensitiveAction() { return sensitiveAction != null ? sensitiveAction : "BLOCK"; }
    public void setSensitiveAction(String sensitiveAction) { this.sensitiveAction = sensitiveAction; }
    public String getPromptInjection() { return promptInjection != null ? promptInjection : "LOG"; }
    public void setPromptInjection(String promptInjection) { this.promptInjection = promptInjection; }
    public Boolean getOutputGuard() { return outputGuard == null || outputGuard; }
    public void setOutputGuard(Boolean outputGuard) { this.outputGuard = outputGuard; }
    public String getAllowedHours() { return allowedHours; }
    public void setAllowedHours(String allowedHours) { this.allowedHours = allowedHours; }
    public Boolean getKillSwitch() { return Boolean.TRUE.equals(killSwitch); }
    public void setKillSwitch(Boolean killSwitch) { this.killSwitch = killSwitch; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
