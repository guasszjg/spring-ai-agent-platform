package com.example.agentplatform.service;

import com.example.agentplatform.config.SecretCrypto;
import com.example.agentplatform.model.Agent;
import com.example.agentplatform.model.AgentToolSecret;
import com.example.agentplatform.repository.AgentRepository;
import com.example.agentplatform.repository.AgentToolSecretRepository;
import com.example.agentplatform.security.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AgentToolSecretService {

    private final AgentToolSecretRepository secretRepository;
    private final AgentRepository agentRepository;
    private final SecretCrypto secretCrypto;
    private final ResourceAuthorizationService authService;

    public AgentToolSecretService(AgentToolSecretRepository secretRepository,
                                  AgentRepository agentRepository,
                                  SecretCrypto secretCrypto,
                                  ResourceAuthorizationService authService) {
        this.secretRepository = secretRepository;
        this.agentRepository = agentRepository;
        this.secretCrypto = secretCrypto;
        this.authService = authService;
    }

    public void saveBochaApiKey(String agentId, String apiKey) {
        saveBochaApiKey(agentId, apiKey, CurrentActor.get());
    }

    public void saveBochaApiKey(String agentId, String apiKey, CurrentActor actor) {
        Agent agent = requireAgent(agentId);
        if (actor != null && !authService.canManageAgent(actor, agent)) {
            throw new IllegalStateException("权限不足：仅所有者或超级管理员可配置该智能体的工具密钥");
        }
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
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        return secretRepository.findById(agentId)
                .map(AgentToolSecret::getBochaApiKeyEncrypted)
                .filter(value -> value != null && !value.isBlank())
                .map(secretCrypto::decrypt)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public boolean isBochaConfigured(String agentId) {
        return getBochaApiKey(agentId) != null;
    }

    @Transactional(readOnly = true)
    public boolean isBochaConfiguredSpecific(String agentId) {
        return isBochaConfiguredSpecific(agentId, CurrentActor.get());
    }

    @Transactional(readOnly = true)
    public boolean isBochaConfiguredSpecific(String agentId, CurrentActor actor) {
        if (agentId == null || agentId.isBlank()) {
            return false;
        }
        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null) {
            return false;
        }
        if (actor != null && !authService.canViewAgent(actor, agent)) {
            return false;
        }
        return secretRepository.findById(agentId)
                .map(AgentToolSecret::getBochaApiKeyEncrypted)
                .filter(value -> value != null && !value.isBlank())
                .isPresent();
    }

    public void clearBochaApiKey(String agentId) {
        clearBochaApiKey(agentId, CurrentActor.get());
    }

    public void clearBochaApiKey(String agentId, CurrentActor actor) {
        Agent agent = requireAgent(agentId);
        if (actor != null && !authService.canManageAgent(actor, agent)) {
            throw new IllegalStateException("权限不足：仅所有者或超级管理员可清除该智能体的工具密钥");
        }
        secretRepository.deleteById(agentId);
    }

    public void clearForDeletedAgent(String agentId) {
        secretRepository.deleteById(agentId);
    }

    private Agent requireAgent(String agentId) {
        return agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("智能体不存在: " + agentId));
    }
}
