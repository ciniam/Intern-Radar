package com.ciniam.internradar.service;

import com.ciniam.internradar.entity.RiskAlertEntity;
import com.ciniam.internradar.entity.WorkRecordEntity;
import com.ciniam.internradar.mapper.RiskAlertMapper;
import com.ciniam.internradar.mapper.WorkRecordMapper;
import com.ciniam.internradar.model.InternStatus;
import com.ciniam.internradar.model.MentorOverview;
import com.ciniam.internradar.model.SysUser;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导师视图：把实习生名单与工作记录、任务、告警汇总成团队全貌
 * 导师看的是别人的数据，统计一律排除聊天采集的摘要
 */
@Service
public class MentorViewService {

    private static final int DEFAULT_WINDOW_DAYS = 7;
    private static final int TREND_WINDOW_DAYS = 14;
    private static final int HIGHLIGHT_ALERT_LIMIT = 5;
    private static final String NO_RISK = "NONE";
    private static final List<String> LEVELS = List.of("HIGH", "MEDIUM", "LOW");

    private final UserDirectory userDirectory;
    private final ReportService reportService;
    private final RiskService riskService;
    private final WorkRecordMapper workRecordMapper;
    private final RiskAlertMapper riskAlertMapper;
    private final WorkRecordVisibility visibility;

    public MentorViewService(UserDirectory userDirectory,
                             ReportService reportService,
                             RiskService riskService,
                             WorkRecordMapper workRecordMapper,
                             RiskAlertMapper riskAlertMapper,
                             WorkRecordVisibility visibility) {
        this.userDirectory = userDirectory;
        this.reportService = reportService;
        this.riskService = riskService;
        this.workRecordMapper = workRecordMapper;
        this.riskAlertMapper = riskAlertMapper;
        this.visibility = visibility;
    }

    public List<InternStatus> interns(int windowDays) {
        int days = normalizeWindow(windowDays);
        LocalDate today = LocalDate.now();
        List<InternStatus> statuses = new ArrayList<>();
        for (SysUser intern : userDirectory.interns()) {
            statuses.add(statusOf(intern, days, today));
        }
        return statuses;
    }

    public MentorOverview overview(int windowDays) {
        int days = normalizeWindow(windowDays);
        List<InternStatus> statuses = interns(days);
        int recordCount = 0;
        int openAlertCount = 0;
        int openTaskCount = 0;
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        LEVELS.forEach(level -> byLevel.put(level, 0));
        for (InternStatus status : statuses) {
            recordCount += status.recordCount();
            openAlertCount += status.openAlertCount();
            openTaskCount += status.openTaskCount();
            status.alertCounts().forEach((level, count) -> byLevel.merge(level, count, Integer::sum));
        }
        return new MentorOverview(days, statuses.size(), recordCount, openAlertCount, openTaskCount,
                byLevel, LocalDateTime.now(), statuses);
    }

    /**
     * 团队产出趋势，按天汇总工作记录条数
     */
    public List<Map<String, Object>> trend(int windowDays) {
        int days = windowDays <= 0 ? TREND_WINDOW_DAYS : windowDays;
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(days - 1L);
        Map<LocalDate, Integer> counts = new LinkedHashMap<>();
        for (LocalDate cursor = start; !cursor.isAfter(today); cursor = cursor.plusDays(1)) {
            counts.put(cursor, 0);
        }
        for (SysUser intern : userDirectory.interns()) {
            for (WorkRecordEntity record : reportService.recordsOf(intern.userId(), start, today)) {
                if (visibility.isChat(record) || record.getOccurredAt() == null) {
                    continue;
                }
                counts.computeIfPresent(record.getOccurredAt().toLocalDate(), (key, value) -> value + 1);
            }
        }
        List<Map<String, Object>> series = new ArrayList<>();
        counts.forEach((day, value) -> {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", day.toString());
            point.put("value", value);
            series.add(point);
        });
        return series;
    }

    /**
     * 名下全部实习生的未处理告警，按等级从高到低排序
     */
    public List<RiskAlertEntity> openAlerts() {
        List<RiskAlertEntity> alerts = new ArrayList<>();
        for (SysUser intern : userDirectory.interns()) {
            alerts.addAll(riskAlertMapper.selectByUserAndStatus(intern.userId(), "OPEN"));
        }
        alerts.sort((left, right) -> rank(right.getLevel()) - rank(left.getLevel()));
        return alerts;
    }

    private InternStatus statusOf(SysUser intern, int days, LocalDate today) {
        String userId = intern.userId();
        List<WorkRecordEntity> records = reportService.recordsOf(userId, today.minusDays(days - 1L), today);
        List<WorkRecordEntity> openTasks = riskService.openTasks(userId, today);
        List<RiskAlertEntity> alerts = riskAlertMapper.selectByUserAndStatus(userId, "OPEN");
        Map<String, Integer> counts = new LinkedHashMap<>();
        LEVELS.forEach(level -> counts.put(level, 0));
        for (RiskAlertEntity alert : alerts) {
            counts.merge(alert.getLevel(), 1, Integer::sum);
        }
        return new InternStatus(
                userId,
                intern.name(),
                intern.mentorUserId(),
                visibility.countVisible(false, records),
                openTasks.size(),
                alerts.size(),
                highestLevel(alerts),
                workRecordMapper.selectLastOccurredAt(userId),
                counts,
                alertTitles(alerts),
                dailyCounts(records, days, today));
    }

    /**
     * 窗口内每天的记录条数，没有记录的天补 0，看板热力图直接用
     */
    private List<InternStatus.DayCount> dailyCounts(List<WorkRecordEntity> records, int days, LocalDate today) {
        Map<LocalDate, Integer> perDay = new LinkedHashMap<>();
        for (LocalDate cursor = today.minusDays(days - 1L); !cursor.isAfter(today); cursor = cursor.plusDays(1)) {
            perDay.put(cursor, 0);
        }
        for (WorkRecordEntity record : visibility.visibleTo(false, records)) {
            if (record.getOccurredAt() == null) {
                continue;
            }
            perDay.computeIfPresent(record.getOccurredAt().toLocalDate(), (key, value) -> value + 1);
        }
        List<InternStatus.DayCount> series = new ArrayList<>();
        perDay.forEach((day, value) -> series.add(new InternStatus.DayCount(day.toString(), value)));
        return series;
    }

    private String highestLevel(List<RiskAlertEntity> alerts) {
        String highest = NO_RISK;
        for (RiskAlertEntity alert : alerts) {
            if (rank(alert.getLevel()) > rank(highest)) {
                highest = alert.getLevel();
            }
        }
        return highest;
    }

    private int rank(String level) {
        if (level == null) {
            return 0;
        }
        return switch (level) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private List<String> alertTitles(List<RiskAlertEntity> alerts) {
        List<String> titles = new ArrayList<>();
        for (RiskAlertEntity alert : alerts) {
            if (titles.size() >= HIGHLIGHT_ALERT_LIMIT) {
                break;
            }
            titles.add("[" + alert.getLevel() + "] " + alert.getType() + "：" + firstEvidence(alert.getEvidence()));
        }
        return titles;
    }

    private String firstEvidence(String evidence) {
        if (evidence == null || evidence.isBlank()) {
            return "";
        }
        int split = evidence.indexOf('｜');
        String first = split < 0 ? evidence : evidence.substring(0, split);
        return first.length() > 80 ? first.substring(0, 80) : first;
    }

    private int normalizeWindow(int windowDays) {
        return windowDays <= 0 ? DEFAULT_WINDOW_DAYS : windowDays;
    }
}
