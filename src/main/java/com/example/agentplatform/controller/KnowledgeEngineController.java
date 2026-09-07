package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.rag.dto.KnowledgeEngineInfo;
import com.example.agentplatform.service.KnowledgeBaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-engine")
public class KnowledgeEngineController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeEngineController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<KnowledgeEngineInfo>> engine() {
        return ResponseEntity.ok(ApiResponse.ok(knowledgeBaseService.getEngineInfo()));
    }
}
