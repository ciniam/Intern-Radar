package com.ciniam.internradar.model;

/**
 * 个人中心展示的本人统计，按固定窗口汇总
 */
public record ProfileStats(
        int windowDays,
        int recordCount,
        int reportCount,
        int openAlertCount,
        int conversationCount,
        int questionCount
) {
}
