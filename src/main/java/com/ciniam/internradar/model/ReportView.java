package com.ciniam.internradar.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 报告查询视图，content 为解析后的结构化日报
 */
public record ReportView(
        Long id,
        String userId,
        String type,
        LocalDate periodStart,
        LocalDate periodEnd,
        DailyReport content,
        Integer tokensUsed,
        LocalDateTime generatedAt,
        String status
) {
}
