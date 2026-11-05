package com.ciniam.internradar.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ciniam.internradar.agent.ReportAgent;
import com.ciniam.internradar.entity.ReportEntity;
import com.ciniam.internradar.entity.WorkRecordEntity;
import com.ciniam.internradar.mapper.ReportMapper;
import com.ciniam.internradar.mapper.WorkRecordMapper;
import com.ciniam.internradar.model.DailyPlan;
import com.ciniam.internradar.model.DailyReport;
import com.ciniam.internradar.model.ReportDetail;
import com.ciniam.internradar.model.ReportView;
import com.ciniam.internradar.model.UserPreference;
import com.ciniam.internradar.model.WeeklyReport;
import com.ciniam.internradar.push.PushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 报告编排：采集落库、读取数据、结构化生成、按需推送
 * 报告基于库表数据生成，保证可复现
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    public static final String TYPE_DAILY = "DAILY";
    public static final String TYPE_PLAN = "PLAN";
    public static final String TYPE_WEEKLY = "WEEKLY";

    private static final String SCENE_DAILY = "DAILY_REPORT";
    private static final String SCENE_PLAN = "MORNING_PLAN";
    private static final String SCENE_WEEKLY = "WEEKLY_REPORT";


    private static final String DAILY_PROMPT = """
            你是实习生日报助手，负责把当日真实工作数据整理成结构化日报。
            规则：
            1. 只使用给定数据中出现的事实，不得编造工作内容；
            2. completed 里每条产出都要在 evidence 中给出对应的提交短哈希；
            3. problems 只写数据里真实暴露的问题，没有就返回空数组；
            4. tomorrowPlan 与 needHelp 基于当日进展推断，要具体可执行。
            """;

    private static final String PLAN_PROMPT = """
            你是实习生今日计划助手，负责根据未完成任务与近期进展给出今日计划。
            规则：
            1. 只使用给定数据中出现的事实，不得编造任务；
            2. focusItems 是今日最该推进的重点，最多三条；
            3. todoItems 是具体待办，要可执行；
            4. riskNotes 只写数据里能看出的风险，没有就返回空数组。
            """;

    private static final String WEEKLY_PROMPT = """
            你是实习生周报助手，面向导师输出本周总结。
            规则：
            1. 只使用给定数据中出现的事实，不得编造产出；
            2. outputs 归纳本周实际产出，按主题合并同类项；
            3. trendNotes 描述与上周相比的变化，数据不足时说明数据不足；
            4. nextWeekFocus 给出下周重点，risks 给出需要导师介入的风险。
            """;

    private final WorkCollectService workCollectService;
    private final WorkRecordMapper workRecordMapper;
    private final ReportMapper reportMapper;
    private final PushService pushService;
    private final ObserveService observeService;
    private final ModelRegistry modelRegistry;
    private final ObjectMapper objectMapper;
    private final PreferenceService preferenceService;
    private final ToolRegistry toolRegistry;
    private final AgentTraceService agentTraceService;
    private final TeamScope teamScope;

    public ReportService(WorkCollectService workCollectService,
                         WorkRecordMapper workRecordMapper,
                         ReportMapper reportMapper,
                         PushService pushService,
                         ObserveService observeService,
                         ModelRegistry modelRegistry,
                         ObjectMapper objectMapper,
                         PreferenceService preferenceService,
                         // 工具注册中心反过来要用报告查询能力，这里用延迟注入打破构造期循环
                         @Lazy ToolRegistry toolRegistry,
                         AgentTraceService agentTraceService,
                         TeamScope teamScope) {
        this.workCollectService = workCollectService;
        this.workRecordMapper = workRecordMapper;
        this.reportMapper = reportMapper;
        this.pushService = pushService;
        this.observeService = observeService;
        this.modelRegistry = modelRegistry;
        this.objectMapper = objectMapper;
        this.preferenceService = preferenceService;
        this.toolRegistry = toolRegistry;
        this.agentTraceService = agentTraceService;
        this.teamScope = teamScope;
    }

    public DailyReport generateDaily(String userId, LocalDate date, boolean push) {
        UserPreference preference = preferenceService.get(userId);
        workCollectService.collect(userId, date);
        List<WorkRecordEntity> records = recordsOf(userId, date, date);
        if (records.isEmpty()) {
            log.warn("用户 {} 在 {} 没有可用工作记录，生成为空日报", userId, date);
            DailyReport empty = new DailyReport("当日未采集到工作记录", List.of(), List.of(), List.of(), List.of());
            save(userId, TYPE_DAILY, date, date, empty, 0, "EMPTY", null);
            return empty;
        }
        AgentOutput<DailyReport> output = callAgent(userId, SCENE_DAILY, "reportAgent", dailyPrompt(preference),
                dailyTask(userId, date), "日报 " + date, DailyReport.class);
        DailyReport report = output.value();
        save(userId, TYPE_DAILY, date, date, report, 0, "SUCCESS", output.runId());
        if (push) {
            pushService.sendToUser(userId, TYPE_DAILY, formatDaily(userId, date, report, preference));
        }
        return report;
    }

    public ReportView getDaily(String userId, LocalDate date) {
        ReportEntity entity = reportMapper.selectLatest(userId, TYPE_DAILY, date);
        return entity == null ? null : toView(entity);
    }

    public DailyPlan generateMorningPlan(String userId, LocalDate date, boolean push) {
        UserPreference preference = preferenceService.get(userId);
        workCollectService.collect(userId, date);
        AgentOutput<DailyPlan> output = callAgent(userId, SCENE_PLAN, "planAgent", planPrompt(preference),
                planTask(userId, date), "今日计划 " + date, DailyPlan.class);
        DailyPlan plan = output.value();
        save(userId, TYPE_PLAN, date, date, plan, 0, "SUCCESS", output.runId());
        if (push) {
            pushService.sendToUser(userId, TYPE_PLAN, formatPlan(userId, date, plan));
        }
        return plan;
    }

    public WeeklyReport generateWeekly(String userId, LocalDate weekStart, boolean push) {
        UserPreference preference = preferenceService.get(userId);
        LocalDate weekEnd = weekStart.plusDays(6);
        for (LocalDate cursor = weekStart; !cursor.isAfter(weekEnd); cursor = cursor.plusDays(1)) {
            workCollectService.collect(userId, cursor);
        }
        AgentOutput<WeeklyReport> output = callAgent(userId, SCENE_WEEKLY, "weeklyAgent", WEEKLY_PROMPT,
                weeklyTask(userId, weekStart, weekEnd), "周报 " + weekStart + " 至 " + weekEnd, WeeklyReport.class);
        WeeklyReport report = output.value();
        save(userId, TYPE_WEEKLY, weekStart, weekEnd, report, 0, "SUCCESS", output.runId());
        if (push) {
            pushService.sendToMentors(TYPE_WEEKLY, formatWeekly(userId, weekStart, weekEnd, report, preference));
        }
        return report;
    }

    /**
     * 读已生成的周报内容，供按导师偏好投递时拼装
     */
    public WeeklyReport getWeeklyReport(String userId, LocalDate weekStart) {
        ReportEntity entity = reportMapper.selectLatest(userId, TYPE_WEEKLY, weekStart);
        return entity == null ? null : parse(entity.getContentJson(), WeeklyReport.class);
    }

    /**
     * 日报提示词按偏好多加两条规则：详略程度与是否附提交哈希
     */
    private String dailyPrompt(UserPreference preference) {
        StringBuilder builder = new StringBuilder(DAILY_PROMPT);
        if (preference.compactReport()) {
            builder.append("5. 内容要精简，每条产出只给一句结论，不展开实现过程。\n");
        } else {
            builder.append("5. 内容要写清关键实现与取舍，让人不看代码也能理解。\n");
        }
        if (!preference.attachCommitHash()) {
            builder.append("6. 不需要提交哈希，evidence 一律返回空数组。\n");
        }
        return builder.toString();
    }

    private String planPrompt(UserPreference preference) {
        return PLAN_PROMPT + "5. focusItems 最多 " + preference.planFocusLimit() + " 条，超出按优先级取舍。\n";
    }



    public List<WorkRecordEntity> recordsOf(String userId, LocalDate start, LocalDate end) {
        return workRecordMapper.selectByUserAndRange(userId, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
    }

    /**
     * 代理产出与它对应的运行标识，运行标识随报告一起落库，供界面回看轨迹
     */
    private record AgentOutput<T>(T value, Long runId) {
    }

    /**
     * 调智能体产出结构化结果。首次失败时带格式提示重试一次，两次都失败直接抛出
     */
    private <T> AgentOutput<T> callAgent(String userId, String scene, String agentName, String systemPrompt,
                                         String prompt, String title, Class<T> outputType) {
        ToolRegistry.ToolSet toolSet = toolRegistry.forScene(ToolRegistry.SCENE_REPORT, userId);
        Long runId = agentTraceService.begin(userId, scene, title);
        long begin = System.currentTimeMillis();
        ReportAgent<T> agent = newAgent(toolSet.callbacks(), agentName, systemPrompt, outputType);
        agent.setStepListener(agentTraceService.listener(runId));
        try {
            T result = agent.generate(prompt);
            finishTrace(userId, scene, runId, agent, begin, true, null);
            log.info("{} 生成完成，用户 {}，工具步数 {}，token {}",
                    scene, userId, agent.getExecutedSteps(), agent.getTotalTokens());
            return new AgentOutput<>(result, runId);
        } catch (Exception first) {
            log.warn("{} 首次生成失败，带格式提示重试一次：{}", scene, first.getMessage());
            ReportAgent<T> retry = newAgent(toolSet.callbacks(), agentName, systemPrompt, outputType);
            retry.setStepListener(agentTraceService.listener(runId, agent.getExecutedSteps()));
            try {
                T result = retry.generate(prompt + ReportAgent.RETRY_SUFFIX);
                finishTrace(userId, scene, runId, retry, begin, true, null);
                return new AgentOutput<>(result, runId);
            } catch (Exception second) {
                finishTrace(userId, scene, runId, retry, begin, false, second.getMessage());
                throw new IllegalStateException(scene + " 生成失败：" + second.getMessage(), second);
            }
        }
    }

    private <T> ReportAgent<T> newAgent(ToolCallback[] tools, String agentName,
                                        String systemPrompt, Class<T> outputType) {
        return new ReportAgent<>(tools, modelRegistry.chatModel(), agentName, systemPrompt, outputType);
    }

    private void finishTrace(String userId, String scene, Long runId, ReportAgent<?> agent,
                             long begin, boolean success, String error) {
        long elapsed = System.currentTimeMillis() - begin;
        agentTraceService.finish(runId, agent.getExecutedSteps(), agent.getPromptTokens(),
                agent.getCompletionTokens(), elapsed, success, error);
        observeService.recordLlm(userId, scene, modelRegistry.modelName(), agent.getPromptTokens(),
                agent.getCompletionTokens(), elapsed, success, error);
    }

    private String dailyTask(String userId, LocalDate date) {
        return "请为人员 " + userId + " 生成 " + date + " 的日报。"
                + "先用工具查询该日的工作记录，覆盖代码提交、任务与工时日志，再按格式要求输出结果。";
    }

    private String planTask(String userId, LocalDate date) {
        return "请为人员 " + userId + " 生成 " + date + " 的今日计划。"
                + "先用工具查询当前未关闭的任务，并参考昨日的工作记录，再按格式要求输出结果。";
    }

    private String weeklyTask(String userId, LocalDate start, LocalDate end) {
        return "请为人员 " + userId + " 生成 " + start + " 至 " + end + " 的周报。"
                + "先用工具查询本周与上周的工作记录，需要衔接既有结论时再查历史报告，再按格式要求输出结果。";
    }

    private String formatDaily(String userId, LocalDate date, DailyReport report, UserPreference preference) {
        StringBuilder builder = new StringBuilder();
        builder.append("实习雷达·今日总结 ").append(date).append(' ').append(userId).append('\n');
        builder.append(nullToEmpty(report.summary())).append('\n');
        appendSection(builder, "今日完成", report.completed() == null ? List.of()
                : report.completed().stream().map(item -> formatCompleted(item, preference)).toList());
        appendSection(builder, "遇到问题", report.problems());
        appendSection(builder, "明日计划", report.tomorrowPlan());
        appendSection(builder, "需要协助", report.needHelp());
        return builder.toString();
    }

    private String formatPlan(String userId, LocalDate date, DailyPlan plan) {
        StringBuilder builder = new StringBuilder();
        builder.append("实习雷达·今日计划 ").append(date).append(' ').append(userId).append('\n');
        builder.append(nullToEmpty(plan.summary())).append('\n');
        appendSection(builder, "今日重点", plan.focusItems());
        appendSection(builder, "待办清单", plan.todoItems());
        appendSection(builder, "风险提示", plan.riskNotes());
        return builder.toString();
    }

    public String formatWeekly(String userId, LocalDate start, LocalDate end, WeeklyReport report,
                               UserPreference preference) {
        StringBuilder builder = new StringBuilder();
        builder.append("实习雷达·周报 ").append(userId).append(' ').append(start).append(" 至 ").append(end).append('\n');
        builder.append(nullToEmpty(report.summary())).append('\n');
        appendSection(builder, "本周产出", report.outputs());
        if (preference.weeklyTrendEnabled()) {
            appendSection(builder, "趋势变化", report.trendNotes());
        }
        appendSection(builder, "下周重点", report.nextWeekFocus());
        appendSection(builder, "风险提示", report.risks());
        return builder.toString();
    }

    private String formatCompleted(DailyReport.CompletedItem item, UserPreference preference) {
        if (!preference.attachCommitHash()) {
            return item.content();
        }
        List<String> evidence = item.evidence();
        String suffix = evidence == null || evidence.isEmpty() ? "" : " " + String.join(" ", evidence);
        return item.content() + suffix;
    }

    private void appendSection(StringBuilder builder, String title, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        builder.append('\n').append(title).append('\n');
        for (String line : lines) {
            builder.append("- ").append(line).append('\n');
        }
    }

    private String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private void save(String userId, String type, LocalDate start, LocalDate end,
                      Object content, int tokensUsed, String status, Long agentRunId) {
        reportMapper.insert(ReportEntity.builder()
                .teamId(teamScope.of(userId))
                .userId(userId)
                .type(type)
                .periodStart(start)
                .periodEnd(end)
                .contentJson(toJson(content))
                .tokensUsed(tokensUsed)
                .cost(BigDecimal.ZERO)
                .status(status)
                .agentRunId(agentRunId)
                .generatedAt(LocalDateTime.now())
                .build());
    }

    /**
     * 读取某周已生成的周报，供导师看板查看
     */
    public ReportDetail getWeekly(String userId, LocalDate weekStart) {
        ReportEntity entity = reportMapper.selectLatest(userId, TYPE_WEEKLY, weekStart);
        return entity == null ? null : toDetail(entity);
    }

    /**
     * 区间内报告列表，type 为空时返回全部类型
     */
    public List<ReportDetail> listReports(String userId, LocalDate start, LocalDate end, String type) {
        return reportMapper.selectByUser(userId, start, end, type).stream().map(this::toDetail).toList();
    }

    public ReportDetail toDetail(ReportEntity entity) {
        return new ReportDetail(entity.getId(), entity.getUserId(), entity.getType(), entity.getPeriodStart(),
                entity.getPeriodEnd(), readTree(entity.getContentJson()), entity.getTokensUsed(),
                entity.getAgentRunId(), entity.getGeneratedAt(), entity.getStatus());
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("报告内容解析失败，原因：{}", e.getMessage());
            return null;
        }
    }

    public ReportView toView(ReportEntity entity) {
        return new ReportView(entity.getId(), entity.getUserId(), entity.getType(), entity.getPeriodStart(),
                entity.getPeriodEnd(), parse(entity.getContentJson(), DailyReport.class), entity.getTokensUsed(),
                entity.getGeneratedAt(), entity.getStatus());
    }

    private <T> T parse(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("报告内容解析失败，原因：{}", e.getMessage());
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("报告序列化失败", e);
            return "{}";
        }
    }
}
