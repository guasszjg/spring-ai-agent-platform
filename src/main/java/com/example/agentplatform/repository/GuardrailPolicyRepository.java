package com.example.agentplatform.repository;

import com.example.agentplatform.model.GuardrailPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuardrailPolicyRepository extends JpaRepository<GuardrailPolicy, String> {
}
