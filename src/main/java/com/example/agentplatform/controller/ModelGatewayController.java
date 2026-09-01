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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/model-gateway")
public class ModelGatewayController {

    private final LlmGatewayService gatewayService;

    public ModelGatewayController(LlmGatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @GetMapping({ "", "/overview" })
    public ResponseEntity<ApiResponse<GatewayOverview>> overview() {
        return ResponseEntity.ok(ApiResponse.ok(gatewayService.overview()));
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
        try {
            return ResponseEntity.ok(ApiResponse.ok("路由策略已保存", gatewayService.savePolicy(policy)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
