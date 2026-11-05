package com.ciniam.internradar.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * user_preference 表映射对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPreferenceEntity {

    private Long id;
    private Long teamId;
    private String userId;
    private String displayName;
    private String feishuOpenId;
    private String gitAuthors;
    private String gitRepositories;
    private String zentaoAccount;
    private String zentaoPassword;
    private Boolean zentaoLoginAllowed;
    private Boolean morningPushEnabled;
    private String morningPushTime;
    private Boolean eveningPushEnabled;
    private String eveningPushTime;
    private Boolean alertPushEnabled;
    private String quietStart;
    private String quietEnd;
    private String pushChannel;
    private String reportStyle;
    private Boolean attachCommitHash;
    private Integer planFocusLimit;
    private Boolean weeklyEnabled;
    private String weeklyWeekday;
    private String weeklyTime;
    private String weeklyScope;
    private String watchInterns;
    private Boolean weeklyTrendEnabled;
    private String riskTypes;
    private String riskMinLevel;
    private String learnCategories;
    private String learnTags;
    private String learnAnswerStyle;
    private LocalDateTime updatedAt;
}
