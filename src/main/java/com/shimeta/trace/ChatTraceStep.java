package com.shimeta.trace;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调用链路中的单个执行节点（准备、知识库检索、向量编码、重排、工具调用、大模型推理等）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatTraceStep {
    private String id;
    private String parentId;
    private String stage;
    private String name;
    /** running | ok | error | skipped */
    private String status;
    private long startOffsetMs;
    private Long durationMs;
    private String summary;
    private String error;
    private Map<String, Object> detail;

    public ChatTraceStep() {
    }

    public ChatTraceStep(String id, String parentId, String stage, String name, String status,
                         long startOffsetMs, Long durationMs, String summary, String error,
                         Map<String, Object> detail) {
        this.id = id;
        this.parentId = parentId;
        this.stage = stage;
        this.name = name;
        this.status = status;
        this.startOffsetMs = startOffsetMs;
        this.durationMs = durationMs;
        this.summary = summary;
        this.error = error;
        this.detail = detail == null || detail.isEmpty() ? null : new LinkedHashMap<>(detail);
    }

    public ChatTraceStep copy() {
        return new ChatTraceStep(id, parentId, stage, name, status, startOffsetMs, durationMs, summary, error, detail);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getStartOffsetMs() {
        return startOffsetMs;
    }

    public void setStartOffsetMs(long startOffsetMs) {
        this.startOffsetMs = startOffsetMs;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Map<String, Object> getDetail() {
        return detail;
    }

    public void setDetail(Map<String, Object> detail) {
        this.detail = detail;
    }
}
