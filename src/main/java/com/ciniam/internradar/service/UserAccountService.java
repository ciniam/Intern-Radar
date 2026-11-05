package com.ciniam.internradar.service;

import com.ciniam.internradar.entity.WorkRecordEntity;
import com.ciniam.internradar.mapper.LearnConversationMapper;
import com.ciniam.internradar.mapper.LearnMessageMapper;
import com.ciniam.internradar.mapper.ReportMapper;
import com.ciniam.internradar.mapper.RiskAlertMapper;
import com.ciniam.internradar.model.ProfileStats;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 个人中心的统计汇总。只统计本人数据，与团队无关
 */
@Service
public class UserAccountService {

    private static final int STATS_WINDOW_DAYS = 30;

    private final ReportService reportService;
    private final ReportMapper reportMapper;
    private final RiskAlertMapper riskAlertMapper;
    private final LearnConversationMapper conversationMapper;
    private final LearnMessageMapper messageMapper;
    private final WorkRecordVisibility visibility;

    public UserAccountService(ReportService reportService,
                              ReportMapper reportMapper,
                              RiskAlertMapper riskAlertMapper,
                              LearnConversationMapper conversationMapper,
                              LearnMessageMapper messageMapper,
                              WorkRecordVisibility visibility) {
        this.reportService = reportService;
        this.reportMapper = reportMapper;
        this.riskAlertMapper = riskAlertMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.visibility = visibility;
    }

    /**
     * 本人统计，工作记录沿用可见性规则，统计口径与导师视图一致
     */
    public ProfileStats stats(String userId) {
        LocalDate today = LocalDate.now();
        List<WorkRecordEntity> records = reportService.recordsOf(userId,
                today.minusDays(STATS_WINDOW_DAYS - 1L), today);
        int recordCount = visibility.countVisible(true, records);
        int reportCount = reportMapper.selectByUser(userId,
                today.minusDays(STATS_WINDOW_DAYS - 1L), today, null).size();
        int openAlerts = riskAlertMapper.selectByUserAndStatus(userId, "OPEN").size();
        return new ProfileStats(STATS_WINDOW_DAYS, recordCount, reportCount, openAlerts,
                conversationMapper.countByUser(userId), messageMapper.countQuestions(userId));
    }
}
