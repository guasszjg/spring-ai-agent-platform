package com.example.agentplatform.controller;

import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.EnabledToggleRequest;
import com.example.agentplatform.model.GatewayModelOption;
import com.example.agentplatform.model.GatewayOverview;
import com.example.agentplatform.model.GatewayPolicy;
import com.example.agentplatform.model.GatewayProbeRequest;
import com.example.agentplatform.model.GatewayProbeResult;
import com.example.agentplatform.model.LlmProviderRequest;
import com.example.agentplatform.model.LlmProviderView;
import com.example.agentplatform.model.DifyConfigRequest;
import com.example.agentplatform.model.DifyConfigView;
import com.example.agentplatform.model.EmbeddingConfigRequest;
import com.example.agentplatform.model.EmbeddingConfigView;
import com.example.agentplatform.model.OcrConfigRequest;
import com.example.agentplatform.model.OcrConfigView;
import com.example.agentplatform.service.DifyConfigService;
import com.example.agentplatform.service.EmbeddingConfigService;
import com.example.agentplatform.service.OcrConfigService;
import com.example.agentplatform.service.LlmGatewayService;
import com.example.agentplatform.service.LlmVendorCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.agentplatform.security.CurrentActor;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/model-gateway")
public class ModelGatewayController {

    private final LlmGatewayService gatewayService;
    private final EmbeddingConfigService embeddingConfigService;
    private final DifyConfigService difyConfigService;
    private final OcrConfigService ocrConfigService;

    public ModelGatewayController(LlmGatewayService gatewayService,
                                  EmbeddingConfigService embeddingConfigService,
                                  DifyConfigService difyConfigService,
                                  OcrConfigService ocrConfigService) {
        this.gatewayService = gatewayService;
        this.embeddingConfigService = embeddingConfigService;
        this.difyConfigService = difyConfigService;
        this.ocrConfigService = ocrConfigService;
    }

    private boolean checkAdmin() {
        CurrentActor actor = CurrentActor.get();
        return actor != null && actor.isSuperAdmin();
    }

    @GetMapping({ "", "/overview" })
    public ResponseEntity<ApiResponse<GatewayOverview>> overview() {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可查看网关概览"));
        }
        return ResponseEntity.ok(ApiResponse.ok(gatewayService.overview()));
    }

    @GetMapping("/active-route")
    public ResponseEntity<ApiResponse<Map<String, String>>> activeRoute() {
        return ResponseEntity.ok(ApiResponse.ok(gatewayService.activeRoute()));
    }

    @GetMapping("/catalog")
    public ResponseEntity<ApiResponse<List<LlmVendorCatalog.VendorPreset>>> catalog() {
        return ResponseEntity.ok(ApiResponse.ok(LlmVendorCatalog.all()));
    }

    @GetMapping("/models")
    public ResponseEntity<ApiResponse<List<GatewayModelOption>>> models() {
        return ResponseEntity.ok(ApiResponse.ok(gatewayService.listModels()));
    }

    @PostMapping("/providers")
    public ResponseEntity<ApiResponse<LlmProviderView>> create(@RequestBody LlmProviderRequest request) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可创建模型通道"));
        }
        try {
            LlmProviderView created = gatewayService.create(request);
            URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                    .path("/{id}")
                    .buildAndExpand(created.getId())
                    .toUri();
            return ResponseEntity.created(location).body(ApiResponse.ok("模型通道已创建", created));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/providers/{id}")
    public ResponseEntity<ApiResponse<LlmProviderView>> update(@PathVariable String id,
                                                               @RequestBody LlmProviderRequest request) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可修改模型通道"));
        }
        try {
            return ResponseEntity.ok(ApiResponse.ok("模型通道已更新", gatewayService.update(id, request)));
        } catch (IllegalArgumentException e) {
            HttpStatus status = e.getMessage() != null && e.getMessage().contains("未找到")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PatchMapping("/providers/{id}/enabled")
    public ResponseEntity<ApiResponse<LlmProviderView>> toggle(@PathVariable String id,
                                                               @RequestBody EnabledToggleRequest request) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可启停模型通道"));
        }
        if (request.getEnabled() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("enabled 不能为空"));
        }
        try {
            return ResponseEntity.ok(ApiResponse.ok("通道状态已更新", gatewayService.toggleEnabled(id, request.getEnabled())));
        } catch (IllegalArgumentException e) {
            HttpStatus status = e.getMessage() != null && e.getMessage().contains("未找到")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/providers/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可删除模型通道"));
        }
        try {
            gatewayService.delete(id);
            return ResponseEntity.ok(ApiResponse.ok("自定义通道已删除", null));
        } catch (IllegalArgumentException e) {
            HttpStatus status = e.getMessage() != null && e.getMessage().contains("未找到")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/providers/{id}/probe")
    public ResponseEntity<ApiResponse<LlmProviderView>> probe(@PathVariable String id) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可测试通道连通性"));
        }
        try {
            LlmProviderView view = gatewayService.probe(id);
            String message = "SUCCESS".equals(view.getLastProbeStatus()) ? "连通性探测成功" : "连通性探测失败";
            return ResponseEntity.ok(ApiResponse.ok(message, view));
        } catch (IllegalArgumentException e) {
            HttpStatus status = e.getMessage() != null && e.getMessage().contains("未找到")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/probe")
    public ResponseEntity<ApiResponse<GatewayProbeResult>> testConnection(@RequestBody GatewayProbeRequest request) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可测试通道连通性"));
        }
        try {
            GatewayProbeResult result = gatewayService.testConnection(request);
            String message = result.isSuccess() ? "连通性测试通过" : "连通性测试失败";
            return ResponseEntity.ok(ApiResponse.ok(message, result));
        } catch (IllegalArgumentException e) {
            HttpStatus status = e.getMessage() != null && e.getMessage().contains("未找到")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/policy")
    public ResponseEntity<ApiResponse<GatewayPolicy>> savePolicy(@RequestBody GatewayPolicy policy) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可修改网关路由策略"));
        }
        try {
            return ResponseEntity.ok(ApiResponse.ok("路由策略已保存", gatewayService.savePolicy(policy)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    // ==========================================
    // Embedding 向量模型管理接口
    // ==========================================
    @GetMapping("/embeddings")
    public ResponseEntity<ApiResponse<List<EmbeddingConfigView>>> listEmbeddings() {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可查看向量模型配置"));
        }
        return ResponseEntity.ok(ApiResponse.ok(embeddingConfigService.listViews()));
    }

    /**
     * 当前生效的向量模型摘要（任意登录用户可读，用于新建知识库时展示）。
     * 只返回名称 / 模型 / 维度，不含接口地址与密钥。
     */
    @GetMapping("/embeddings/active")
    public ResponseEntity<ApiResponse<Map<String, Object>>> activeEmbedding() {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        embeddingConfigService.getActiveConfig().ifPresentOrElse(cfg -> {
            view.put("configured", true);
            view.put("name", cfg.getName());
            view.put("provider", cfg.getProvider());
            view.put("modelName", cfg.getModelName());
            view.put("dimension", cfg.getDimension());
        }, () -> {
            view.put("configured", false);
            view.put("dimension", 1024);
        });
        return ResponseEntity.ok(ApiResponse.ok(view));
    }

    @PostMapping("/embeddings")
    public ResponseEntity<ApiResponse<EmbeddingConfigView>> createEmbedding(@RequestBody EmbeddingConfigRequest request) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可配置向量模型"));
        }
        try {
            return ResponseEntity.ok(ApiResponse.ok("向量模型配置已创建", embeddingConfigService.create(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/embeddings/{id}")
    public ResponseEntity<ApiResponse<EmbeddingConfigView>> updateEmbedding(@PathVariable String id,
                                                                           @RequestBody EmbeddingConfigRequest request) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可更新向量模型"));
        }
        try {
            return ResponseEntity.ok(ApiResponse.ok("向量模型配置已更新", embeddingConfigService.update(id, request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/embeddings/{id}/activate")
    public ResponseEntity<ApiResponse<Void>> activateEmbedding(@PathVariable String id) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可切换激活向量模型"));
        }
        try {
            embeddingConfigService.activate(id);
            return ResponseEntity.ok(ApiResponse.ok("向量模型已设为当前激活生效", null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/embeddings/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteEmbedding(@PathVariable String id) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可删除向量模型"));
        }
        try {
            embeddingConfigService.delete(id);
            return ResponseEntity.ok(ApiResponse.ok("向量模型配置已删除", null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/embeddings/test-connection")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testEmbeddingConnection(@RequestBody EmbeddingConfigRequest request) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可测试向量模型连通性"));
        }
        try {
            Map<String, Object> result = embeddingConfigService.probeDraft(request);
            boolean success = Boolean.TRUE.equals(result.get("success"));
            String msg = (String) result.get("message");
            return ResponseEntity.ok(success ? ApiResponse.ok(msg, result) : ApiResponse.error(msg, result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/embeddings/{id}/probe")
    public ResponseEntity<ApiResponse<Map<String, Object>>> probeEmbedding(@PathVariable String id) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可测试向量模型连通性"));
        }
        try {
            Map<String, Object> result = embeddingConfigService.probe(id);
            boolean success = Boolean.TRUE.equals(result.get("success"));
            String msg = (String) result.get("message");
            return ResponseEntity.ok(success ? ApiResponse.ok(msg, result) : ApiResponse.error(msg, result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    // ==========================================
    // Dify 知识库引擎接入管理接口
    // ==========================================
    @GetMapping("/dify")
    public ResponseEntity<ApiResponse<List<DifyConfigView>>> listDify() {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可查看 Dify 接入配置"));
        }
        return ResponseEntity.ok(ApiResponse.ok(difyConfigService.listViews()));
    }

    @PostMapping("/dify")
    public ResponseEntity<ApiResponse<DifyConfigView>> createDify(@RequestBody DifyConfigRequest request) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可添加 Dify 实例"));
        }
        try {
            return ResponseEntity.ok(ApiResponse.ok("Dify 实例已接入", difyConfigService.create(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/dify/{id}")
    public ResponseEntity<ApiResponse<DifyConfigView>> updateDify(@PathVariable String id,
                                                                 @RequestBody DifyConfigRequest request) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可修改 Dify 配置"));
        }
        try {
            return ResponseEntity.ok(ApiResponse.ok("Dify 配置已更新", difyConfigService.update(id, request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/dify/{id}/activate")
    public ResponseEntity<ApiResponse<Void>> activateDify(@PathVariable String id) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可切换激活 Dify 实例"));
        }
        try {
            difyConfigService.activate(id);
            return ResponseEntity.ok(ApiResponse.ok("Dify 实例已设为当前激活生效", null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/dify/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteDify(@PathVariable String id) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可删除 Dify 配置"));
        }
        try {
            difyConfigService.delete(id);
            return ResponseEntity.ok(ApiResponse.ok("Dify 配置已删除", null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/ocr")
    public ResponseEntity<ApiResponse<List<OcrConfigView>>> listOcr() {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可查看 OCR 配置"));
        }
        return ResponseEntity.ok(ApiResponse.ok(ocrConfigService.listViews()));
    }

    @PostMapping("/ocr")
    public ResponseEntity<ApiResponse<OcrConfigView>> createOcr(@RequestBody OcrConfigRequest request) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可配置 OCR"));
        }
        try {
            return ResponseEntity.ok(ApiResponse.ok("OCR 配置已创建", ocrConfigService.create(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/ocr/{id}")
    public ResponseEntity<ApiResponse<OcrConfigView>> updateOcr(@PathVariable String id,
                                                               @RequestBody OcrConfigRequest request) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可更新 OCR"));
        }
        try {
            return ResponseEntity.ok(ApiResponse.ok("OCR 配置已更新", ocrConfigService.update(id, request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/ocr/{id}/activate")
    public ResponseEntity<ApiResponse<Void>> activateOcr(@PathVariable String id) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可切换激活 OCR"));
        }
        try {
            ocrConfigService.activate(id);
            return ResponseEntity.ok(ApiResponse.ok("OCR 已设为当前激活生效", null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/ocr/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteOcr(@PathVariable String id) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可删除 OCR 配置"));
        }
        try {
            ocrConfigService.delete(id);
            return ResponseEntity.ok(ApiResponse.ok("OCR 配置已删除", null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/ocr/test-connection")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testOcrConnection(@RequestBody OcrConfigRequest request) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可测试 OCR 连通性"));
        }
        try {
            Map<String, Object> result = ocrConfigService.probeDraft(request);
            boolean success = Boolean.TRUE.equals(result.get("success"));
            String msg = (String) result.get("message");
            return ResponseEntity.ok(success ? ApiResponse.ok(msg, result) : ApiResponse.error(msg, result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/ocr/{id}/probe")
    public ResponseEntity<ApiResponse<Map<String, Object>>> probeOcr(@PathVariable String id) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可测试 OCR 连通性"));
        }
        try {
            Map<String, Object> result = ocrConfigService.probe(id);
            boolean success = Boolean.TRUE.equals(result.get("success"));
            String msg = (String) result.get("message");
            return ResponseEntity.ok(success ? ApiResponse.ok(msg, result) : ApiResponse.error(msg, result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/dify/test-connection")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testDifyConnection(@RequestBody DifyConfigRequest request) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可测试 Dify 连通性"));
        }
        try {
            Map<String, Object> result = difyConfigService.probeDraft(request);
            boolean success = Boolean.TRUE.equals(result.get("success"));
            String msg = (String) result.get("message");
            return ResponseEntity.ok(success ? ApiResponse.ok(msg, result) : ApiResponse.error(msg, result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/dify/{id}/probe")
    public ResponseEntity<ApiResponse<Map<String, Object>>> probeDify(@PathVariable String id) {
        if (!checkAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("权限不足：仅超级管理员可测试 Dify 连通性"));
        }
        try {
            Map<String, Object> result = difyConfigService.probe(id);
            boolean success = Boolean.TRUE.equals(result.get("success"));
            String msg = (String) result.get("message");
            return ResponseEntity.ok(success ? ApiResponse.ok(msg, result) : ApiResponse.error(msg, result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
