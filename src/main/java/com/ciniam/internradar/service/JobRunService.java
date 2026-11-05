package com.ciniam.internradar.service;

import com.ciniam.internradar.entity.JobRunEntity;
import com.ciniam.internradar.mapper.JobRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 任务幂等：同一人同一任务同一天只执行一次
 * 靠 job_run 唯一键抢占，抢占失败说明当天已跑过
 */
@Service
public class JobRunService {

    private static final Logger log = LoggerFactory.getLogger(JobRunService.class);

    private final JobRunMapper jobRunMapper;

    private final TeamScope teamScope;

    public JobRunService(JobRunMapper jobRunMapper, TeamScope teamScope) {
        this.jobRunMapper = jobRunMapper;
        this.teamScope = teamScope;
    }

    public Long lock(String userId, String jobType, LocalDate bizDate) {
        JobRunEntity entity = JobRunEntity.builder()
                .teamId(teamScope.of(userId))
                .userId(userId)
                .jobType(jobType)
                .bizDate(bizDate)
                .status("RUNNING")
                .build();
        int inserted = jobRunMapper.tryStart(entity);
        if (inserted == 0) {
            log.info("任务已执行过，跳过。用户 {}，任务 {}，日期 {}", userId, jobType, bizDate);
            return null;
        }
        return entity.getId();
    }

    /**
     * 释放抢占记录，用于执行失败需要允许重跑的场景
     */
    public void release(Long jobRunId) {
        if (jobRunId == null) {
            return;
        }
        jobRunMapper.finish(jobRunId, "RETRY", 0L, "执行失败已释放");
    }

    public void finish(Long jobRunId, String status, long durationMs, String errorMsg) {
        if (jobRunId == null) {
            return;
        }
        jobRunMapper.finish(jobRunId, status, durationMs,
                errorMsg == null || errorMsg.length() <= 480 ? errorMsg : errorMsg.substring(0, 480));
    }
}
