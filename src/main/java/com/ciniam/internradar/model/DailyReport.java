package com.ciniam.internradar.model;

import java.util.List;

/**
 * 日报结构化结果，由 ReportAgent 按 JSON Schema 生成
 */
public record DailyReport(
        String summary,
        List<CompletedItem> completed,
        List<String> problems,
        List<String> tomorrowPlan,
        List<String> needHelp
) {

    /**
     * 单条产出，evidence 要求给出对应的提交短哈希，便于事实核对
     */
    public record CompletedItem(String content, List<String> evidence) {
    }
}
