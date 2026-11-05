package com.ciniam.internradar.service;

import com.ciniam.internradar.config.InternRadarProperties;
import com.ciniam.internradar.entity.UserPreferenceEntity;
import com.ciniam.internradar.mapper.UserPreferenceMapper;
import com.ciniam.internradar.model.DataSourceCredential;
import com.ciniam.internradar.model.OnboardingView;
import com.ciniam.internradar.model.SysUser;
import com.ciniam.internradar.model.UserPreference;
import com.ciniam.internradar.security.TokenCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 用户偏好读写。偏好真正参与行为：报告风格与证据、计划条数、告警过滤与推送时段、
 * 学习推荐范围，以及个人级的数据源凭据
 */
@Service
public class PreferenceService {

    private static final Logger log = LoggerFactory.getLogger(PreferenceService.class);

    private static final int PUSH_WINDOW_MINUTES = 120;
    private static final List<String> ALL_RISK_TYPES = List.of("NO_OUTPUT", "TASK_STALL", "DEADLINE_RISK",
            "TREND_ANOMALY", "PITFALL");

    private final UserPreferenceMapper preferenceMapper;
    private final UserDirectory userDirectory;
    private final TeamSettingService teamSettingService;
    private final InternRadarProperties properties;
    private final SystemConfigService systemConfig;
    private final TokenCipher tokenCipher;

    private final TeamScope teamScope;

    public PreferenceService(UserPreferenceMapper preferenceMapper,
                             UserDirectory userDirectory,
                             TeamSettingService teamSettingService,
                             InternRadarProperties properties,
                             SystemConfigService systemConfig,
                             TokenCipher tokenCipher,
                             TeamScope teamScope) {
        this.preferenceMapper = preferenceMapper;
        this.userDirectory = userDirectory;
        this.teamSettingService = teamSettingService;
        this.properties = properties;
        this.systemConfig = systemConfig;
        this.tokenCipher = tokenCipher;
        this.teamScope = teamScope;
    }

    /**
     * 读偏好。没有行时按默认值返回，并补上用户目录里的基础资料
     * 密码不回传，只回传是否已设置
     */
    public UserPreference get(String userId) {
        UserPreferenceEntity entity = preferenceMapper.selectByUser(userId);
        SysUser user = userDirectory.byUserId(userId);
        if (entity == null) {
            return defaults(userId, user);
        }
        return new UserPreference(
                userId,
                text(entity.getDisplayName(), user == null ? userId : user.name()),
                text(entity.getFeishuOpenId(), user == null ? null : user.feishuOpenId()),
                splitList(entity.getGitAuthors()),
                splitList(entity.getGitRepositories()),
                blankToNull(entity.getZentaoAccount()),
                null,
                yes(entity.getZentaoLoginAllowed()),
                yes(entity.getMorningPushEnabled()),
                text(entity.getMorningPushTime(), teamSettingService.current().defaultMorningTime()),
                yes(entity.getEveningPushEnabled()),
                text(entity.getEveningPushTime(), teamSettingService.current().defaultEveningTime()),
                yes(entity.getAlertPushEnabled()),
                blankToNull(entity.getQuietStart()),
                blankToNull(entity.getQuietEnd()),
                text(entity.getPushChannel(), UserPreference.CHANNEL_USER),
                text(entity.getReportStyle(), UserPreference.STYLE_DETAIL),
                yes(entity.getAttachCommitHash()),
                positive(entity.getPlanFocusLimit(), 3),
                yes(entity.getWeeklyEnabled()),
                text(entity.getWeeklyWeekday(), UserPreference.DEFAULT_WEEKLY_WEEKDAY),
                text(entity.getWeeklyTime(), UserPreference.DEFAULT_WEEKLY_TIME),
                text(entity.getWeeklyScope(), UserPreference.SCOPE_WATCHED),
                splitList(entity.getWatchInterns()),
                yes(entity.getWeeklyTrendEnabled()),
                fallbackList(entity.getRiskTypes(), ALL_RISK_TYPES),
                text(entity.getRiskMinLevel(), UserPreference.DEFAULT_RISK_MIN_LEVEL),
                splitList(entity.getLearnCategories()),
                splitList(entity.getLearnTags()),
                text(entity.getLearnAnswerStyle(), UserPreference.DEFAULT_ANSWER_STYLE));
    }

    public boolean passwordSet(String userId) {
        UserPreferenceEntity entity = preferenceMapper.selectByUser(userId);
        return entity != null && entity.getZentaoPassword() != null && !entity.getZentaoPassword().isBlank();
    }

    /**
     * 保存偏好。密码字段约定：null 表示不改动，空串表示清除，其余按新值加密入库
     */
    public UserPreference save(UserPreference preference) {
        UserPreferenceEntity existing = preferenceMapper.selectByUser(preference.userId());
        String cipher = existing == null ? null : existing.getZentaoPassword();
        if (preference.zentaoPassword() != null) {
            cipher = preference.zentaoPassword().isBlank()
                    ? null : tokenCipher.encrypt(preference.zentaoPassword());
        }
        preferenceMapper.upsert(UserPreferenceEntity.builder()
                .teamId(teamScope.of(preference.userId()))
                .userId(preference.userId())
                .displayName(preference.displayName())
                .feishuOpenId(preference.feishuOpenId())
                .gitAuthors(join(preference.gitAuthors()))
                .gitRepositories(join(preference.gitRepositories()))
                .zentaoAccount(preference.zentaoAccount())
                .zentaoPassword(cipher)
                .zentaoLoginAllowed(preference.zentaoLoginAllowed())
                .morningPushEnabled(preference.morningPushEnabled())
                .morningPushTime(preference.morningPushTime())
                .eveningPushEnabled(preference.eveningPushEnabled())
                .eveningPushTime(preference.eveningPushTime())
                .alertPushEnabled(preference.alertPushEnabled())
                .quietStart(preference.quietStart())
                .quietEnd(preference.quietEnd())
                .pushChannel(preference.pushChannel())
                .reportStyle(preference.reportStyle())
                .attachCommitHash(preference.attachCommitHash())
                .planFocusLimit(preference.planFocusLimit())
                .weeklyEnabled(preference.weeklyEnabled())
                .weeklyWeekday(preference.weeklyWeekday())
                .weeklyTime(preference.weeklyTime())
                .weeklyScope(preference.weeklyScope())
                .watchInterns(join(preference.watchInterns()))
                .weeklyTrendEnabled(preference.weeklyTrendEnabled())
                .riskTypes(join(preference.riskTypes()))
                .riskMinLevel(preference.riskMinLevel())
                .learnCategories(join(preference.learnCategories()))
                .learnTags(join(preference.learnTags()))
                .learnAnswerStyle(preference.learnAnswerStyle())
                .updatedAt(LocalDateTime.now())
                .build());
        log.info("用户偏好已保存，用户 {}", preference.userId());
        return get(preference.userId());
    }

    /**
     * 个人配置引导：飞书标识、代码提交、内部任务系统三项是否已配好
     * 服务端兜底能覆盖的情况写在说明里，避免让人以为没配就完全不能采集
     */
    public OnboardingView onboarding(String userId) {
        UserPreference preference = get(userId);
        boolean feishu = preference.feishuOpenId() != null && !preference.feishuOpenId().isBlank();
        boolean git = !preference.gitRepositories().isEmpty() && !preference.gitAuthors().isEmpty();
        boolean zentao = preference.hasOwnZentaoAccount() && passwordSet(userId);

        List<OnboardingView.Item> items = new ArrayList<>();
        items.add(new OnboardingView.Item("profile", "飞书用户标识", feishu,
                "没填就收不到私发推送，日报与提醒只能发到群里",
                "填 ou_ 开头的标识，完成一次飞书授权后会自动带出"));
        items.add(new OnboardingView.Item("git", "代码提交", git,
                gitRepositoriesFallback()
                        ? "还没配作者标识，提交采不到你名下，日报里的产出证据会是空的"
                        : "没配仓库与作者标识，就采不到你的提交",
                "填提交作者标识并选好仓库；仓库可以留空，会按服务端兜底仓库采集"));
        items.add(new OnboardingView.Item("zentao", "内部任务系统", zentao,
                "没配个人账号时用服务账号采集，任务与工时可能对不到你本人",
                "填个人账号与密码；不填也能跑，但统计口径会偏"));

        int done = 0;
        for (OnboardingView.Item item : items) {
            if (item.configured()) {
                done++;
            }
        }
        return new OnboardingView(items, done, items.size());
    }

    private boolean gitRepositoriesFallback() {
        return !systemConfig.gitRepositories().isEmpty();
    }

    /**
     * 推送渠道用的接收人标识，优先取偏好里的，其次取用户目录
     */
    public String openId(String userId) {
        return get(userId).feishuOpenId();
    }

    /**
     * 采集提交时用的作者标识。本人没配就回退到配置文件里的值
     */
    public List<String> gitAuthors(String userId) {
        List<String> own = get(userId).gitAuthors();
        if (!own.isEmpty()) {
            return own;
        }
        return properties.getInterns().stream()
                .filter(intern -> userId.equals(intern.getUserId()))
                .flatMap(intern -> intern.getGitAuthors().stream())
                .filter(author -> author != null && !author.isBlank())
                .distinct()
                .toList();
    }

    /**
     * 采集用的本地仓库列表。本人没配就回退服务端的兜底列表
     */
    public List<String> gitRepositories(String userId) {
        List<String> own = get(userId).gitRepositories();
        if (!own.isEmpty()) {
            return own;
        }
        return systemConfig.gitRepositories();
    }

    /**
     * 内部任务系统的采集凭据。个人配置优先，没有则用服务账号
     */
    public DataSourceCredential zentaoCredential(String userId) {
        UserPreferenceEntity entity = preferenceMapper.selectByUser(userId);
        if (entity != null && entity.getZentaoAccount() != null && !entity.getZentaoAccount().isBlank()
                && entity.getZentaoPassword() != null && !entity.getZentaoPassword().isBlank()) {
            return new DataSourceCredential(entity.getZentaoAccount(),
                    tokenCipher.decrypt(entity.getZentaoPassword()),
                    yes(entity.getZentaoLoginAllowed()), "个人配置");
        }
        return new DataSourceCredential(systemConfig.zentaoAccount(), systemConfig.zentaoPassword(),
                systemConfig.zentaoAllowLogin(), "服务账号");
    }

    /**
     * 导师关注的实习生。未配置时按「我带的」理解，取该导师名下的人
     */
    public List<String> watchedInterns(UserPreference preference) {
        if (preference.watchAllInterns()) {
            return userDirectory.interns().stream().map(SysUser::userId).toList();
        }
        if (!preference.watchInterns().isEmpty()) {
            return preference.watchInterns();
        }
        return userDirectory.interns().stream()
                .filter(intern -> preference.userId().equals(intern.mentorUserId()))
                .map(SysUser::userId)
                .toList();
    }

    /**
     * 是否落在静默时段，跨天时按次日处理
     */
    public boolean inQuietHours(UserPreference preference, LocalTime now) {
        LocalTime start = parseTime(preference.quietStart());
        LocalTime end = parseTime(preference.quietEnd());
        if (start == null || end == null || start.equals(end)) {
            return false;
        }
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        return !now.isBefore(start) || now.isBefore(end);
    }

    /**
     * 定时任务按窗口扫描时判断是否到达该用户的推送时间
     */
    public boolean inPushWindow(String configuredTime, LocalTime now) {
        LocalTime target = parseTime(configuredTime);
        if (target == null) {
            return false;
        }
        return !now.isBefore(target) && now.isBefore(target.plusMinutes(PUSH_WINDOW_MINUTES));
    }

    public boolean alertLevelAccepted(UserPreference preference, String level) {
        return rank(level) >= rank(preference.riskMinLevel());
    }

    public boolean alertTypeAccepted(UserPreference preference, String type) {
        if (preference.riskTypes() == null || preference.riskTypes().isEmpty()) {
            return false;
        }
        return preference.riskTypes().contains(type);
    }

    private UserPreference defaults(String userId, SysUser user) {
        return new UserPreference(userId,
                user == null ? userId : user.name(),
                user == null ? null : user.feishuOpenId(),
                List.of(), List.of(), null, null, false,
                true, teamSettingService.current().defaultMorningTime(),
                true, teamSettingService.current().defaultEveningTime(),
                true, null, null, UserPreference.CHANNEL_USER,
                UserPreference.STYLE_DETAIL, true, 3,
                true, UserPreference.DEFAULT_WEEKLY_WEEKDAY, UserPreference.DEFAULT_WEEKLY_TIME,
                UserPreference.SCOPE_WATCHED, List.of(),
                true, ALL_RISK_TYPES, UserPreference.DEFAULT_RISK_MIN_LEVEL,
                List.of(), List.of(), UserPreference.DEFAULT_ANSWER_STYLE);
    }

    private int rank(String level) {
        if (level == null) {
            return 0;
        }
        return switch (level.trim().toUpperCase(Locale.ROOT)) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private boolean yes(Boolean value) {
        return value != null && value;
    }

    private int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String join(List<String> values) {
        return values == null ? "" : String.join(",", values);
    }

    private List<String> splitList(String value) {
        List<String> items = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return items;
        }
        for (String item : value.split("[,，、;；\\s]+")) {
            if (!item.isBlank()) {
                items.add(item.trim());
            }
        }
        return items;
    }

    private List<String> fallbackList(String value, List<String> fallback) {
        List<String> items = splitList(value);
        return items.isEmpty() ? fallback : items;
    }
}
