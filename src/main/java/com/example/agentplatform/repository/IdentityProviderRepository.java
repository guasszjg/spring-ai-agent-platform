package com.example.agentplatform.repository;

import com.example.agentplatform.model.IdentityProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IdentityProviderRepository extends JpaRepository<IdentityProvider, String> {
    Optional<IdentityProvider> findByCode(String code);
    List<IdentityProvider> findByEnabledTrue();
}
