package com.shimeta.controller;

import com.shimeta.model.Agent;
import com.shimeta.model.ApiResponse;
import com.shimeta.model.PlatformToolView;
import com.shimeta.repository.AgentRepository;
import com.shimeta.security.CurrentActor;
import com.shimeta.service.PlatformSettingService;
import com.shimeta.service.PlatformToolService;
import com.shimeta.service.ResourceAuthorizationService;
import com.shimeta.service.ToolUsageService;
import com.shimeta.service.ToolUsageService.ToolStats;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具管理页：工具调用统计（次数、成功率、耗时、预估费用）与「单次回答最多调用工具轮数」设置。
 * 超级管理员看全平台的调用；其他用户只看自己可见的智能体产生的调用。
 */
@RestController
@RequestMapping("/api/platform-tools")
public class ToolUsageController {

    private final ToolUsageService usageService;
    private final PlatformToolService platformToolService;
    private final PlatformSettingService settingService;
    private final AgentRepository agentRepository;
    private final ResourceAuthorizationService authorizationService;

    public ToolUsageController(ToolUsageService usageService, PlatformToolService platformToolService,
                               PlatformSettingService settingService, AgentRepository agentRepository,
                               ResourceAuthorizationService authorizationService) {
        this.usageService = usageService;
        this.platformToolService = platformToolService;
        this.settingService = settingService;
        this.agentRepository = agentRepository;
        this.authorizationService = authorizationService;
    }

    /** 各工具的调用统计 */
    @GetMapping("/usage")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> usage() {
        CurrentActor actor = CurrentActor.get();
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("未登录"));
        }
        Map<String, ToolStats> stats = usageService.summary(visibleAgentIds(actor));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PlatformToolView tool : platformToolService.list()) {
            rows.add(statsView(tool, stats.getOrDefault(tool.getCode(), ToolStats.EMPTY)));
        }
        return ResponseEntity.ok(ApiResponse.ok(rows));
    }

    /** 单个工具的详细统计：汇总、本月按智能体拆分、近 14 天每日调用次数 */
    @GetMapping("/{id}/usage")
    public ResponseEntity<ApiResponse<Map<String, Object>>> toolUsage(@PathVariable String id) {
        CurrentActor actor = CurrentActor.get();
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("未登录"));
        }
        PlatformToolView tool;
        try {
            tool = platformToolService.get(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
        List<String> agentIds = visibleAgentIds(actor);
        Map<String, Object> result = statsView(tool,
                usageService.summary(agentIds).getOrDefault(tool.getCode(), ToolStats.EMPTY));

        Map<String, String> agentNames = agentRepository.findAll().stream()
                .collect(Collectors.toMap(Agent::getId, a -> a.getName() == null ? a.getId() : a.getName(), (a, b) -> a));
        List<Map<String, Object>> byAgent = new ArrayList<>();
        usageService.monthByAgent(tool.getCode(), agentIds).forEach((agentId, counts) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentId", agentId);
            row.put("agentName", agentId.isBlank() ? "未关联智能体" : agentNames.getOrDefault(agentId, "已删除的智能体"));
            row.put("calls", counts[0]);
            row.put("failures", counts[1]);
            row.put("cost", cost(tool.getUnitPrice(), counts[0] - counts[1]));
            byAgent.add(row);
        });
        result.put("byAgent", byAgent);

        List<Map<String, Object>> daily = new ArrayList<>();
        usageService.daily(tool.getCode(), 14, agentIds).forEach((date, calls) ->
                daily.add(Map.of("date", date.toString(), "calls", calls)));
        result.put("daily", daily);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/settings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> settings() {
        return ResponseEntity.ok(ApiResponse.ok(settingsView()));
    }

    @PutMapping("/settings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateSettings(@RequestBody Map<String, Object> body) {
        CurrentActor actor = CurrentActor.get();
        if (actor == null || !actor.isSuperAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("权限不足：仅超级管理员可修改平台设置"));
        }
        Object raw = body == null ? null : body.get("maxToolRounds");
        if (!(raw instanceof Number number) || number.doubleValue() != Math.floor(number.doubleValue())) {
            return ResponseEntity.badRequest().body(ApiResponse.error("maxToolRounds 需为整数"));
        }
        try {
            settingService.setMaxToolRounds(number.intValue());
            return ResponseEntity.ok(ApiResponse.ok("已保存，对新的提问立即生效", settingsView()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    private Map<String, Object> settingsView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("maxToolRounds", settingService.maxToolRounds());
        view.put("min", PlatformSettingService.MIN_TOOL_ROUNDS);
        view.put("max", PlatformSettingService.MAX_TOOL_ROUNDS_LIMIT);
        view.put("default", PlatformSettingService.DEFAULT_MAX_TOOL_ROUNDS);
        return view;
    }

    /** null 表示不限（超级管理员） */
    private List<String> visibleAgentIds(CurrentActor actor) {
        if (actor.isSuperAdmin()) {
            return null;
        }
        return agentRepository.findAll().stream()
                .filter(agent -> authorizationService.canViewAgent(actor, agent))
                .map(Agent::getId)
                .toList();
    }

    private static Map<String, Object> statsView(PlatformToolView tool, ToolStats stats) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("toolId", tool.getId());
        row.put("toolCode", tool.getCode());
        row.put("name", tool.getName());
        row.put("today", stats.today());
        row.put("month", stats.month());
        row.put("total", stats.total());
        row.put("monthFailures", stats.monthFailures());
        row.put("monthSuccessRate", stats.monthSuccessRate());
        row.put("monthAvgLatencyMs", stats.monthAvgLatencyMs());
        row.put("unitPrice", tool.getUnitPrice());
        // 失败的调用一般不计费，预估费用只按成功次数算
        row.put("monthCost", cost(tool.getUnitPrice(), stats.month() - stats.monthFailures()));
        return row;
    }

    private static BigDecimal cost(BigDecimal unitPrice, long successfulCalls) {
        if (unitPrice == null || successfulCalls <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return unitPrice.multiply(BigDecimal.valueOf(successfulCalls)).setScale(2, RoundingMode.HALF_UP);
    }
}
