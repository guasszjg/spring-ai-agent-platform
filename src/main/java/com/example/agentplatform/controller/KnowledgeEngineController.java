package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.rag.dto.KnowledgeEngineInfo;
import com.example.agentplatform.service.DifyConfigService;
import com.example.agentplatform.service.KnowledgeBaseService;
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
        KnowledgeEngineInfo info = knowledgeBaseService.getEngineInfo();
        if (!info.isConfigured()) {
            info.setReady(false);
            info.setProbeStatus("UNCONFIGURED");
        } else {
            var active = difyConfigService.getActiveConfig();
            if (active.isPresent()) {
                String status = active.get().getLastProbeStatus();
                info.setProbeStatus(status != null ? status : "UNTESTED");
                info.setReady("SUCCESS".equalsIgnoreCase(status));
            } else {
                // 仅通过配置文件提供地址（无网关配置记录）时无探测记录，视为就绪
                info.setProbeStatus("SUCCESS");
                info.setReady(true);
            }
        }
        return ResponseEntity.ok(ApiResponse.ok(info));
    }
}
