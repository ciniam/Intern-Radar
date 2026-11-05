package com.ciniam.internradar.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ciniam.internradar.entity.WorkRecordEntity;
import com.ciniam.internradar.service.RiskService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务查询工具。返回的是未关闭任务的快照，同一任务只保留最新一条
 */
public class TaskQueryTool {

    private static final int MAX_LINES = 40;

    private final RiskService riskService;
    private final ObjectMapper objectMapper;
    private final ToolScope scope;

    public TaskQueryTool(RiskService riskService, ObjectMapper objectMapper, ToolScope scope) {
        this.riskService = riskService;
        this.objectMapper = objectMapper;
        this.scope = scope;
    }

    @Tool(description = "查询某人当前未关闭的任务，含进度、已耗工时与截止日期")
    public String queryOpenTasks(
            @ToolParam(description = "人员标识，留空表示查自己")
            String userId) {
        String target = scope.resolve(userId);
        if (target == null) {
            return ToolScope.denied();
        }
        List<WorkRecordEntity> tasks = riskService.openTasks(target, LocalDate.now());
        if (tasks.isEmpty()) {
            return "当前没有未关闭的任务。";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(target).append(" 当前未关闭任务 ").append(tasks.size()).append(" 条：\n");
        int index = 0;
        for (WorkRecordEntity task : tasks) {
            if (index++ >= MAX_LINES) {
                break;
            }
            Map<String, Object> metadata = metadata(task);
            builder.append("- ").append(task.getTitle())
                    .append(" 状态 ").append(task.getStatus() == null ? "" : task.getStatus())
                    .append(" 最后变更 ").append(task.getOccurredAt())
                    .append(" 截止 ").append(metadata.getOrDefault("deadline", "未设置"))
                    .append(" 进度 ").append(metadata.getOrDefault("progress", "未知"))
                    .append(" 已耗 ").append(metadata.getOrDefault("consumed", "未知"))
                    .append('\n');
        }
        return builder.toString();
    }

    private Map<String, Object> metadata(WorkRecordEntity record) {
        String json = record.getMetadata();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(), entry.getValue().asText()));
            return map;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
