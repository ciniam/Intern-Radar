package com.ciniam.internradar.model;

import java.util.List;

/**
 * 用户偏好。缺省值即默认行为，库里没有行时按这份默认值处理
 * zentaoPassword 只写不读：保存时非空即覆盖，读出来一律为 null
 */
public record UserPreference(
        String userId,
        String displayName,
        String feishuOpenId,
        List<String> gitAuthors,
        List<String> gitRepositories,
        String zentaoAccount,
        String zentaoPassword,
        boolean zentaoLoginAllowed,
        boolean morningPushEnabled,
        String morningPushTime,
        boolean eveningPushEnabled,
        String eveningPushTime,
        boolean alertPushEnabled,
        String quietStart,
        String quietEnd,
        String pushChannel,
        String reportStyle,
        boolean attachCommitHash,
        int planFocusLimit,
        boolean weeklyEnabled,
        String weeklyWeekday,
        String weeklyTime,
        String weeklyScope,
        List<String> watchInterns,
        boolean weeklyTrendEnabled,
        List<String> riskTypes,
        String riskMinLevel,
        List<String> learnCategories,
        List<String> learnTags,
        String learnAnswerStyle
) {

    public static final String STYLE_COMPACT = "COMPACT";
    public static final String STYLE_DETAIL = "DETAIL";
    public static final String CHANNEL_USER = "USER";
    public static final String CHANNEL_GROUP = "GROUP";
    public static final String CHANNEL_BOTH = "BOTH";
    public static final String SCOPE_WATCHED = "WATCHED";
    public static final String SCOPE_ALL = "ALL";

    public static final String DEFAULT_MORNING_TIME = "09:00";
    public static final String DEFAULT_EVENING_TIME = "18:30";
    public static final String DEFAULT_WEEKLY_WEEKDAY = "FRI";
    public static final String DEFAULT_WEEKLY_TIME = "18:30";
    public static final String DEFAULT_ANSWER_STYLE = "STANDARD";
    public static final String DEFAULT_RISK_MIN_LEVEL = "LOW";

    public boolean compactReport() {
        return STYLE_COMPACT.equalsIgnoreCase(reportStyle);
    }

    public boolean pushToUser() {
        return CHANNEL_USER.equalsIgnoreCase(pushChannel) || CHANNEL_BOTH.equalsIgnoreCase(pushChannel);
    }

    public boolean pushToGroup() {
        return CHANNEL_GROUP.equalsIgnoreCase(pushChannel) || CHANNEL_BOTH.equalsIgnoreCase(pushChannel);
    }

    public boolean watchAllInterns() {
        return SCOPE_ALL.equalsIgnoreCase(weeklyScope);
    }

    public boolean hasOwnZentaoAccount() {
        return zentaoAccount != null && !zentaoAccount.isBlank();
    }

    /**
     * 强制把偏好写到指定用户上，避免请求体里带别人的标识
     */
    public UserPreference withUserId(String newUserId) {
        return new UserPreference(newUserId, displayName, feishuOpenId, gitAuthors, gitRepositories,
                zentaoAccount, zentaoPassword, zentaoLoginAllowed, morningPushEnabled, morningPushTime,
                eveningPushEnabled, eveningPushTime, alertPushEnabled, quietStart, quietEnd,
                pushChannel, reportStyle, attachCommitHash, planFocusLimit, weeklyEnabled,
                weeklyWeekday, weeklyTime, weeklyScope, watchInterns, weeklyTrendEnabled,
                riskTypes, riskMinLevel, learnCategories, learnTags, learnAnswerStyle);
    }

    public UserPreference withDisplayName(String value) {
        return new UserPreference(userId, value, feishuOpenId, gitAuthors, gitRepositories,
                zentaoAccount, zentaoPassword, zentaoLoginAllowed, morningPushEnabled, morningPushTime,
                eveningPushEnabled, eveningPushTime, alertPushEnabled, quietStart, quietEnd,
                pushChannel, reportStyle, attachCommitHash, planFocusLimit, weeklyEnabled,
                weeklyWeekday, weeklyTime, weeklyScope, watchInterns, weeklyTrendEnabled,
                riskTypes, riskMinLevel, learnCategories, learnTags, learnAnswerStyle);
    }

    public UserPreference withFeishuOpenId(String value) {
        return new UserPreference(userId, displayName, value, gitAuthors, gitRepositories,
                zentaoAccount, zentaoPassword, zentaoLoginAllowed, morningPushEnabled, morningPushTime,
                eveningPushEnabled, eveningPushTime, alertPushEnabled, quietStart, quietEnd,
                pushChannel, reportStyle, attachCommitHash, planFocusLimit, weeklyEnabled,
                weeklyWeekday, weeklyTime, weeklyScope, watchInterns, weeklyTrendEnabled,
                riskTypes, riskMinLevel, learnCategories, learnTags, learnAnswerStyle);
    }

    public UserPreference withGitAuthors(List<String> value) {
        return new UserPreference(userId, displayName, feishuOpenId, value, gitRepositories,
                zentaoAccount, zentaoPassword, zentaoLoginAllowed, morningPushEnabled, morningPushTime,
                eveningPushEnabled, eveningPushTime, alertPushEnabled, quietStart, quietEnd,
                pushChannel, reportStyle, attachCommitHash, planFocusLimit, weeklyEnabled,
                weeklyWeekday, weeklyTime, weeklyScope, watchInterns, weeklyTrendEnabled,
                riskTypes, riskMinLevel, learnCategories, learnTags, learnAnswerStyle);
    }

    public UserPreference withGitRepositories(List<String> value) {
        return new UserPreference(userId, displayName, feishuOpenId, gitAuthors, value,
                zentaoAccount, zentaoPassword, zentaoLoginAllowed, morningPushEnabled, morningPushTime,
                eveningPushEnabled, eveningPushTime, alertPushEnabled, quietStart, quietEnd,
                pushChannel, reportStyle, attachCommitHash, planFocusLimit, weeklyEnabled,
                weeklyWeekday, weeklyTime, weeklyScope, watchInterns, weeklyTrendEnabled,
                riskTypes, riskMinLevel, learnCategories, learnTags, learnAnswerStyle);
    }

    public UserPreference withMorningPushTime(String value) {
        return new UserPreference(userId, displayName, feishuOpenId, gitAuthors, gitRepositories,
                zentaoAccount, zentaoPassword, zentaoLoginAllowed, morningPushEnabled, value,
                eveningPushEnabled, eveningPushTime, alertPushEnabled, quietStart, quietEnd,
                pushChannel, reportStyle, attachCommitHash, planFocusLimit, weeklyEnabled,
                weeklyWeekday, weeklyTime, weeklyScope, watchInterns, weeklyTrendEnabled,
                riskTypes, riskMinLevel, learnCategories, learnTags, learnAnswerStyle);
    }

    public UserPreference withEveningPushTime(String value) {
        return new UserPreference(userId, displayName, feishuOpenId, gitAuthors, gitRepositories,
                zentaoAccount, zentaoPassword, zentaoLoginAllowed, morningPushEnabled, morningPushTime,
                eveningPushEnabled, value, alertPushEnabled, quietStart, quietEnd,
                pushChannel, reportStyle, attachCommitHash, planFocusLimit, weeklyEnabled,
                weeklyWeekday, weeklyTime, weeklyScope, watchInterns, weeklyTrendEnabled,
                riskTypes, riskMinLevel, learnCategories, learnTags, learnAnswerStyle);
    }

    public UserPreference withLearnCategories(List<String> value) {
        return new UserPreference(userId, displayName, feishuOpenId, gitAuthors, gitRepositories,
                zentaoAccount, zentaoPassword, zentaoLoginAllowed, morningPushEnabled, morningPushTime,
                eveningPushEnabled, eveningPushTime, alertPushEnabled, quietStart, quietEnd,
                pushChannel, reportStyle, attachCommitHash, planFocusLimit, weeklyEnabled,
                weeklyWeekday, weeklyTime, weeklyScope, watchInterns, weeklyTrendEnabled,
                riskTypes, riskMinLevel, value, learnTags, learnAnswerStyle);
    }

    public UserPreference withLearnTags(List<String> value) {
        return new UserPreference(userId, displayName, feishuOpenId, gitAuthors, gitRepositories,
                zentaoAccount, zentaoPassword, zentaoLoginAllowed, morningPushEnabled, morningPushTime,
                eveningPushEnabled, eveningPushTime, alertPushEnabled, quietStart, quietEnd,
                pushChannel, reportStyle, attachCommitHash, planFocusLimit, weeklyEnabled,
                weeklyWeekday, weeklyTime, weeklyScope, watchInterns, weeklyTrendEnabled,
                riskTypes, riskMinLevel, learnCategories, value, learnAnswerStyle);
    }
}
