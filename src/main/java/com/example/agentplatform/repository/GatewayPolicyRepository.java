package com.example.agentplatform.repository;

import com.example.agentplatform.model.GatewayPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GatewayPolicyRepository extends JpaRepository<GatewayPolicy, String> {
}
