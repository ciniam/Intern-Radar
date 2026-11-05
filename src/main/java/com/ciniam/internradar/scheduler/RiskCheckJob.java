package com.ciniam.internradar.scheduler;

import com.ciniam.internradar.config.InternRadarProperties;
import com.ciniam.internradar.model.RiskAlert;
import com.ciniam.internradar.model.SysUser;
import com.ciniam.internradar.model.UserPreference;
import com.ciniam.internradar.push.PushService;
import com.ciniam.internradar.security.TeamContext;
import com.ciniam.internradar.service.JobRunService;
import com.ciniam.internradar.service.PreferenceService;
import com.ciniam.internradar.service.RiskService;
import com.ciniam.internradar.service.UserDirectory;
import com.ciniam.internradar.service.WorkCollectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 风险检查定时任务：先采集当天数据，再跑规则，最后推送未通知的告警
 */
@Component
public class RiskCheckJob {

    private static final Logger log = LoggerFactory.getLogger(RiskCheckJob.class);

    static final String JOB_TYPE = "RISK_CHECK";

    private final InternRadarProperties properties;
    private final RiskService riskService;
    private final WorkCollectService workCollectService;
    private final JobRunService jobRunService;
    private final UserDirectory userDirectory;
    private final PreferenceService preferenceService;
    private final PushService pushService;

    public RiskCheckJob(InternRadarProperties properties,
                        RiskService riskService,
                        WorkCollectService workCollectService,
                        JobRunService jobRunService,
                        UserDirectory userDirectory,
                        PreferenceService preferenceService,
                        PushService pushService) {
        this.properties = properties;
        this.riskService = riskService;
        this.workCollectService = workCollectService;
        this.jobRunService = jobRunService;
        this.userDirectory = userDirectory;
        this.preferenceService = preferenceService;
        this.pushService = pushService;
    }

    @Scheduled(cron = "${intern-radar.scheduler.risk-check-cron}")
    public void run() {
        if (!properties.getScheduler().isEnabled() || !properties.getScheduler().isRiskEnabled()) {
            return;
        }
        LocalDate date = LocalDate.now();
        List<SysUser> interns = userDirectory.internsAcrossTeams();
        log.info("风险检查开始，日期 {}，人数 {}", date, interns.size());
        for (SysUser intern : interns) {
            TeamContext.set(intern.teamId());
            String userId = intern.userId();
            Long jobRunId = jobRunService.lock(userId, JOB_TYPE, date);
            if (jobRunId == null) {
                continue;
            }
            long begin = System.currentTimeMillis();
            try {
                workCollectService.collect(userId, date);
                List<RiskAlert> created = riskService.evaluate(userId, date);
                int notified = riskService.notifyPending(userId);
                log.info("风险检查完成，用户 {}，新增 {} 条，通知 {} 条", userId, created.size(), notified);
                notifyMentors(userId, intern, created, date);
                jobRunService.finish(jobRunId, "SUCCESS", System.currentTimeMillis() - begin, null);
            } catch (Exception e) {
                log.error("风险检查失败，用户 {}，日期 {}", userId, date, e);
                jobRunService.finish(jobRunId, "FAILED", System.currentTimeMillis() - begin, e.getMessage());
            }
        }
        TeamContext.clear();
        log.info("风险检查结束，日期 {}", date);
    }

    /**
     * 新增告警按导师的关注范围与接收偏好投递一份摘要
     */
    private void notifyMentors(String internId, SysUser intern, List<RiskAlert> created, LocalDate date) {
        if (created.isEmpty()) {
            return;
        }
        List<SysUser> mentors = userDirectory.mentors();
        for (SysUser mentor : mentors) {
            TeamContext.set(mentor.teamId());
            UserPreference preference = preferenceService.get(mentor.userId());
            if (!preferenceService.watchedInterns(preference).contains(internId)) {
                continue;
            }
            List<RiskAlert> accepted = new ArrayList<>();
            for (RiskAlert alert : created) {
                if (!preferenceService.alertTypeAccepted(preference, alert.type())) {
                    continue;
                }
                if (!preferenceService.alertLevelAccepted(preference, alert.level().name())) {
                    continue;
                }
                accepted.add(alert);
            }
            if (accepted.isEmpty()) {
                continue;
            }
            Long lock = jobRunService.lock(mentor.userId(), JOB_TYPE + "_MENTOR", date);
            if (lock == null) {
                continue;
            }
            long begin = System.currentTimeMillis();
            try {
                pushService.sendToUser(mentor.userId(), "RISK_ALERT", formatDigest(intern, accepted));
                jobRunService.finish(lock, "SUCCESS", System.currentTimeMillis() - begin, null);
            } catch (Exception e) {
                log.error("导师告警投递失败，导师 {}", mentor.userId(), e);
                jobRunService.finish(lock, "FAILED", System.currentTimeMillis() - begin, e.getMessage());
            }
        }
    }

    private String formatDigest(SysUser intern, List<RiskAlert> alerts) {
        StringBuilder builder = new StringBuilder();
        builder.append("实习雷达·风险提醒 ").append(intern.name()).append(' ').append(intern.userId()).append('\n');
        for (RiskAlert alert : alerts) {
            builder.append('\n').append('[').append(alert.level()).append("] ").append(alert.title()).append('\n');
            for (String evidence : alert.evidence()) {
                builder.append("- ").append(evidence).append('\n');
            }
            builder.append("建议：").append(alert.suggestion() == null ? "" : alert.suggestion()).append('\n');
        }
        return builder.toString();
    }
}
