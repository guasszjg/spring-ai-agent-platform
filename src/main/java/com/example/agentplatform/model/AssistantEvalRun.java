package com.example.agentplatform.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonRawValue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/** 平台 AI 助手的一次评测运行。 */
@Entity
@Table(name = "assistant_eval_runs")
public class AssistantEvalRun {

    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "triggered_by", length = 64)
    private String triggeredBy;

    @Column(length = 128)
    private String model;

    @Column(nullable = false)
    private Integer total = 0;

    @Column(nullable = false)
    private Integer completed = 0;

    @Column(nullable = false)
    private Integer passed = 0;

    @Column(nullable = false)
    private Integer failed = 0;

    @Column(nullable = false)
    private Integer skipped = 0;

    @Column(nullable = false)
    private Integer errored = 0;

    @Column(name = "prompt_tokens", nullable = false)
    private Long promptTokens = 0L;

    @Column(name = "completion_tokens", nullable = false)
    private Long completionTokens = 0L;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String results;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = "eval-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getTotal() { return total; }
    public void setTotal(Integer total) { this.total = total; }
    public Integer getCompleted() { return completed; }
    public void setCompleted(Integer completed) { this.completed = completed; }
    public Integer getPassed() { return passed; }
    public void setPassed(Integer passed) { this.passed = passed; }
    public Integer getFailed() { return failed; }
    public void setFailed(Integer failed) { this.failed = failed; }
    public Integer getSkipped() { return skipped; }
    public void setSkipped(Integer skipped) { this.skipped = skipped; }
    public Integer getErrored() { return errored; }
    public void setErrored(Integer errored) { this.errored = errored; }
    public Long getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Long promptTokens) { this.promptTokens = promptTokens; }
    public Long getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Long completionTokens) { this.completionTokens = completionTokens; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    /** 汇总与逐条结果本身就是 JSON，接口中原样输出为对象 */
    @JsonRawValue
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    @JsonRawValue
    public String getResults() { return results; }
    public void setResults(String results) { this.results = results; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }

    @JsonIgnore
    public boolean isRunning() { return RUNNING.equals(status); }
}
