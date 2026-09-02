package com.example.agentplatform.service;

import java.time.LocalDate;

final class TimeRange {

    private TimeRange() {
    }

    static LocalDate[] resolve(String range) {
        LocalDate end = LocalDate.now();
        if (range == null || range.isBlank() || "7days".equalsIgnoreCase(range)) {
            return new LocalDate[]{end.minusDays(6), end};
        }
        if ("today".equalsIgnoreCase(range)) {
            return new LocalDate[]{end, end};
        }
        if ("30days".equalsIgnoreCase(range)) {
            return new LocalDate[]{end.minusDays(29), end};
        }
        if ("all".equalsIgnoreCase(range)) {
            return new LocalDate[]{null, end};
        }
        return new LocalDate[]{end.minusDays(6), end};
    }
}
