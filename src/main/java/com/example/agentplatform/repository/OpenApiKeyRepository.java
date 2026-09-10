package com.example.agentplatform.repository;

import com.example.agentplatform.model.OpenApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OpenApiKeyRepository extends JpaRepository<OpenApiKey, String> {
    Optional<OpenApiKey> findByKeyHash(String keyHash);
    List<OpenApiKey> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
    List<OpenApiKey> findAllByOrderByCreatedAtDesc();
    long countByStatus(String status);
    long countByOwnerIdAndStatus(String ownerId, String status);
}
