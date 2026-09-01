package com.example.agentplatform.model;

import java.util.ArrayList;
import java.util.List;

public class GatewayProbeResult {
    private boolean success;
    private String message;
    private List<String> models = new ArrayList<>();

    public GatewayProbeResult() {
    }

    public GatewayProbeResult(boolean success, String message) {
        this(success, message, List.of());
    }

    public GatewayProbeResult(boolean success, String message, List<String> models) {
        this.success = success;
        this.message = message;
        this.models = models != null ? models : new ArrayList<>();
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<String> getModels() {
        return models;
    }

    public void setModels(List<String> models) {
        this.models = models;
    }
}
