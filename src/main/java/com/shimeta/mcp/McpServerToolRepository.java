package com.shimeta.mcp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface McpServerToolRepository extends JpaRepository<McpServerTool, String> {
    List<McpServerTool> findByServerIdOrderByToolNameAsc(String serverId);

    List<McpServerTool> findByEnabledTrue();

    Optional<McpServerTool> findByFunctionName(String functionName);

    void deleteByServerId(String serverId);
}
