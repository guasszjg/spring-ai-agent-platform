package com.shimeta.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具调用统计：每次工具执行记一笔（按日、工具、智能体汇总），供工具管理页查看调用次数、成功率、耗时和预估费用。
 * 统计写入失败只记日志，不影响对话。
 */
@Service
public class ToolUsageService {

    private static final Logger log = LoggerFactory.getLogger(ToolUsageService.class);

    private final JdbcTemplate jdbcTemplate;

    public ToolUsageService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(String toolCode, String agentId, boolean success, long latencyMs) {
        if (toolCode == null || toolCode.isBlank()) {
            return;
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO tool_usage_daily (stat_date, tool_code, agent_id, calls, failures, latency_sum_ms)
                    VALUES (?, ?, ?, 1, ?, ?)
                    ON CONFLICT (stat_date, tool_code, agent_id) DO UPDATE SET
                        calls = tool_usage_daily.calls + 1,
                        failures = tool_usage_daily.failures + EXCLUDED.failures,
                        latency_sum_ms = tool_usage_daily.latency_sum_ms + EXCLUDED.latency_sum_ms
                    """, Date.valueOf(LocalDate.now()), toolCode, agentId == null ? "" : agentId,
                    success ? 0 : 1, Math.max(0, latencyMs));
        } catch (Exception e) {
            log.warn("记录工具调用统计失败 [{}]: {}", toolCode, e.getMessage());
        }
    }

    /** 单个工具的汇总数字 */
    public record ToolStats(long today, long month, long total, long monthFailures, long monthLatencySumMs) {
        public static final ToolStats EMPTY = new ToolStats(0, 0, 0, 0, 0);

        public Double monthSuccessRate() {
            return month == 0 ? null : (month - monthFailures) * 1.0 / month;
        }

        public Long monthAvgLatencyMs() {
            return month == 0 ? null : monthLatencySumMs / month;
        }
    }

    /**
     * 各工具的汇总。agentIds 为 null 表示不限智能体（超级管理员），否则只统计这些智能体的调用。
     */
    public Map<String, ToolStats> summary(Collection<String> agentIds) {
        Map<String, ToolStats> result = new LinkedHashMap<>();
        if (agentIds != null && agentIds.isEmpty()) {
            return result;
        }
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        List<Object> args = new ArrayList<>(List.of(Date.valueOf(today), Date.valueOf(monthStart), Date.valueOf(monthStart), Date.valueOf(monthStart)));
        String filter = agentFilter(agentIds, args);
        jdbcTemplate.query("""
                SELECT tool_code,
                       SUM(CASE WHEN stat_date = ? THEN calls ELSE 0 END) AS today_calls,
                       SUM(CASE WHEN stat_date >= ? THEN calls ELSE 0 END) AS month_calls,
                       SUM(calls) AS total_calls,
                       SUM(CASE WHEN stat_date >= ? THEN failures ELSE 0 END) AS month_failures,
                       SUM(CASE WHEN stat_date >= ? THEN latency_sum_ms ELSE 0 END) AS month_latency
                FROM tool_usage_daily
                """ + filter + " GROUP BY tool_code", rs -> {
            result.put(rs.getString("tool_code"), new ToolStats(rs.getLong("today_calls"), rs.getLong("month_calls"),
                    rs.getLong("total_calls"), rs.getLong("month_failures"), rs.getLong("month_latency")));
        }, args.toArray());
        return result;
    }

    /** 某个工具本月按智能体拆分：agentId → [调用次数, 失败次数] */
    public Map<String, long[]> monthByAgent(String toolCode, Collection<String> agentIds) {
        Map<String, long[]> result = new LinkedHashMap<>();
        if (agentIds != null && agentIds.isEmpty()) {
            return result;
        }
        List<Object> args = new ArrayList<>(List.of(toolCode, Date.valueOf(LocalDate.now().withDayOfMonth(1))));
        String filter = agentFilter(agentIds, args);
        jdbcTemplate.query("SELECT agent_id, SUM(calls) AS calls, SUM(failures) AS failures FROM tool_usage_daily "
                        + "WHERE tool_code = ? AND stat_date >= ?" + filter.replace("WHERE", "AND")
                        + " GROUP BY agent_id ORDER BY calls DESC",
                rs -> {
                    result.put(rs.getString("agent_id"), new long[]{rs.getLong("calls"), rs.getLong("failures")});
                }, args.toArray());
        return result;
    }

    /** 某个工具最近 days 天的每日调用次数（含 0 次的日期，按日期正序） */
    public Map<LocalDate, Long> daily(String toolCode, int days, Collection<String> agentIds) {
        Map<LocalDate, Long> result = new LinkedHashMap<>();
        LocalDate start = LocalDate.now().minusDays(Math.max(1, days) - 1L);
        for (LocalDate d = start; !d.isAfter(LocalDate.now()); d = d.plusDays(1)) {
            result.put(d, 0L);
        }
        if (agentIds != null && agentIds.isEmpty()) {
            return result;
        }
        List<Object> args = new ArrayList<>(List.of(toolCode, Date.valueOf(start)));
        String filter = agentFilter(agentIds, args);
        jdbcTemplate.query("SELECT stat_date, SUM(calls) AS calls FROM tool_usage_daily WHERE tool_code = ? AND stat_date >= ?"
                        + filter.replace("WHERE", "AND") + " GROUP BY stat_date",
                rs -> {
                    result.put(rs.getDate("stat_date").toLocalDate(), rs.getLong("calls"));
                }, args.toArray());
        return result;
    }

    private static String agentFilter(Collection<String> agentIds, List<Object> args) {
        if (agentIds == null) {
            return "";
        }
        args.addAll(agentIds);
        return " WHERE agent_id IN (" + String.join(",", java.util.Collections.nCopies(agentIds.size(), "?")) + ")";
    }
}
