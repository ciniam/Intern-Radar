package com.ciniam.internradar.controller;

import com.ciniam.internradar.entity.WorkRecordEntity;
import com.ciniam.internradar.mapper.DocRetrieveLogMapper;
import com.ciniam.internradar.mapper.LlmCallLogMapper;
import com.ciniam.internradar.mapper.WorkRecordMapper;
import com.ciniam.internradar.model.DailyPlan;
import com.ciniam.internradar.model.DailyReport;
import com.ciniam.internradar.model.ReportDetail;
import com.ciniam.internradar.model.ReportStats;
import com.ciniam.internradar.model.ReportView;
import com.ciniam.internradar.model.WeeklyReport;
import com.ciniam.internradar.security.AuthGuard;
import com.ciniam.internradar.service.ReportService;
import com.ciniam.internradar.service.RiskService;
import com.ciniam.internradar.service.WorkCollectService;
import com.ciniam.internradar.service.WorkRecordVisibility;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报告相关接口。userId 缺省时按登录人处理，实习生只能操作自己的数据
 */
@RestController
@RequestMapping("/report")
public class ReportController {

    private final ReportService reportService;
    private final WorkCollectService workCollectService;
    private final WorkRecordMapper workRecordMapper;
    private final WorkRecordVisibility visibility;
    private final RiskService riskService;
    private final LlmCallLogMapper llmCallLogMapper;
    private final DocRetrieveLogMapper docRetrieveLogMapper;
    private final AuthGuard authGuard;

    public ReportController(ReportService reportService,
                            WorkCollectService workCollectService,
                            WorkRecordMapper workRecordMapper,
                            WorkRecordVisibility visibility,
                            RiskService riskService,
                            LlmCallLogMapper llmCallLogMapper,
                            DocRetrieveLogMapper docRetrieveLogMapper,
                            AuthGuard authGuard) {
        this.reportService = reportService;
        this.workCollectService = workCollectService;
        this.workRecordMapper = workRecordMapper;
        this.visibility = visibility;
        this.riskService = riskService;
        this.llmCallLogMapper = llmCallLogMapper;
        this.docRetrieveLogMapper = docRetrieveLogMapper;
        this.authGuard = authGuard;
    }

    @PostMapping("/collect")
    public WorkCollectService.CollectResult collect(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return workCollectService.collect(authGuard.resolveUserId(userId), dateOrDefault(date));
    }

    @PostMapping("/generate")
    public DailyReport generate(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean push) {
        return reportService.generateDaily(authGuard.resolveUserId(userId), dateOrDefault(date), push);
    }

    @PostMapping("/morning")
    public DailyPlan morning(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean push) {
        return reportService.generateMorningPlan(authGuard.resolveUserId(userId), dateOrDefault(date), push);
    }

    @PostMapping("/weekly")
    public WeeklyReport weekly(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
            @RequestParam(defaultValue = "false") boolean push) {
        LocalDate start = weekStart != null ? weekStart
                : LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return reportService.generateWeekly(authGuard.resolveUserId(userId), start, push);
    }

    @GetMapping("/daily")
    public ReportView daily(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        ReportView view = reportService.getDaily(authGuard.resolveUserId(userId), dateOrDefault(date));
        if (view == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该日期暂无日报");
        }
        return view;
    }

    /**
     * 区间报告列表，start 与 end 缺省为最近 7 天，type 可选 DAILY、PLAN、WEEKLY
     */
    @GetMapping("/list")
    public List<ReportDetail> list(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required = false) String type) {
        LocalDate rangeEnd = end == null ? LocalDate.now() : end;
        LocalDate rangeStart = start == null ? rangeEnd.minusDays(6) : start;
        return reportService.listReports(authGuard.resolveUserId(userId), rangeStart, rangeEnd, type);
    }

    /**
     * 读取已生成的周报，weekStart 缺省为本周一
     */
    @GetMapping("/weekly")
    public ReportDetail weeklyReport(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        LocalDate start = weekStart != null ? weekStart
                : LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        ReportDetail detail = reportService.getWeekly(authGuard.resolveUserId(userId), start);
        if (detail == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该周暂无周报");
        }
        return detail;
    }

    /**
     * 未关闭的任务快照，供我的今日与导师视图共用
     */
    @GetMapping("/tasks")
    public List<WorkRecordEntity> tasks(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return riskService.openTasks(authGuard.resolveUserId(userId), dateOrDefault(date));
    }

    /**
     * 原始工作记录。非本人查看时过滤掉聊天采集的摘要
     */
    @GetMapping("/records")
    public List<WorkRecordEntity> records(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        String target = authGuard.resolveUserId(userId);
        LocalDate day = dateOrDefault(date);
        List<WorkRecordEntity> records = workRecordMapper.selectByUserAndRange(target,
                day.atStartOfDay(), day.plusDays(1).atStartOfDay());
        return visibility.visibleTo(authGuard.isSelf(target), records);
    }

    /**
     * 按天 × 按来源的统计。start 与 end 缺省为最近 7 天，天序列会把没有记录的日期补成 0
     * 看他人数据时与趋势一致，排除聊天采集的摘要
     */
    @GetMapping("/stats")
    public ReportStats stats(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        LocalDate rangeEnd = dateOrDefault(end);
        LocalDate rangeStart = start == null ? rangeEnd.minusDays(6) : start;
        if (rangeStart.isAfter(rangeEnd)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "start 不能晚于 end");
        }
        String target = authGuard.resolveUserId(userId);
        List<WorkRecordEntity> records = visibility.visibleTo(authGuard.isSelf(target),
                workRecordMapper.selectByUserAndRange(target,
                        rangeStart.atStartOfDay(), rangeEnd.plusDays(1).atStartOfDay()));

        Map<LocalDate, Map<String, Integer>> byDay = new LinkedHashMap<>();
        Map<LocalDate, Integer> workTotals = new LinkedHashMap<>();
        for (LocalDate cursor = rangeStart; !cursor.isAfter(rangeEnd); cursor = cursor.plusDays(1)) {
            byDay.put(cursor, new LinkedHashMap<>());
            workTotals.put(cursor, 0);
        }
        for (WorkRecordEntity record : records) {
            if (record.getOccurredAt() == null) {
                continue;
            }
            Map<String, Integer> bucket = byDay.get(record.getOccurredAt().toLocalDate());
            if (bucket == null) {
                continue;
            }
            String source = record.getSource() == null || record.getSource().isBlank()
                    ? "unknown" : record.getSource().trim();
            bucket.merge(source, 1, Integer::sum);
            workTotals.merge(record.getOccurredAt().toLocalDate(), 1, Integer::sum);
        }

        // 阅读与问答不计入 total，只作为热力图的额外来源
        if (authGuard.isSelf(target)) {
            LocalDateTime from = rangeStart.atStartOfDay();
            LocalDateTime to = rangeEnd.plusDays(1).atStartOfDay();
            mergeDaily(byDay, llmCallLogMapper.selectDailyAsk(target, from, to), "ask");
            mergeDaily(byDay, docRetrieveLogMapper.selectDailyRead(target, from, to), "doc-read");
        }

        List<ReportStats.Day> days = new ArrayList<>();
        byDay.forEach((day, sources) -> days.add(new ReportStats.Day(day, workTotals.getOrDefault(day, 0), sources)));
        return new ReportStats(target, rangeStart, rangeEnd, days);
    }

    private void mergeDaily(Map<LocalDate, Map<String, Integer>> byDay, List<Map<String, Object>> rows, String source) {
        if (rows == null) {
            return;
        }
        for (Map<String, Object> row : rows) {
            Object dateValue = row.get("statDate");
            Object totalValue = row.get("total");
            if (dateValue == null || !(totalValue instanceof Number number)) {
                continue;
            }
            LocalDate date = LocalDate.parse(String.valueOf(dateValue).substring(0, 10));
            Map<String, Integer> bucket = byDay.get(date);
            if (bucket != null) {
                bucket.merge(source, number.intValue(), Integer::sum);
            }
        }
    }

    private LocalDate dateOrDefault(LocalDate date) {
        return date == null ? LocalDate.now() : date;
    }
}
