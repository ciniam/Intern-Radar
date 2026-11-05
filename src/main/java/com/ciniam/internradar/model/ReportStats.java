package com.ciniam.internradar.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 按天 × 按来源的工作记录统计。
 * 天序列覆盖 start~end 整个区间，没有记录的那天也会出现，total 为 0。
 * 来源只报实际出现过的键，前端按自己的口径补 0。
 */
public record ReportStats(
        String userId,
        LocalDate start,
        LocalDate end,
        List<Day> days
) {

    public record Day(LocalDate date, int total, Map<String, Integer> bySource) {
    }
}
