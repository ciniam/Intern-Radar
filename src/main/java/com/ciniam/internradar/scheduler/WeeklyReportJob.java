package com.ciniam.internradar.scheduler;

import com.ciniam.internradar.config.InternRadarProperties;
import com.ciniam.internradar.model.SysUser;
import com.ciniam.internradar.model.UserPreference;
import com.ciniam.internradar.model.WeeklyReport;
import com.ciniam.internradar.push.PushService;
import com.ciniam.internradar.security.TeamContext;
import com.ciniam.internradar.service.JobRunService;
import com.ciniam.internradar.service.PreferenceService;
import com.ciniam.internradar.service.ReportService;
import com.ciniam.internradar.service.UserDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * 周报任务：先生成各实习生的周报，再按导师各自的接收时间与关注范围投递
 * 生成与投递分开记幂等键，导师改时间或改范围后不必重新生成
 */
@Component
public class WeeklyReportJob {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportJob.class);

    static final String JOB_TYPE = "WEEKLY_REPORT";
    private static final String JOB_TYPE_PUSH = "WEEKLY_PUSH";

    private final InternRadarProperties properties;
    private final ReportService reportService;
    private final JobRunService jobRunService;
    private final PreferenceService preferenceService;
    private final UserDirectory userDirectory;
    private final PushService pushService;

    public WeeklyReportJob(InternRadarProperties properties,
                           ReportService reportService,
                           JobRunService jobRunService,
                           PreferenceService preferenceService,
                           UserDirectory userDirectory,
                           PushService pushService) {
        this.properties = properties;
        this.reportService = reportService;
        this.jobRunService = jobRunService;
        this.preferenceService = preferenceService;
        this.userDirectory = userDirectory;
        this.pushService = pushService;
    }

    @Scheduled(cron = "${intern-radar.scheduler.weekly-scan-cron}")
    public void run() {
        if (!properties.getScheduler().isEnabled() || !properties.getScheduler().isWeeklyEnabled()) {
            return;
        }
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        generateAll(weekStart);
        pushToMentors(weekStart, today, LocalTime.now());
    }

    /**
     * 每个实习生每周只生成一次，生成与推送解耦
     */
    private void generateAll(LocalDate weekStart) {
        List<SysUser> interns = userDirectory.internsAcrossTeams();
        for (SysUser intern : interns) {
            TeamContext.set(intern.teamId());
            Long jobRunId = jobRunService.lock(intern.userId(), JOB_TYPE, weekStart);
            if (jobRunId == null) {
                continue;
            }
            long begin = System.currentTimeMillis();
            try {
                reportService.generateWeekly(intern.userId(), weekStart, false);
                jobRunService.finish(jobRunId, "SUCCESS", System.currentTimeMillis() - begin, null);
            } catch (Exception e) {
                log.error("周报生成失败，用户 {}，周区间 {}", intern.userId(), weekStart, e);
                jobRunService.finish(jobRunId, "FAILED", System.currentTimeMillis() - begin, e.getMessage());
            }
        }
    }

    /**
     * 每位导师按自己的接收时间与关注范围收周报
     */
    private void pushToMentors(LocalDate weekStart, LocalDate today, LocalTime now) {
        for (SysUser mentor : userDirectory.mentorsAcrossTeams()) {
            TeamContext.set(mentor.teamId());
            UserPreference preference = preferenceService.get(mentor.userId());
            if (!preference.weeklyEnabled()) {
                continue;
            }
            if (!weekdayMatches(preference.weeklyWeekday(), today.getDayOfWeek())) {
                continue;
            }
            if (!preferenceService.inPushWindow(preference.weeklyTime(), now)) {
                continue;
            }
            Long jobRunId = jobRunService.lock(mentor.userId(), JOB_TYPE_PUSH, weekStart);
            if (jobRunId == null) {
                continue;
            }
            long begin = System.currentTimeMillis();
            try {
                int delivered = deliver(mentor, preference, weekStart);
                jobRunService.finish(jobRunId, "SUCCESS", System.currentTimeMillis() - begin, null);
                log.info("周报投递完成，导师 {}，含 {} 人", mentor.userId(), delivered);
            } catch (Exception e) {
                log.error("周报投递失败，导师 {}", mentor.userId(), e);
                jobRunService.finish(jobRunId, "FAILED", System.currentTimeMillis() - begin, e.getMessage());
            }
        }
    }

    private int deliver(SysUser mentor, UserPreference preference, LocalDate weekStart) {
        List<String> watched = preferenceService.watchedInterns(preference);
        StringBuilder content = new StringBuilder();
        content.append("实习雷达·团队周报 ").append(weekStart).append(" 至 ")
                .append(weekStart.plusDays(6)).append('\n');
        int count = 0;
        for (String internId : watched) {
            WeeklyReport report = reportService.getWeeklyReport(internId, weekStart);
            if (report == null) {
                continue;
            }
            content.append('\n').append(reportService.formatWeekly(internId, weekStart,
                    weekStart.plusDays(6), report, preference)).append('\n');
            count++;
        }
        if (count == 0) {
            log.info("导师 {} 本周没有可投递的周报", mentor.userId());
            return 0;
        }
        pushService.sendToUser(mentor.userId(), "WEEKLY", content.toString());
        return count;
    }

    private boolean weekdayMatches(String configured, DayOfWeek today) {
        if (configured == null || configured.isBlank()) {
            return false;
        }
        return configured.trim().equalsIgnoreCase(today.name());
    }
}
