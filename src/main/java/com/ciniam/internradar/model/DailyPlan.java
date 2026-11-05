package com.ciniam.internradar.model;

import java.util.List;

/**
 * 早推送的今日计划
 */
public record DailyPlan(
        String summary,
        List<String> focusItems,
        List<String> todoItems,
        List<String> riskNotes
) {
}
