package com.ciniam.internradar.scheduler;

import com.ciniam.internradar.config.InternRadarProperties;
import com.ciniam.internradar.model.SysUser;
import com.ciniam.internradar.model.UserPreference;
import com.ciniam.internradar.security.TeamContext;
import com.ciniam.internradar.service.JobRunService;
import com.ciniam.internradar.service.PreferenceService;
import com.ciniam.internradar.service.ReportService;
import com.ciniam.internradar.service.UserDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 早推送定时任务：按窗口扫描，到点的人生成今日计划并发给本人
 * 每个人推送时间来自自己的偏好，job_run 保证同一天只推一次
 */
@Component
public class MorningPlanJob {

    private static final Logger log = LoggerFactory.getLogger(MorningPlanJob.class);

    static final String JOB_TYPE = "MORNING_PLAN";

    private final InternRadarProperties properties;
    private final ReportService reportService;
    private final JobRunService jobRunService;
    private final PreferenceService preferenceService;
    private final UserDirectory userDirectory;

    public MorningPlanJob(InternRadarProperties properties,
                          ReportService reportService,
                          JobRunService jobRunService,
                          PreferenceService preferenceService,
                          UserDirectory userDirectory) {
        this.properties = properties;
        this.reportService = reportService;
        this.jobRunService = jobRunService;
        this.preferenceService = preferenceService;
        this.userDirectory = userDirectory;
    }

    @Scheduled(cron = "${intern-radar.scheduler.morning-scan-cron}")
    public void run() {
        if (!properties.getScheduler().isEnabled() || !properties.getScheduler().isMorningEnabled()) {
            return;
        }
        LocalDate date = LocalDate.now();
        LocalTime now = LocalTime.now();
        for (SysUser intern : userDirectory.internsAcrossTeams()) {
            TeamContext.set(intern.teamId());
            String userId = intern.userId();
            UserPreference preference = preferenceService.get(userId);
            if (!preference.morningPushEnabled()) {
                continue;
            }
            if (!preferenceService.inPushWindow(preference.morningPushTime(), now)) {
                continue;
            }
            Long jobRunId = jobRunService.lock(userId, JOB_TYPE, date);
            if (jobRunId == null) {
                continue;
            }
            long begin = System.currentTimeMillis();
            try {
                reportService.generateMorningPlan(userId, date, true);
                jobRunService.finish(jobRunId, "SUCCESS", System.currentTimeMillis() - begin, null);
                log.info("今日计划已推送，用户 {}，时间 {}", userId, preference.morningPushTime());
            } catch (Exception e) {
                log.error("今日计划任务失败，用户 {}，日期 {}", userId, date, e);
                jobRunService.finish(jobRunId, "FAILED", System.currentTimeMillis() - begin, e.getMessage());
            }
        }
        TeamContext.clear();
    }
}
