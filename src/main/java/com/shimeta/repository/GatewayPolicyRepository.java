package com.shimeta.repository;

import com.shimeta.model.GatewayPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GatewayPolicyRepository extends JpaRepository<GatewayPolicy, String> {
}
