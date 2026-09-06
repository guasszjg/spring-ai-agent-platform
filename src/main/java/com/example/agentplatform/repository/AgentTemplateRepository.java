package com.example.agentplatform.repository;

import com.example.agentplatform.model.AgentTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AgentTemplateRepository extends JpaRepository<AgentTemplate, String> {

    List<AgentTemplate> findAllByOrderBySortOrderAscCreatedAtDesc();

    List<AgentTemplate> findByCategoryOrderBySortOrderAscCreatedAtDesc(String category);

    @Query("SELECT t FROM AgentTemplate t WHERE " +
            "(:category IS NULL OR :category = '' OR :category = '全部' OR t.category = :category) AND " +
            "(:keyword IS NULL OR :keyword = '' OR " +
            "LOWER(t.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(t.description) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(t.systemPrompt) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(t.tags) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "ORDER BY t.sortOrder ASC, t.createdAt DESC")
    List<AgentTemplate> searchTemplates(@Param("keyword") String keyword, @Param("category") String category);
}
