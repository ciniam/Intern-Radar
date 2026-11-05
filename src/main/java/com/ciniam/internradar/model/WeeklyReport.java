package com.ciniam.internradar.model;

import java.util.List;

/**
 * 面向导师的周报
 */
public record WeeklyReport(
        String summary,
        List<String> outputs,
        List<String> trendNotes,
        List<String> nextWeekFocus,
        List<String> risks
) {
}
