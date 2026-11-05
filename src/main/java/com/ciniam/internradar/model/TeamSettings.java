package com.ciniam.internradar.model;

/**
 * 团队级设置。字段缺省值来自配置文件，库里存在则以库为准
 */
public record TeamSettings(
        int noOutputDays,
        int taskStallDays,
        int deadlineDays,
        int deadlineProgress,
        int alertThrottleHours,
        String defaultMorningTime,
        String defaultEveningTime,
        String knowledgeRootDir,
        int knowledgeMaxReadChars,
        int knowledgeSearchTopK
) {

    public TeamSettings withRisk(int noOutputDays, int taskStallDays, int deadlineDays,
                                 int deadlineProgress, int alertThrottleHours) {
        return new TeamSettings(noOutputDays, taskStallDays, deadlineDays, deadlineProgress,
                alertThrottleHours, defaultMorningTime, defaultEveningTime,
                knowledgeRootDir, knowledgeMaxReadChars, knowledgeSearchTopK);
    }
}
