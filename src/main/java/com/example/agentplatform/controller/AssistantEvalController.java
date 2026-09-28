package com.example.agentplatform.controller;

import com.example.agentplatform.assistant.AssistantEvalCase;
import com.example.agentplatform.assistant.AssistantEvalService;
import com.example.agentplatform.model.ApiResponse;
import com.example.agentplatform.model.AssistantEvalRun;
import com.example.agentplatform.security.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台 AI 助手评测（仅超级管理员）：查看用例、发起评测、查看历史与逐条结果。
 * 评测会真实调用模型通道并消耗 token。
 */
@RestController
@RequestMapping("/api/assistant/evals")
public class AssistantEvalController {

    private final AssistantEvalService evalService;

    public AssistantEvalController(AssistantEvalService evalService) {
        this.evalService = evalService;
    }

    @GetMapping("/cases")
    public ResponseEntity<ApiResponse<List<AssistantEvalCase>>> cases() {
        if (!isSuperAdmin()) {
            return forbidden();
        }
        return ResponseEntity.ok(ApiResponse.ok(evalService.cases()));
    }

    /** 请求体可选 {"caseIds": [...]}，为空表示运行全部用例。 */
    @PostMapping("/runs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> start(@RequestBody(required = false) Map<String, List<String>> body) {
        if (!isSuperAdmin()) {
            return forbidden();
        }
        try {
            AssistantEvalRun run = evalService.start(CurrentActor.get(), body != null ? body.get("caseIds") : null);
            return ResponseEntity.ok(ApiResponse.ok(overview(run)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/runs")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> runs() {
        if (!isSuperAdmin()) {
            return forbidden();
        }
        return ResponseEntity.ok(ApiResponse.ok(evalService.recentRuns().stream().map(this::overview).toList()));
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<ApiResponse<AssistantEvalRun>> run(@PathVariable String id) {
        if (!isSuperAdmin()) {
            return forbidden();
        }
        return evalService.findRun(id)
                .map(run -> ResponseEntity.ok(ApiResponse.ok(run)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("评测记录不存在")));
    }

    /** 列表只返回汇总，不含逐条结果（体积大） */
    private Map<String, Object> overview(AssistantEvalRun run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", run.getId());
        m.put("status", run.getStatus());
        m.put("triggeredBy", run.getTriggeredBy());
        m.put("model", run.getModel());
        m.put("total", run.getTotal());
        m.put("completed", run.getCompleted());
        m.put("passed", run.getPassed());
        m.put("failed", run.getFailed());
        m.put("skipped", run.getSkipped());
        m.put("errored", run.getErrored());
        m.put("promptTokens", run.getPromptTokens());
        m.put("completionTokens", run.getCompletionTokens());
        m.put("durationMs", run.getDurationMs());
        m.put("error", run.getError());
        m.put("startedAt", run.getStartedAt());
        m.put("finishedAt", run.getFinishedAt());
        return m;
    }

    private static boolean isSuperAdmin() {
        CurrentActor actor = CurrentActor.get();
        return actor != null && actor.isSuperAdmin();
    }

    private static <T> ResponseEntity<ApiResponse<T>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("权限不足：仅超级管理员可以使用助手评测"));
    }
}
