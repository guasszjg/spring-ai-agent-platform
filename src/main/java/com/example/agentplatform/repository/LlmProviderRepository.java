package com.example.agentplatform.repository;

import com.example.agentplatform.model.LlmProvider;
import com.example.agentplatform.model.LlmProviderType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LlmProviderRepository extends JpaRepository<LlmProvider, String> {
    List<LlmProvider> findAllByOrderByBuiltinDescCreatedAtAsc();

    long countByVendor(LlmProviderType vendor);
}
