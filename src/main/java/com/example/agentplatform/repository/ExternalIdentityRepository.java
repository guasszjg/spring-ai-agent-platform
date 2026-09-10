package com.example.agentplatform.repository;

import com.example.agentplatform.model.ExternalIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExternalIdentityRepository extends JpaRepository<ExternalIdentity, String> {
    List<ExternalIdentity> findByUserIdOrderByBoundAtDesc(String userId);
    List<ExternalIdentity> findByProviderCode(String providerCode);
    Optional<ExternalIdentity> findByProviderCodeAndExternalId(String providerCode, String externalId);
    long countByStatus(String status);
}
