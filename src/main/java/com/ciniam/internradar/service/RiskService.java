package com.ciniam.internradar.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ciniam.internradar.agent.ReportAgent;
import com.ciniam.internradar.config.InternRadarProperties;
import com.ciniam.internradar.entity.RiskAlertEntity;
import com.ciniam.internradar.entity.WorkRecordEntity;
import com.ciniam.internradar.mapper.RiskAlertMapper;
import com.ciniam.internradar.model.Pitfall;
import com.ciniam.internradar.model.RiskAlert;
import com.ciniam.internradar.model.RiskJudgement;
import com.ciniam.internradar.model.RiskLevel;
import com.ciniam.internradar.model.TeamSettings;
import com.ciniam.internradar.model.UserPreference;
import com.ciniam.internradar.push.PushResult;
import com.ciniam.internradar.push.PushService;
import com.ciniam.internradar.trend.TrendClient;
import com.ciniam.internradar.trend.TrendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 风险识别规则层。规则先行保证精确率，语义判断留给后续的模型层
 * 规则：连续无产出、任务停滞、临期未完成
 */
@Service
public class RiskService {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);

    private static final Set<String> CLOSED_STATUS = Set.of("done", "closed", "cancel", "resolved");

    private static final String SCENE_RISK = "RISK_CHECK";

    private static final String TYPE_NO_OUTPUT = "NO_OUTPUT";
    private static final String TYPE_TASK_STALL = "TASK_STALL";
    private static final String TYPE_DEADLINE = "DEADLINE_RISK";
    private static final String TYPE_TREND = "TREND_ANOMALY";
    private static final String TYPE_PITFALL = "PITFALL";

    private static final String REVIEW_PROMPT = """
            你是实习生风险复核员。规则层已经给出一批候选风险，你的任务是复核而不是重新发现。
            规则：
            1. 逐条判断：确属风险的保留并说明判定理由，属于误报的标记为不保留；
            2. 判定理由要具体，指向数据里的哪一条事实，不要写空话；
            3. 建议动作要可执行，能落到下一步做什么；
            4. 需要更多事实时先调用工具查证，不要凭印象判断。
            """;

    private static final int TREND_WINDOW_DAYS = 14;
    private static final double TREND_Z_THRESHOLD = 2.0;
    private static final int SUGGESTION_LIMIT = 480;

    private final RiskAlertMapper riskAlertMapper;
    private final ReportService reportService;
    private final PushService pushService;
    private final TrendClient trendClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeService knowledgeService;
    private final PreferenceService preferenceService;
    private final TeamSettingService teamSettingService;
    private final ToolRegistry toolRegistry;
    private final AgentTraceService agentTraceService;
    private final ModelRegistry modelRegistry;
    private final ObserveService observeService;
    private final TeamScope teamScope;

    public RiskService(RiskAlertMapper riskAlertMapper,
                       ReportService reportService,
                       PushService pushService,
                       TrendClient trendClient,
                       ObjectMapper objectMapper,
                       KnowledgeService knowledgeService,
                       PreferenceService preferenceService,
                       TeamSettingService teamSettingService,
                       // 工具注册中心反过来要用任务快照能力，这里用延迟注入打破构造期循环
                       @Lazy ToolRegistry toolRegistry,
                       AgentTraceService agentTraceService,
                       ModelRegistry modelRegistry,
                       ObserveService observeService,
                       TeamScope teamScope) {
        this.riskAlertMapper = riskAlertMapper;
        this.reportService = reportService;
        this.pushService = pushService;
        this.trendClient = trendClient;
        this.objectMapper = objectMapper;
        this.knowledgeService = knowledgeService;
        this.preferenceService = preferenceService;
        this.teamSettingService = teamSettingService;
        this.toolRegistry = toolRegistry;
        this.agentTraceService = agentTraceService;
        this.modelRegistry = modelRegistry;
        this.observeService = observeService;
        this.teamScope = teamScope;
    }

    /**
     * 跑一遍规则并落库，返回本次新增的告警
     * 阈值取自团队设置，配置文件里的值只是默认值
     */
    public List<RiskAlert> evaluate(String userId, LocalDate date) {
        TeamSettings settings = teamSettingService.current();
        List<RiskAlert> alerts = new ArrayList<>();
        alerts.addAll(checkNoOutput(userId, date, settings));
        List<WorkRecordEntity> openTasks = openTasks(userId, date);
        alerts.addAll(checkTaskStall(openTasks, date, settings));
        alerts.addAll(checkDeadline(openTasks, date, settings));
        alerts.addAll(checkTrend(userId, date));
        alerts.addAll(checkPitfall(userId, date));
        if (alerts.isEmpty()) {
            log.info("风险检查完成，用户 {}，规则层没有命中", userId);
            return List.of();
        }
        Long runId = agentTraceService.begin(userId, SCENE_RISK, "风险检查 " + date);
        List<RiskAlert> reviewed = reviewWithAgent(userId, date, alerts, runId);
        List<RiskAlert> created = new ArrayList<>();
        for (RiskAlert alert : reviewed) {
            if (persist(alert, runId)) {
                created.add(alert);
            }
        }
        log.info("风险检查完成，用户 {}，规则命中 {} 条，复核后保留 {} 条，新增 {} 条",
                userId, alerts.size(), reviewed.size(), created.size());
        return created;
    }

    /**
     * 在阈值规则之上加一层智能体判断：核掉误报，并为留下的告警补上判定理由与建议
     * 复核失败时保留规则层结论，保证告警不会因为模型不可用而整体丢失
     */
    private List<RiskAlert> reviewWithAgent(String userId, LocalDate date, List<RiskAlert> alerts, Long runId) {
        ToolRegistry.ToolSet toolSet = toolRegistry.forScene(ToolRegistry.SCENE_RISK, userId);
        ReportAgent<RiskJudgement> agent = new ReportAgent<>(toolSet.callbacks(), modelRegistry.chatModel(),
                "riskAgent", REVIEW_PROMPT, RiskJudgement.class);
        agent.setStepListener(agentTraceService.listener(runId));
        long begin = System.currentTimeMillis();
        long elapsed;
        try {
            RiskJudgement judgement = agent.generate(reviewTask(userId, date, alerts));
            elapsed = System.currentTimeMillis() - begin;
            agentTraceService.finish(runId, agent.getExecutedSteps(), agent.getPromptTokens(),
                    agent.getCompletionTokens(), elapsed, true, null);
            observeService.recordLlm(userId, SCENE_RISK, modelRegistry.modelName(), agent.getPromptTokens(),
                    agent.getCompletionTokens(), elapsed, true, null);
            return applyJudgement(alerts, judgement);
        } catch (Exception e) {
            elapsed = System.currentTimeMillis() - begin;
            agentTraceService.finish(runId, agent.getExecutedSteps(), agent.getPromptTokens(),
                    agent.getCompletionTokens(), elapsed, false, e.getMessage());
            observeService.recordLlm(userId, SCENE_RISK, modelRegistry.modelName(), agent.getPromptTokens(),
                    agent.getCompletionTokens(), elapsed, false, e.getMessage());
            log.warn("风险复核失败，保留规则层结论，用户 {}：{}", userId, e.getMessage());
            return alerts;
        }
    }

    /**
     * 按指纹对齐复核结论：keep 为假的丢弃，留下的换上模型给出的理由与建议
     */
    private List<RiskAlert> applyJudgement(List<RiskAlert> alerts, RiskJudgement judgement) {
        if (judgement == null || judgement.items() == null || judgement.items().isEmpty()) {
            return alerts;
        }
        Map<String, RiskJudgement.Item> byFingerprint = new LinkedHashMap<>();
        for (RiskJudgement.Item item : judgement.items()) {
            if (item != null && item.fingerprint() != null) {
                byFingerprint.put(item.fingerprint().trim(), item);
            }
        }
        List<RiskAlert> kept = new ArrayList<>();
        int unmatched = 0;
        for (RiskAlert alert : alerts) {
            RiskJudgement.Item item = byFingerprint.get(alert.fingerprint());
            if (item == null) {
                // 对不上号时保留规则层原文，宁可多留一条也不丢告警
                unmatched++;
                kept.add(alert);
                continue;
            }
            if (Boolean.FALSE.equals(item.keep())) {
                log.info("风险告警被复核判定为误报，已丢弃：{}，理由 {}", alert.fingerprint(), item.reason());
                continue;
            }
            kept.add(withJudgement(alert, item));
        }
        if (unmatched > 0) {
            log.warn("复核结论有 {} 条对不上候选指纹，已按规则层原文保留", unmatched);
        }
        return kept;
    }

    private RiskAlert withJudgement(RiskAlert alert, RiskJudgement.Item item) {
        List<String> evidence = new ArrayList<>(alert.evidence());
        if (item.reason() != null && !item.reason().isBlank()) {
            evidence.add("判定理由：" + truncate(item.reason().trim()));
        }
        String suggestion = item.suggestion() == null || item.suggestion().isBlank()
                ? alert.suggestion() : truncate(item.suggestion().trim());
        return new RiskAlert(alert.userId(), alert.level(), alert.type(), alert.fingerprint(), alert.title(),
                evidence, suggestion, alert.extra());
    }

    private String reviewTask(String userId, LocalDate date, List<RiskAlert> alerts) {
        StringBuilder builder = new StringBuilder();
        builder.append("请复核 ").append(userId).append(" 在 ").append(date).append(" 的下列候选风险，")
                .append(alerts.size()).append(" 条。\n");
        int index = 1;
        for (RiskAlert alert : alerts) {
            builder.append("\n候选 ").append(index++).append('\n');
            builder.append("需原样回填的指纹：").append(alert.fingerprint()).append('\n');
            builder.append("等级：").append(alert.level()).append('\n');
            builder.append("类型：").append(alert.type()).append('\n');
            builder.append("规则结论：").append(alert.title()).append('\n');
            builder.append("规则依据：").append(String.join("；", alert.evidence())).append('\n');
        }
        builder.append("""

                要求：
                1. items 里每条只对应一个候选，fingerprint 字段必须把上面「需原样回填的指纹」整串抄过来，
                   不要拼接等级、类型、结论或依据，也不要改写其中的数字；
                2. 每条都要给出 keep 判断，确属风险为 true，误报为 false；
                3. reason 写为什么这样判，要指向数据里的具体事实；
                4. suggestion 写下一步该做什么，要可执行；
                5. 拿不准时先用工具查证再下结论。
                """);
        return builder.toString();
    }

    /**
     * 推送未通知告警，超出节流窗口的会再次提醒
     * 按用户偏好筛掉不关注的类型与低于等级下限的告警，推送失败时不标记已通知，留待下次重试
     */
    public int notifyPending(String userId) {
        LocalDateTime since = LocalDateTime.now()
                .minusHours(teamSettingService.current().alertThrottleHours());
        List<RiskAlertEntity> pending = riskAlertMapper.selectForNotify(userId, since);
        if (pending.isEmpty()) {
            return 0;
        }
        UserPreference preference = preferenceService.get(userId);
        List<RiskAlertEntity> selected = new ArrayList<>();
        for (RiskAlertEntity alert : pending) {
            if (!preferenceService.alertTypeAccepted(preference, alert.getType())) {
                continue;
            }
            if (!preferenceService.alertLevelAccepted(preference, alert.getLevel())) {
                continue;
            }
            selected.add(alert);
        }
        if (selected.isEmpty()) {
            log.info("告警全部被偏好过滤，用户 {}，待通知 {} 条", userId, pending.size());
            return 0;
        }
        PushResult result = pushService.sendToUser(userId, "RISK_ALERT", formatAlerts(userId, selected));
        if (!result.success()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        for (RiskAlertEntity alert : selected) {
            riskAlertMapper.markNotified(alert.getId(), now);
        }
        return selected.size();
    }

    public List<RiskAlertEntity> listAlerts(String userId, String status) {
        return riskAlertMapper.selectByUserAndStatus(userId, status == null ? "OPEN" : status);
    }

    public boolean acknowledge(Long id) {
        return riskAlertMapper.updateStatus(id, "ACKED") > 0;
    }

    /**
     * 按主键取告警，接口层用它判断这条告警属不属于调用人
     */
    public RiskAlertEntity findById(Long id) {
        return riskAlertMapper.selectById(id);
    }

    private List<RiskAlert> checkNoOutput(String userId, LocalDate date, TeamSettings settings) {
        int days = settings.noOutputDays();
        if (days <= 0) {
            return List.of();
        }
        LocalDate start = date.minusDays(days - 1L);
        List<WorkRecordEntity> records = reportService.recordsOf(userId, start, date);
        Set<LocalDate> activeDays = new java.util.HashSet<>();
        for (WorkRecordEntity record : records) {
            if (record.getOccurredAt() != null) {
                activeDays.add(record.getOccurredAt().toLocalDate());
            }
        }
        List<String> silentDays = new ArrayList<>();
        for (LocalDate cursor = start; !cursor.isAfter(date); cursor = cursor.plusDays(1)) {
            if (!activeDays.contains(cursor)) {
                silentDays.add(cursor.toString());
            }
        }
        if (silentDays.size() < days) {
            return List.of();
        }
        return List.of(new RiskAlert(userId, RiskLevel.HIGH, TYPE_NO_OUTPUT,
                TYPE_NO_OUTPUT + ":" + date, "连续 " + silentDays.size() + " 天没有任何工作产出",
                List.of("无产出的日期：" + String.join("、", silentDays)),
                "建议先确认是否在休假或任务未登记，否则需要导师介入了解卡点", Map.of()));
    }

    private List<RiskAlert> checkTaskStall(List<WorkRecordEntity> openTasks, LocalDate date, TeamSettings settings) {
        int stallDays = settings.taskStallDays();
        if (stallDays <= 0) {
            return List.of();
        }
        List<RiskAlert> alerts = new ArrayList<>();
        for (WorkRecordEntity task : openTasks) {
            if (!started(task)) {
                // 未开始的任务不算停滞，这类任务由临期规则覆盖
                continue;
            }
            LocalDateTime last = task.getOccurredAt();
            if (last == null) {
                continue;
            }
            long silent = ChronoUnit.DAYS.between(last.toLocalDate(), date);
            if (silent < stallDays) {
                continue;
            }
            String taskId = taskKey(task);
            alerts.add(new RiskAlert(task.getUserId(), RiskLevel.MEDIUM, TYPE_TASK_STALL,
                    TYPE_TASK_STALL + ":" + taskId,
                    "任务停滞 " + silent + " 天：" + task.getTitle(),
                    List.of("任务：" + task.getTitle(), "最后变更时间：" + last, "已停滞 " + silent + " 天"),
                    "建议确认任务是否被阻塞，必要时调整排期或拆分任务", Map.of("taskId", taskId)));
        }
        return alerts;
    }

    private List<RiskAlert> checkDeadline(List<WorkRecordEntity> openTasks, LocalDate date, TeamSettings settings) {
        int days = settings.deadlineDays();
        int progressLine = settings.deadlineProgress();
        if (days <= 0) {
            return List.of();
        }
        List<RiskAlert> alerts = new ArrayList<>();
        for (WorkRecordEntity task : openTasks) {
            Map<String, Object> metadata = metadata(task);
            LocalDate deadline = toDate(metadata.get("deadline"));
            if (deadline == null) {
                continue;
            }
            long remain = ChronoUnit.DAYS.between(date, deadline);
            if (remain < 0 || remain > days) {
                continue;
            }
            int progress = toInt(metadata.get("progress"), -1);
            // 进度未知时不下结论，避免误报
            if (progress < 0) {
                continue;
            }
            if (progressLine > 0 && progress >= progressLine) {
                continue;
            }
            String taskId = taskKey(task);
            alerts.add(new RiskAlert(task.getUserId(), RiskLevel.HIGH, TYPE_DEADLINE,
                    TYPE_DEADLINE + ":" + taskId,
                    "任务临近截止且进度偏低：" + task.getTitle(),
                    List.of("截止日期：" + deadline, "剩余天数：" + remain, "当前进度：" + progress),
                    "建议重新评估工作量，必要时缩小交付范围或提前求助", Map.of("taskId", taskId)));
        }
        return alerts;
    }

    /**
     * 产出量趋势突变检测，统计计算交给 Python 服务，Java 侧只负责取数与判定
     */
    private List<RiskAlert> checkTrend(String userId, LocalDate date) {
        if (!trendClient.configured()) {
            return List.of();
        }
        LocalDate start = date.minusDays(TREND_WINDOW_DAYS - 1L);
        List<WorkRecordEntity> records = reportService.recordsOf(userId, start, date);
        Map<LocalDate, Integer> counts = new LinkedHashMap<>();
        for (LocalDate cursor = start; !cursor.isAfter(date); cursor = cursor.plusDays(1)) {
            counts.put(cursor, 0);
        }
        for (WorkRecordEntity record : records) {
            if (record.getOccurredAt() == null) {
                continue;
            }
            counts.computeIfPresent(record.getOccurredAt().toLocalDate(), (key, value) -> value + 1);
        }
        List<Map<String, Object>> series = new ArrayList<>();
        counts.forEach((day, value) -> {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", day.toString());
            point.put("value", value);
            series.add(point);
        });
        TrendResult result = trendClient.analyze(series, TREND_Z_THRESHOLD);
        if (!"down".equals(result.trend())) {
            return List.of();
        }
        List<String> evidence = new ArrayList<>();
        evidence.add(result.summary());
        for (TrendResult.AnomalyPoint anomaly : result.anomalies()) {
            evidence.add("突变日期 " + anomaly.date() + "，数值 " + anomaly.value()
                    + "，偏离 " + anomaly.zscore() + " 个标准差");
        }
        return List.of(new RiskAlert(userId, RiskLevel.MEDIUM, TYPE_TREND,
                TYPE_TREND + ":" + date, "近 " + TREND_WINDOW_DAYS + " 天产出量呈下降趋势",
                evidence, "建议确认是否工作量减少或遇到阻碍，必要时安排沟通", Map.of()));
    }

    /**
     * 坑位预警：用当日工作记录文本匹配坑位标签，命中即提醒
     * 同一坑位对同一人只提醒一次，靠指纹唯一键天然去重
     */
    private List<RiskAlert> checkPitfall(String userId, LocalDate date) {
        List<WorkRecordEntity> records = reportService.recordsOf(userId, date, date);
        if (records.isEmpty()) {
            return List.of();
        }
        StringBuilder text = new StringBuilder();
        for (WorkRecordEntity record : records) {
            if (record.getTitle() != null) {
                text.append(record.getTitle()).append(' ');
            }
            if (record.getContent() != null) {
                text.append(record.getContent()).append(' ');
            }
        }
        List<Pitfall> hits = knowledgeService.matchPitfalls(text.toString());
        List<RiskAlert> alerts = new ArrayList<>();
        for (Pitfall pitfall : hits) {
            List<String> evidence = new ArrayList<>();
            evidence.add("命中坑位：" + pitfall.title());
            evidence.add("命中标签：" + String.join("、", pitfall.tags()));
            if (pitfall.symptom() != null && !pitfall.symptom().isBlank()) {
                evidence.add("典型症状：" + pitfall.symptom());
            }
            alerts.add(new RiskAlert(userId, RiskLevel.LOW, TYPE_PITFALL,
                    TYPE_PITFALL + ":" + pitfall.id(),
                    "疑似踩到已知坑位：" + pitfall.title(),
                    evidence,
                    truncate(pitfall.solution()),
                    Map.of("pitfallId", pitfall.id())));
        }
        return alerts;
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > SUGGESTION_LIMIT ? text.substring(0, SUGGESTION_LIMIT) : text;
    }

    /**
     * 未关闭的任务快照，同一任务取最新一条。导师视图也复用这套判定
     */
    public List<WorkRecordEntity> openTasks(String userId, LocalDate date) {
        long lookback = Math.max(30L, teamSettingService.current().taskStallDays() + 7L);
        List<WorkRecordEntity> records = reportService.recordsOf(userId, date.minusDays(lookback), date);
        Map<String, WorkRecordEntity> latest = new LinkedHashMap<>();
        for (WorkRecordEntity record : records) {
            if (!"TASK".equals(record.getType()) || "todo".equals(metadata(record).get("kind"))) {
                continue;
            }
            latest.put(taskKey(record), record);
        }
        List<WorkRecordEntity> open = new ArrayList<>();
        for (WorkRecordEntity record : latest.values()) {
            String status = record.getStatus() == null ? "" : record.getStatus().trim().toLowerCase();
            if (!CLOSED_STATUS.contains(status)) {
                open.add(record);
            }
        }
        return open;
    }

    /**
     * 任务标识优先取业务 id，避免同一任务的多个快照被当成不同任务
     */
    private String taskKey(WorkRecordEntity record) {
        Object taskId = metadata(record).get("taskId");
        if (taskId != null && !String.valueOf(taskId).isBlank()) {
            return String.valueOf(taskId);
        }
        if (record.getExternalId() != null && !record.getExternalId().isBlank()) {
            return record.getExternalId();
        }
        return String.valueOf(record.getTitle());
    }

    /**
     * 任务是否已启动。只看 doing 或已有工时消耗，避免把未开始的排期任务当成停滞
     */
    private boolean started(WorkRecordEntity record) {
        Map<String, Object> metadata = metadata(record);
        String rawStatus = metadata.get("rawStatus") == null ? "" : String.valueOf(metadata.get("rawStatus"));
        String normalized = rawStatus.toLowerCase();
        if (normalized.contains("doing") || normalized.contains("pause")) {
            return true;
        }
        return toDouble(metadata.get("consumed")) > 0;
    }

    private double toDouble(Object value) {
        if (value == null) {
            return 0d;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    private boolean persist(RiskAlert alert, Long agentRunId) {
        RiskAlertEntity entity = RiskAlertEntity.builder()
                .teamId(teamScope.of(alert.userId()))
                .userId(alert.userId())
                .level(alert.level().name())
                .type(alert.type())
                .title(alert.title())
                .fingerprint(alert.fingerprint())
                .evidence(String.join("｜", alert.evidence()))
                .suggestion(alert.suggestion())
                .status("OPEN")
                .agentRunId(agentRunId)
                .createdAt(LocalDateTime.now())
                .build();
        return riskAlertMapper.insertIgnore(entity) > 0;
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

    private LocalDate toDate(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(text.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private int toInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String formatAlerts(String userId, List<RiskAlertEntity> alerts) {
        StringBuilder builder = new StringBuilder();
        builder.append("实习雷达·风险提醒 ").append(userId).append('\n');
        for (RiskAlertEntity alert : alerts) {
            builder.append('\n').append('[').append(alert.getLevel()).append("] ")
                    .append(alert.getType()).append('\n');
            builder.append(alert.getEvidence() == null ? "" : alert.getEvidence().replace("｜", "\n")).append('\n');
            builder.append("建议：").append(alert.getSuggestion() == null ? "" : alert.getSuggestion()).append('\n');
        }
        return builder.toString();
    }
}
