package com.ciniam.internradar.model;

import java.time.LocalDate;

/**
 * 禅道侧的任务视图，供风险规则使用
 */
public record TaskItem(
        String taskId,
        String title,
        String status,
        Integer progress,
        LocalDate deadline,
        LocalDate lastChangedDate,
        String owner
) {
}
