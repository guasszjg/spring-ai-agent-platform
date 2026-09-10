package com.example.agentplatform.security;

import com.example.agentplatform.model.UserRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiScopesTest {

    @Test
    void viewerCannotIssueKeys() {
        assertThatThrownBy(() -> OpenApiScopes.validateAgainstRole(UserRole.VIEWER, List.of("chat")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只读观察员");
    }

    @Test
    void developerCanIssueChat() {
        OpenApiScopes.validateAgainstRole(UserRole.DEVELOPER, List.of("chat", "agents:read"));
        assertThat(OpenApiScopes.requiredPermission("chat")).isEqualTo("agent:run");
    }

    @Test
    void unknownScopeRejected() {
        assertThatThrownBy(() -> OpenApiScopes.validateAgainstRole(UserRole.DEVELOPER, List.of("god:mode")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持");
    }
}
