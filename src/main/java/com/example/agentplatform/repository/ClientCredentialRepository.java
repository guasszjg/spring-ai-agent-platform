package com.example.agentplatform.repository;

import com.example.agentplatform.model.ClientCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClientCredentialRepository extends JpaRepository<ClientCredential, String> {
    List<ClientCredential> findByOwnerIdOrderByUpdatedAtDesc(String ownerId);
    List<ClientCredential> findAllByOrderByUpdatedAtDesc();
    Optional<ClientCredential> findByOwnerIdAndClientTypeAndClientIdHash(String ownerId, String clientType, String clientIdHash);
    long countByStatus(String status);
    long countByOwnerIdAndStatus(String ownerId, String status);
}
