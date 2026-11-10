package com.shimeta.mcp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface McpServerRepository extends JpaRepository<McpServer, String> {
    List<McpServer> findAllByOrderByCreatedAtAsc();

    boolean existsBySlug(String slug);
}
