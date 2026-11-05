package com.ciniam.internradar.service;

import com.ciniam.internradar.agent.AgentStepListener;
import com.ciniam.internradar.entity.AgentRunEntity;
import com.ciniam.internradar.entity.AgentStepEntity;
import com.ciniam.internradar.mapper.AgentRunMapper;
import com.ciniam.internradar.mapper.AgentStepMapper;
import com.ciniam.internradar.model.AgentRunView;
import com.ciniam.internradar.model.AgentStepView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 智能体运行轨迹。写轨迹失败不影响主流程，只记日志
 */
@Service
public class AgentTraceService {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceService.class);

    private static final int TITLE_LIMIT = 200;
    private static final int ARGS_LIMIT = 1000;
    private static final int TEXT_LIMIT = 2000;
    private static final int DEFAULT_LIST_LIMIT = 50;

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    private final AgentRunMapper runMapper;
    private final AgentStepMapper stepMapper;

    private final TeamScope teamScope;

    public AgentTraceService(AgentRunMapper runMapper, AgentStepMapper stepMapper, TeamScope teamScope) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.teamScope = teamScope;
    }

    /**
     * 开启一次运行，返回运行标识。落库失败返回 null，调用方按「不记录轨迹」继续
     */
    public Long begin(String userId, String scene, String title) {
        try {
            AgentRunEntity entity = AgentRunEntity.builder()
                    .teamId(teamScope.of(userId))
                    .userId(userId)
                    .scene(scene)
                    .title(clip(title, TITLE_LIMIT))
                    .status(STATUS_RUNNING)
                    .stepCount(0)
                    .promptTokens(0)
                    .completionTokens(0)
                    .durationMs(0L)
                    .createdAt(LocalDateTime.now())
                    .build();
            runMapper.insert(entity);
            return entity.getId();
        } catch (Exception e) {
            log.warn("轨迹开启失败，场景 {}：{}", scene, e.getMessage());
            return null;
        }
    }

    /**
     * 步骤出口，直接挂到代理上
     */
    public AgentStepListener listener(Long runId) {
        return listener(runId, 0);
    }

    /**
     * 同一次运行里重跑代理时，用偏移量接住上一步的序号，避免步骤号重复
     */
    public AgentStepListener listener(Long runId, int stepOffset) {
        if (runId == null) {
            return null;
        }
        return (stepNo, thought, toolName, toolArgs, toolResult, durationMs) ->
                recordStep(runId, stepNo + stepOffset, thought, toolName, toolArgs, toolResult, durationMs);
    }

    public void finish(Long runId, int stepCount, int promptTokens, int completionTokens,
                       long durationMs, boolean success, String error) {
        if (runId == null) {
            return;
        }
        try {
            runMapper.finish(runId, success ? STATUS_SUCCESS : STATUS_FAILED, stepCount,
                    promptTokens, completionTokens, durationMs, clip(error, 480));
        } catch (Exception e) {
            log.warn("轨迹收尾失败，运行 {}：{}", runId, e.getMessage());
        }
    }

    public List<AgentRunView> list(String userId, String scene, Integer limit) {
        int size = limit == null || limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, 200);
        List<AgentRunView> runs = new ArrayList<>();
        for (AgentRunEntity entity : runMapper.selectByUser(userId, scene, size)) {
            runs.add(toView(entity, null));
        }
        return runs;
    }

    /**
     * 运行详情，带上每一步
     */
    public AgentRunView detail(Long runId) {
        AgentRunEntity entity = runId == null ? null : runMapper.selectById(runId);
        if (entity == null) {
            return null;
        }
        List<AgentStepView> steps = new ArrayList<>();
        for (AgentStepEntity step : stepMapper.selectByRun(runId)) {
            steps.add(new AgentStepView(step.getId(), step.getStepNo() == null ? 0 : step.getStepNo(),
                    step.getThought(), step.getToolName(), step.getToolArgs(), step.getToolResult(),
                    step.getDurationMs() == null ? 0L : step.getDurationMs(), step.getCreatedAt()));
        }
        return toView(entity, steps);
    }

    private void recordStep(Long runId, int stepNo, String thought, String toolName,
                            String toolArgs, String toolResult, long durationMs) {
        try {
            stepMapper.insert(AgentStepEntity.builder()
                    .teamId(teamScope.current())
                    .runId(runId)
                    .stepNo(stepNo)
                    .thought(clip(thought, TEXT_LIMIT))
                    .toolName(clip(toolName, 64))
                    .toolArgs(clip(toolArgs, ARGS_LIMIT))
                    .toolResult(clip(toolResult, TEXT_LIMIT))
                    .durationMs(durationMs)
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("轨迹步骤写入失败，运行 {}：{}", runId, e.getMessage());
        }
    }

    private AgentRunView toView(AgentRunEntity entity, List<AgentStepView> steps) {
        return new AgentRunView(entity.getId(), entity.getUserId(), entity.getScene(), entity.getTitle(),
                entity.getStatus(), entity.getStepCount() == null ? 0 : entity.getStepCount(),
                entity.getPromptTokens() == null ? 0 : entity.getPromptTokens(),
                entity.getCompletionTokens() == null ? 0 : entity.getCompletionTokens(),
                entity.getDurationMs() == null ? 0L : entity.getDurationMs(),
                entity.getErrorMsg(), entity.getCreatedAt(), steps == null ? List.of() : steps);
    }

    private String clip(String text, int limit) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() > limit ? trimmed.substring(0, limit) + "…" : trimmed;
    }
}
