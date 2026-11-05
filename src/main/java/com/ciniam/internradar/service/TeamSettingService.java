package com.ciniam.internradar.service;

import com.ciniam.internradar.config.InternRadarProperties;
import com.ciniam.internradar.entity.TeamSettingEntity;
import com.ciniam.internradar.mapper.TeamSettingMapper;
import com.ciniam.internradar.model.TeamSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 团队级设置读写。配置文件里的值是默认值，库里存了就以库为准
 * 读多写少且会被循环调用，因此按团队各做一层几秒的短缓存，保存时立刻失效
 */
@Service
public class TeamSettingService {

    private static final Logger log = LoggerFactory.getLogger(TeamSettingService.class);

    private static final long CACHE_MILLIS = 5000L;
    private static final String DEFAULT_MORNING_TIME = "09:00";
    private static final String DEFAULT_EVENING_TIME = "18:30";

    public static final String KEY_NO_OUTPUT_DAYS = "risk.noOutputDays";
    public static final String KEY_TASK_STALL_DAYS = "risk.taskStallDays";
    public static final String KEY_DEADLINE_DAYS = "risk.deadlineDays";
    public static final String KEY_DEADLINE_PROGRESS = "risk.deadlineProgress";
    public static final String KEY_ALERT_THROTTLE_HOURS = "risk.alertThrottleHours";
    public static final String KEY_DEFAULT_MORNING_TIME = "push.defaultMorningTime";
    public static final String KEY_DEFAULT_EVENING_TIME = "push.defaultEveningTime";
    public static final String KEY_KNOWLEDGE_ROOT_DIR = "knowledge.rootDir";
    public static final String KEY_KNOWLEDGE_MAX_READ_CHARS = "knowledge.maxReadChars";
    public static final String KEY_KNOWLEDGE_SEARCH_TOP_K = "knowledge.searchTopK";

    private final TeamSettingMapper teamSettingMapper;
    private final InternRadarProperties properties;
    private final TeamScope teamScope;

    private final Map<Long, TeamSettings> cache = new ConcurrentHashMap<>();
    private final Map<Long, Long> cachedAt = new ConcurrentHashMap<>();

    public TeamSettingService(TeamSettingMapper teamSettingMapper,
                              InternRadarProperties properties,
                              TeamScope teamScope) {
        this.teamSettingMapper = teamSettingMapper;
        this.properties = properties;
        this.teamScope = teamScope;
    }

    /**
     * 当前请求或当前任务所属团队的设置
     */
    public TeamSettings current() {
        return current(teamScope.current());
    }

    public TeamSettings current(long teamId) {
        long key = teamId <= 0 ? TeamScope.FALLBACK_TEAM_ID : teamId;
        TeamSettings local = cache.get(key);
        Long at = cachedAt.get(key);
        if (local != null && at != null && System.currentTimeMillis() - at < CACHE_MILLIS) {
            return local;
        }
        TeamSettings loaded = load(key);
        cache.put(key, loaded);
        cachedAt.put(key, System.currentTimeMillis());
        return loaded;
    }

    public TeamSettings save(TeamSettings settings) {
        long teamId = teamScope.current();
        TeamSettings base = current(teamId);
        upsert(teamId, KEY_NO_OUTPUT_DAYS, String.valueOf(settings.noOutputDays()));
        upsert(teamId, KEY_TASK_STALL_DAYS, String.valueOf(settings.taskStallDays()));
        upsert(teamId, KEY_DEADLINE_DAYS, String.valueOf(settings.deadlineDays()));
        upsert(teamId, KEY_DEADLINE_PROGRESS, String.valueOf(settings.deadlineProgress()));
        upsert(teamId, KEY_ALERT_THROTTLE_HOURS, String.valueOf(settings.alertThrottleHours()));
        upsert(teamId, KEY_DEFAULT_MORNING_TIME, settings.defaultMorningTime());
        upsert(teamId, KEY_DEFAULT_EVENING_TIME, settings.defaultEveningTime());
        upsert(teamId, KEY_KNOWLEDGE_ROOT_DIR, settings.knowledgeRootDir());
        upsert(teamId, KEY_KNOWLEDGE_MAX_READ_CHARS, String.valueOf(settings.knowledgeMaxReadChars()));
        upsert(teamId, KEY_KNOWLEDGE_SEARCH_TOP_K, String.valueOf(settings.knowledgeSearchTopK()));
        log.info("团队设置已保存，团队 {}，知识目录由 {} 改为 {}",
                teamId, base.knowledgeRootDir(), settings.knowledgeRootDir());
        cache.remove(teamId);
        cachedAt.remove(teamId);
        return current(teamId);
    }

    /**
     * 默认知识目录按团队分开，团队 1 沿用原有目录，新团队各自一个子目录
     */
    public String defaultKnowledgeRootDir(long teamId) {
        String base = properties.getKnowledge().getRootDir();
        return teamId <= TeamScope.FALLBACK_TEAM_ID ? base : base + "/team-" + teamId;
    }

    private TeamSettings load(long teamId) {
        InternRadarProperties.Risk risk = properties.getRisk();
        InternRadarProperties.Knowledge knowledge = properties.getKnowledge();
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (TeamSettingEntity entity : teamSettingMapper.selectByTeam(teamId)) {
                values.put(entity.getSettingKey(), entity.getSettingValue());
            }
        } catch (Exception e) {
            log.warn("团队设置读取失败，团队 {}，改用配置文件默认值：{}", teamId, e.getMessage());
        }
        return new TeamSettings(
                intValue(values.get(KEY_NO_OUTPUT_DAYS), risk.getNoOutputDays()),
                intValue(values.get(KEY_TASK_STALL_DAYS), risk.getTaskStallDays()),
                intValue(values.get(KEY_DEADLINE_DAYS), risk.getDeadlineDays()),
                intValue(values.get(KEY_DEADLINE_PROGRESS), risk.getDeadlineProgress()),
                intValue(values.get(KEY_ALERT_THROTTLE_HOURS), risk.getAlertThrottleHours()),
                textValue(values.get(KEY_DEFAULT_MORNING_TIME), DEFAULT_MORNING_TIME),
                textValue(values.get(KEY_DEFAULT_EVENING_TIME), DEFAULT_EVENING_TIME),
                textValue(values.get(KEY_KNOWLEDGE_ROOT_DIR), defaultKnowledgeRootDir(teamId)),
                intValue(values.get(KEY_KNOWLEDGE_MAX_READ_CHARS), knowledge.getMaxReadChars()),
                intValue(values.get(KEY_KNOWLEDGE_SEARCH_TOP_K), knowledge.getSearchTopK()));
    }

    private void upsert(long teamId, String key, String value) {
        teamSettingMapper.upsert(TeamSettingEntity.builder()
                .teamId(teamId)
                .settingKey(key)
                .settingValue(value)
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private int intValue(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String textValue(String raw, String fallback) {
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }
}
