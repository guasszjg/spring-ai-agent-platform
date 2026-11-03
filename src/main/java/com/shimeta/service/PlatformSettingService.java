package com.shimeta.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** 平台级设置（键值存储，带进程内缓存） */
@Service
public class PlatformSettingService {

    private static final Logger log = LoggerFactory.getLogger(PlatformSettingService.class);

    /** 单次回答最多调用工具的轮数：只限制模型回答一个问题时反复调用工具，不限制提问次数 */
    public static final String MAX_TOOL_ROUNDS = "chat.max_tool_rounds";
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 5;
    public static final int MIN_TOOL_ROUNDS = 1;
    public static final int MAX_TOOL_ROUNDS_LIMIT = 10;

    private final JdbcTemplate jdbcTemplate;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public PlatformSettingService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int maxToolRounds() {
        String raw = get(MAX_TOOL_ROUNDS);
        try {
            int value = raw == null ? DEFAULT_MAX_TOOL_ROUNDS : Integer.parseInt(raw.trim());
            return Math.max(MIN_TOOL_ROUNDS, Math.min(MAX_TOOL_ROUNDS_LIMIT, value));
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_TOOL_ROUNDS;
        }
    }

    public int setMaxToolRounds(int rounds) {
        if (rounds < MIN_TOOL_ROUNDS || rounds > MAX_TOOL_ROUNDS_LIMIT) {
            throw new IllegalArgumentException("单次回答最多调用工具轮数需在 " + MIN_TOOL_ROUNDS + "～" + MAX_TOOL_ROUNDS_LIMIT + " 之间");
        }
        set(MAX_TOOL_ROUNDS, String.valueOf(rounds));
        return rounds;
    }

    private String get(String key) {
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT setting_value FROM platform_settings WHERE setting_key = ?", String.class, key);
            String value = rows.isEmpty() ? null : rows.get(0);
            if (value != null) {
                cache.put(key, value);
            }
            return value;
        } catch (Exception e) {
            log.warn("读取平台设置失败 [{}]: {}", key, e.getMessage());
            return null;
        }
    }

    private void set(String key, String value) {
        jdbcTemplate.update("""
                INSERT INTO platform_settings (setting_key, setting_value, updated_at) VALUES (?, ?, ?)
                ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value, updated_at = EXCLUDED.updated_at
                """, key, value, LocalDateTime.now());
        cache.put(key, value);
    }
}
