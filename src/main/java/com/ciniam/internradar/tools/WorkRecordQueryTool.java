package com.ciniam.internradar.tools;

import com.ciniam.internradar.entity.WorkRecordEntity;
import com.ciniam.internradar.mapper.WorkRecordMapper;
import com.ciniam.internradar.service.WorkRecordVisibility;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 工作记录查询工具。提交、任务与聊天摘要都在同一张表，靠 source 区分
 */
public class WorkRecordQueryTool {

    private static final int MAX_RANGE_DAYS = 31;
    private static final int MAX_LINES = 60;

    private final WorkRecordMapper workRecordMapper;
    private final WorkRecordVisibility visibility;
    private final ToolScope scope;

    public WorkRecordQueryTool(WorkRecordMapper workRecordMapper,
                               WorkRecordVisibility visibility,
                               ToolScope scope) {
        this.workRecordMapper = workRecordMapper;
        this.visibility = visibility;
        this.scope = scope;
    }

    @Tool(description = "查询某人的工作记录，含代码提交、任务与工时日志。日期用 2026-10-15 这种格式")
    public String queryWorkRecords(
            @ToolParam(description = "人员标识，留空表示查自己")
            String userId,
            @ToolParam(description = "开始日期，留空表示只看今天")
            String startDate,
            @ToolParam(description = "结束日期，留空表示与开始日期同一天")
            String endDate,
            @ToolParam(description = "数据来源过滤，取值 git、zentao、feishu-chat，留空表示全部")
            String source) {
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
        List<WorkRecordEntity> records = workRecordMapper.selectByUserAndRange(target,
                start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        boolean isOwner = target.equals(scope.ownerUserId());
        records = visibility.visibleTo(isOwner, records);
        if (source != null && !source.isBlank()) {
            String wanted = source.trim().toLowerCase();
            records = records.stream()
                    .filter(record -> record.getSource() != null
                            && record.getSource().toLowerCase().contains(wanted))
                    .toList();
        }
        if (records.isEmpty()) {
            return "该区间内没有查到工作记录。可以先把日期放宽再查，或确认数据是否已采集。";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(target).append(' ').append(start).append(" 至 ").append(end)
                .append(" 共 ").append(records.size()).append(" 条：\n");
        int index = 0;
        for (WorkRecordEntity record : records) {
            if (index++ >= MAX_LINES) {
                builder.append("（还有 ").append(records.size() - MAX_LINES).append(" 条未列出）\n");
                break;
            }
            builder.append("- [").append(record.getSource()).append('/').append(record.getType()).append("] ")
                    .append(record.getOccurredAt()).append(" 状态 ").append(text(record.getStatus()))
                    .append(" 说明 ").append(text(record.getTitle()))
                    .append(" 详情 ").append(clip(record.getContent())).append('\n');
        }
        return builder.toString();
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private String clip(String value) {
        if (value == null) {
            return "";
        }
        String plain = value.replaceAll("\\s+", " ").trim();
        return plain.length() > 200 ? plain.substring(0, 200) + "…" : plain;
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.trim().substring(0, Math.min(10, value.trim().length())));
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
