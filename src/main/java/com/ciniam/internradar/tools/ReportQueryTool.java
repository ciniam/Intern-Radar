package com.ciniam.internradar.tools;

import com.ciniam.internradar.model.ReportDetail;
import com.ciniam.internradar.service.ReportService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 历史报告查询工具。用于解读产出变化、衔接上周结论
 */
public class ReportQueryTool {

    private static final int MAX_RANGE_DAYS = 31;
    private static final int MAX_LINES = 12;
    private static final int CONTENT_LIMIT = 800;

    private final ReportService reportService;
    private final ToolScope scope;

    public ReportQueryTool(ReportService reportService, ToolScope scope) {
        this.reportService = reportService;
        this.scope = scope;
    }

    @Tool(description = "查询某人已生成的历史报告，可取日报、今日计划与周报")
    public String queryReports(
            @ToolParam(description = "人员标识，留空表示查自己")
            String userId,
            @ToolParam(description = "报告类型，取值 DAILY、PLAN、WEEKLY，留空表示全部")
            String type,
            @ToolParam(description = "开始日期，例如 2026-10-08")
            String startDate,
            @ToolParam(description = "结束日期，留空表示与开始日期同一天")
            String endDate) {
        String target = scope.resolve(userId);
        if (target == null) {
            return ToolScope.denied();
        }
        LocalDate start = parseDate(startDate, LocalDate.now());
        LocalDate end = parseDate(endDate, start);
        if (end.isBefore(start)) {
            return "结束日期早于开始日期，请重新给出日期。";
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            return "查询区间最长 " + MAX_RANGE_DAYS + " 天，请缩小范围。";
        }
        String wanted = type == null || type.isBlank() ? null : type.trim().toUpperCase();
        List<ReportDetail> reports = reportService.listReports(target, start, end, wanted);
        if (reports.isEmpty()) {
            return "该区间内没有已生成的历史报告。";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(target).append(' ').append(start).append(" 至 ").append(end)
                .append(" 共 ").append(reports.size()).append(" 份报告：\n");
        int index = 0;
        for (ReportDetail report : reports) {
            if (index++ >= MAX_LINES) {
                builder.append("（还有 ").append(reports.size() - MAX_LINES).append(" 份未列出）\n");
                break;
            }
            String content = report.content() == null ? "" : report.content().toString();
            builder.append("[").append(report.type()).append("] ")
                    .append(report.periodStart()).append(" 至 ").append(report.periodEnd()).append('\n')
                    .append(clip(content)).append('\n');
        }
        return builder.toString();
    }

    private String clip(String content) {
        String plain = content.replaceAll("\\s+", " ").trim();
        return plain.length() > CONTENT_LIMIT ? plain.substring(0, CONTENT_LIMIT) + "…" : plain;
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        try {
            return LocalDate.parse(trimmed.substring(0, Math.min(10, trimmed.length())));
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
