package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.UserPreferenceEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserPreferenceMapper {

    @Insert("""
            INSERT INTO user_preference
                (team_id, user_id, display_name, feishu_open_id, git_authors, git_repositories,
                 zentao_account, zentao_password, zentao_login_allowed,
                 morning_push_enabled, morning_push_time, evening_push_enabled, evening_push_time,
                 alert_push_enabled, quiet_start, quiet_end, push_channel,
                 report_style, attach_commit_hash, plan_focus_limit,
                 weekly_enabled, weekly_weekday, weekly_time, weekly_scope, watch_interns,
                 weekly_trend_enabled, risk_types, risk_min_level,
                 learn_categories, learn_tags, learn_answer_style, updated_at)
            VALUES
                (#{teamId}, #{userId}, #{displayName}, #{feishuOpenId}, #{gitAuthors}, #{gitRepositories},
                 #{zentaoAccount}, #{zentaoPassword}, #{zentaoLoginAllowed},
                 #{morningPushEnabled}, #{morningPushTime}, #{eveningPushEnabled}, #{eveningPushTime},
                 #{alertPushEnabled}, #{quietStart}, #{quietEnd}, #{pushChannel},
                 #{reportStyle}, #{attachCommitHash}, #{planFocusLimit},
                 #{weeklyEnabled}, #{weeklyWeekday}, #{weeklyTime}, #{weeklyScope}, #{watchInterns},
                 #{weeklyTrendEnabled}, #{riskTypes}, #{riskMinLevel},
                 #{learnCategories}, #{learnTags}, #{learnAnswerStyle}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
                display_name = VALUES(display_name),
                feishu_open_id = VALUES(feishu_open_id),
                git_authors = VALUES(git_authors),
                git_repositories = VALUES(git_repositories),
                zentao_account = VALUES(zentao_account),
                zentao_password = VALUES(zentao_password),
                zentao_login_allowed = VALUES(zentao_login_allowed),
                morning_push_enabled = VALUES(morning_push_enabled),
                morning_push_time = VALUES(morning_push_time),
                evening_push_enabled = VALUES(evening_push_enabled),
                evening_push_time = VALUES(evening_push_time),
                alert_push_enabled = VALUES(alert_push_enabled),
                quiet_start = VALUES(quiet_start),
                quiet_end = VALUES(quiet_end),
                push_channel = VALUES(push_channel),
                report_style = VALUES(report_style),
                attach_commit_hash = VALUES(attach_commit_hash),
                plan_focus_limit = VALUES(plan_focus_limit),
                weekly_enabled = VALUES(weekly_enabled),
                weekly_weekday = VALUES(weekly_weekday),
                weekly_time = VALUES(weekly_time),
                weekly_scope = VALUES(weekly_scope),
                watch_interns = VALUES(watch_interns),
                weekly_trend_enabled = VALUES(weekly_trend_enabled),
                risk_types = VALUES(risk_types),
                risk_min_level = VALUES(risk_min_level),
                learn_categories = VALUES(learn_categories),
                learn_tags = VALUES(learn_tags),
                learn_answer_style = VALUES(learn_answer_style),
                updated_at = VALUES(updated_at)
            """)
    int upsert(UserPreferenceEntity entity);

    @Select("SELECT * FROM user_preference WHERE user_id = #{userId} LIMIT 1")
    UserPreferenceEntity selectByUser(@Param("userId") String userId);
}
