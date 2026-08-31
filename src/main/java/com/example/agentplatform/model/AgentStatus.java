package com.example.agentplatform.model;

public enum AgentStatus {
    RUNNING("运行中", "badge-success"),
    IDLE("空闲中", "badge-warning"),
    DISABLED("已停用", "badge-danger");

    private final String label;
    private final String badgeClass;

    AgentStatus(String label, String badgeClass) {
        this.label = label;
        this.badgeClass = badgeClass;
    }

    public String getLabel() {
        return label;
    }

    public String getBadgeClass() {
        return badgeClass;
    }
}
