package com.example.agentplatform.assistant;

import com.example.agentplatform.repository.AssistantMessageRepository;
import com.example.agentplatform.security.CurrentActor;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 平台 AI 助手的 token 用量。范围与概览页一致：超级管理员看全站，其余用户只看本人。
 */
@Service
public class AssistantUsageService {

    private final AssistantMessageRepository messageRepository;

    public AssistantUsageService(AssistantMessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /** [start, end] 闭区间内的助手用量；actor 为空时返回空用量。 */
    public Usage usage(CurrentActor actor, LocalDate start, LocalDate end) {
        Usage usage = new Usage();
        if (actor == null || start == null || end == null || end.isBefore(start)) {
            return usage;
        }
        List<Object[]> rows = actor.isSuperAdmin()
                ? messageRepository.sumTokensByDayAndModel(start.atStartOfDay(), end.plusDays(1).atStartOfDay())
                : actor.getUserId() == null ? List.of()
                : messageRepository.sumTokensByDayAndModelForUser(actor.getUserId(),
                        start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        for (Object[] row : rows) {
            LocalDate day = toLocalDate(row[0]);
            String model = row[1] != null && !row[1].toString().isBlank() ? row[1].toString() : "未知模型";
            long prompt = toLong(row[2]);
            long completion = toLong(row[3]);
            usage.promptTokens += prompt;
            usage.completionTokens += completion;
            usage.tokensByModel.merge(model, prompt + completion, Long::sum);
            if (day != null) {
                long[] bucket = usage.byDay.computeIfAbsent(day, d -> new long[]{0L, 0L});
                bucket[0] += prompt;
                bucket[1] += completion;
            }
        }
        return usage;
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate d) {
            return d;
        }
        if (value instanceof Date d) {
            return d.toLocalDate();
        }
        if (value instanceof java.util.Date d) {
            return new Date(d.getTime()).toLocalDate();
        }
        return value != null ? LocalDate.parse(value.toString().substring(0, 10)) : null;
    }

    private static long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    public static class Usage {
        private long promptTokens;
        private long completionTokens;
        private final Map<String, Long> tokensByModel = new LinkedHashMap<>();
        /** 日期 → [prompt, completion] */
        private final Map<LocalDate, long[]> byDay = new TreeMap<>();

        public long getPromptTokens() { return promptTokens; }
        public long getCompletionTokens() { return completionTokens; }
        public long getTotalTokens() { return promptTokens + completionTokens; }
        public Map<String, Long> getTokensByModel() { return tokensByModel; }
        public Map<LocalDate, long[]> getByDay() { return byDay; }
    }
}
