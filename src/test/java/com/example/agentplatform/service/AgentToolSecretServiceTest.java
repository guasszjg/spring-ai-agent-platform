package com.example.agentplatform.service;

import com.example.agentplatform.config.SecretCrypto;
import com.example.agentplatform.model.AgentToolSecret;
import com.example.agentplatform.repository.AgentRepository;
import com.example.agentplatform.repository.AgentToolSecretRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolSecretServiceTest {

    @Test
    void encryptsBochaKeyBeforeSavingAndDecryptsItForInternalUse() {
        AgentToolSecretRepository secrets = mock(AgentToolSecretRepository.class);
        AgentRepository agents = mock(AgentRepository.class);
        SecretCrypto crypto = new SecretCrypto("test-only-master-secret");
        AgentToolSecretService service = new AgentToolSecretService(secrets, agents, crypto);
        when(agents.existsById("agent-1")).thenReturn(true);
        when(secrets.findById("agent-1")).thenReturn(Optional.empty());

        service.saveBochaApiKey("agent-1", "bocha-plain-key");

        ArgumentCaptor<AgentToolSecret> captor = ArgumentCaptor.forClass(AgentToolSecret.class);
        verify(secrets).save(captor.capture());
        AgentToolSecret saved = captor.getValue();
        assertThat(saved.getBochaApiKeyEncrypted()).startsWith("enc:v1:");
        assertThat(saved.getBochaApiKeyEncrypted()).doesNotContain("bocha-plain-key");

        when(secrets.findById("agent-1")).thenReturn(Optional.of(saved));
        assertThat(service.getBochaApiKey("agent-1")).isEqualTo("bocha-plain-key");
    }
}
