package com.example.agentplatform.service;

import com.example.agentplatform.config.SecretCrypto;
import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentToolSecret;
import com.example.agentplatform.repository.AgentRepository;
import com.example.agentplatform.repository.AgentToolSecretRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolSecretServiceTest {

    @Test
    void encryptsBochaKeyBeforeSavingAndDecryptsItForInternalUse() {
        AgentToolSecretRepository secrets = mock(AgentToolSecretRepository.class);
        AgentRepository agents = mock(AgentRepository.class);
        SecretCrypto crypto = new SecretCrypto("test-only-master-secret");
        ResourceAuthorizationService authService = mock(ResourceAuthorizationService.class);
        AgentToolSecretService service = new AgentToolSecretService(secrets, agents, crypto, authService, mock(PlatformToolService.class));

        com.example.agentplatform.model.Agent agent = new com.example.agentplatform.model.Agent();
        agent.setId("agent-1");
        when(agents.findById("agent-1")).thenReturn(Optional.of(agent));
        when(authService.canManageAgent(any(), any())).thenReturn(true);
        when(secrets.findById("agent-1")).thenReturn(Optional.empty());

        service.saveBochaApiKey("agent-1", "bocha-plain-key");

        ArgumentCaptor<AgentToolSecret> captor = ArgumentCaptor.forClass(AgentToolSecret.class);
        verify(secrets).save(captor.capture());
        AgentToolSecret saved = captor.getValue();
        assertThat(saved.getBochaApiKeyEncrypted()).startsWith("enc:v1:");
        assertThat(saved.getBochaApiKeyEncrypted()).doesNotContain("bocha-plain-key");

        when(secrets.findById("agent-1")).thenReturn(Optional.of(saved));
        when(agents.findByIsSystemTrue()).thenReturn(List.of());
        assertThat(service.getBochaApiKey("agent-1")).isEqualTo("bocha-plain-key");
    }

    @Test
    void personalAgentInheritsBochaKeyFromPlatformCatalogFirst() {
        AgentToolSecretRepository secrets = mock(AgentToolSecretRepository.class);
        AgentRepository agents = mock(AgentRepository.class);
        SecretCrypto crypto = new SecretCrypto("test-only-master-secret");
        ResourceAuthorizationService authService = mock(ResourceAuthorizationService.class);
        PlatformToolService platformTools = mock(PlatformToolService.class);
        AgentToolSecretService service = new AgentToolSecretService(secrets, agents, crypto, authService, platformTools);

        when(secrets.findById("agent-personal")).thenReturn(Optional.empty());
        when(platformTools.getBochaApiKey()).thenReturn("catalog-bocha-key");

        assertThat(service.getBochaApiKey("agent-personal")).isEqualTo("catalog-bocha-key");
    }

    @Test
    void personalAgentInheritsBochaKeyFromSystemAgent() {
        AgentToolSecretRepository secrets = mock(AgentToolSecretRepository.class);
        AgentRepository agents = mock(AgentRepository.class);
        SecretCrypto crypto = new SecretCrypto("test-only-master-secret");
        ResourceAuthorizationService authService = mock(ResourceAuthorizationService.class);
        AgentToolSecretService service = new AgentToolSecretService(secrets, agents, crypto, authService, mock(PlatformToolService.class));

        Agent system = new Agent();
        system.setId("agent-007");
        system.setIsSystem(true);
        Agent personal = new Agent();
        personal.setId("agent-personal");
        personal.setIsSystem(false);

        AgentToolSecret platformSecret = new AgentToolSecret();
        platformSecret.setAgentId("agent-007");
        platformSecret.setBochaApiKeyEncrypted(crypto.encrypt("platform-bocha-key"));

        when(agents.findByIsSystemTrue()).thenReturn(List.of(system));
        when(secrets.findById("agent-personal")).thenReturn(Optional.empty());
        when(secrets.findById("agent-007")).thenReturn(Optional.of(platformSecret));

        assertThat(service.getBochaApiKey("agent-personal")).isEqualTo("platform-bocha-key");
        assertThat(service.isBochaConfigured("agent-personal")).isTrue();
        when(agents.findById("agent-personal")).thenReturn(Optional.of(personal));
        assertThat(service.isBochaConfiguredSpecific("agent-personal", null)).isFalse();
    }

    @Test
    void doesNotInheritBochaKeyFromAnotherDeveloperPrivateAgent() {
        AgentToolSecretRepository secrets = mock(AgentToolSecretRepository.class);
        AgentRepository agents = mock(AgentRepository.class);
        SecretCrypto crypto = new SecretCrypto("test-only-master-secret");
        ResourceAuthorizationService authService = mock(ResourceAuthorizationService.class);
        AgentToolSecretService service = new AgentToolSecretService(secrets, agents, crypto, authService, mock(PlatformToolService.class));

        when(agents.findByIsSystemTrue()).thenReturn(List.of());
        when(secrets.findById("agent-personal")).thenReturn(Optional.empty());

        assertThat(service.getBochaApiKey("agent-personal")).isNull();
        assertThat(service.isBochaConfigured("agent-personal")).isFalse();
    }
}
