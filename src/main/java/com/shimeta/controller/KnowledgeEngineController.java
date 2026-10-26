package com.shimeta.controller;

import com.shimeta.model.ApiResponse;
import com.shimeta.rag.dto.KnowledgeEngineInfo;
import com.shimeta.service.DifyConfigService;
import com.shimeta.service.KnowledgeBaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-engine")
public class KnowledgeEngineController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final DifyConfigService difyConfigService;

    public KnowledgeEngineController(KnowledgeBaseService knowledgeBaseService, DifyConfigService difyConfigService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.difyConfigService = difyConfigService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<KnowledgeEngineInfo>> engine() {
        KnowledgeEngineInfo info = difyConfigService.applyReadiness(knowledgeBaseService.getEngineInfo());
        return ResponseEntity.ok(ApiResponse.ok(info));
    }
}
