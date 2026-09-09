package com.example.agentplatform.repository;

import com.example.agentplatform.model.ResourceGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ResourceGrantRepository extends JpaRepository<ResourceGrant, String> {

    List<ResourceGrant> findByResourceTypeAndResourceId(String resourceType, String resourceId);

    List<ResourceGrant> findByResourceTypeAndGranteeUserId(String resourceType, String granteeUserId);

    Optional<ResourceGrant> findByResourceTypeAndResourceIdAndGranteeUserId(String resourceType, String resourceId, String granteeUserId);

    boolean existsByResourceTypeAndResourceIdAndGranteeUserIdAndLevelIn(String resourceType, String resourceId, String granteeUserId, Collection<String> levels);

    void deleteByResourceTypeAndResourceIdAndGranteeUserId(String resourceType, String resourceId, String granteeUserId);

    void deleteByResourceTypeAndResourceId(String resourceType, String resourceId);
}
