package com.example.agentplatform.service;

import com.example.agentplatform.config.SecretCrypto;
import com.example.agentplatform.model.AgentToolSecret;
import com.example.agentplatform.repository.AgentRepository;
import com.example.agentplatform.repository.AgentToolSecretRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AgentToolSecretService {

    private final AgentToolSecretRepository secretRepository;
    private final AgentRepository agentRepository;
    private final SecretCrypto secretCrypto;

    public AgentToolSecretService(AgentToolSecretRepository secretRepository,
                                  AgentRepository agentRepository,
                                  SecretCrypto secretCrypto) {
        this.secretRepository = secretRepository;
        this.agentRepository = agentRepository;
        this.secretCrypto = secretCrypto;
    }

    public void saveBochaApiKey(String agentId, String apiKey) {
        requireAgent(agentId);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Bocha API Key 不能为空");
        }
        AgentToolSecret secret = secretRepository.findById(agentId).orElseGet(() -> {
            AgentToolSecret created = new AgentToolSecret();
            created.setAgentId(agentId);
            return created;
        });
        secret.setBochaApiKeyEncrypted(secretCrypto.encrypt(apiKey.trim()));
        secretRepository.save(secret);
    }

    @Transactional(readOnly = true)
    public String getBochaApiKey(String agentId) {
        return secretRepository.findById(agentId)
                .map(AgentToolSecret::getBochaApiKeyEncrypted)
                .filter(value -> value != null && !value.isBlank())
                .map(secretCrypto::decrypt)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public boolean isBochaConfigured(String agentId) {
        requireAgent(agentId);
        return secretRepository.findById(agentId)
                .map(AgentToolSecret::getBochaApiKeyEncrypted)
                .filter(value -> value != null && !value.isBlank())
                .isPresent();
    }

    public void clearBochaApiKey(String agentId) {
        requireAgent(agentId);
        secretRepository.deleteById(agentId);
    }

    public void clearForDeletedAgent(String agentId) {
        secretRepository.deleteById(agentId);
    }

    public void copyForClonedAgent(String sourceAgentId, String targetAgentId) {
        if (sourceAgentId == null || targetAgentId == null) {
            return;
        }
        secretRepository.findById(sourceAgentId).ifPresent(sourceSecret -> {
            AgentToolSecret copy = new AgentToolSecret();
            copy.setAgentId(targetAgentId);
            copy.setBochaApiKeyEncrypted(sourceSecret.getBochaApiKeyEncrypted());
            secretRepository.save(copy);
        });
    }

    private void requireAgent(String agentId) {
        if (!agentRepository.existsById(agentId)) {
            throw new IllegalArgumentException("智能体不存在: " + agentId);
        }
    }
}
