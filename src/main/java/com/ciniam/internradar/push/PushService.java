package com.ciniam.internradar.push;

import com.ciniam.internradar.config.InternRadarProperties;
import com.ciniam.internradar.entity.PushLogEntity;
import com.ciniam.internradar.mapper.PushLogMapper;
import com.ciniam.internradar.model.SysUser;
import com.ciniam.internradar.model.UserPreference;
import com.ciniam.internradar.service.PreferenceService;
import com.ciniam.internradar.service.SystemConfigService;
import com.ciniam.internradar.service.TeamScope;
import com.ciniam.internradar.service.UserDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 推送编排：按接收人的偏好判断要不要发、怎么发，再执行推送并记录日志
 * 偏好里的开关、静默时段与渠道都在这里统一生效，避免各处重复判断
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    private static final String TYPE_PLAN = "PLAN";
    private static final String TYPE_DAILY = "DAILY";
    private static final String TYPE_RISK = "RISK_ALERT";

    private final List<GroupPusher> groupPushers;
    private final List<UserPusher> userPushers;
    private final PushLogMapper pushLogMapper;
    private final InternRadarProperties properties;
    private final PreferenceService preferenceService;
    private final UserDirectory userDirectory;

    private final TeamScope teamScope;
    private final SystemConfigService systemConfig;

    public PushService(List<GroupPusher> groupPushers,
                       List<UserPusher> userPushers,
                       PushLogMapper pushLogMapper,
                       InternRadarProperties properties,
                       PreferenceService preferenceService,
                       UserDirectory userDirectory,
                       TeamScope teamScope,
                       SystemConfigService systemConfig) {
        this.groupPushers = groupPushers;
        this.userPushers = userPushers;
        this.pushLogMapper = pushLogMapper;
        this.properties = properties;
        this.preferenceService = preferenceService;
        this.userDirectory = userDirectory;
        this.teamScope = teamScope;
        this.systemConfig = systemConfig;
    }

    /**
     * 飞书整块是可选能力：应用凭据或群机器人没配时不推送，只保留站内查看
     */
    private boolean appReady() {
        return notBlank(systemConfig.feishuAppId()) && notBlank(systemConfig.feishuAppSecret());
    }

    private boolean webhookReady() {
        return notBlank(systemConfig.feishuWebhookUrl());
    }

    private boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }

    public PushResult sendToGroup(String userId, String type, String content) {
        if (!webhookReady()) {
            log.debug("未配置群机器人，跳过群推送，用户 {}", userId);
            return PushResult.fail("未配置群机器人，已跳过推送");
        }
        if (groupPushers.isEmpty()) {
            return PushResult.fail("没有可用的群推送渠道");
        }
        GroupPusher pusher = groupPushers.get(0);
        PushResult result = pusher.push(content);
        record(userId, pusher.getChannel(), type, content, result);
        return result;
    }

    /**
     * 私发给某个人。先按偏好判断是否该发，再按渠道发私聊或群或两者
     */
    public PushResult sendToUser(String userId, String type, String content) {
        UserPreference preference = preferenceService.get(userId);
        String skipReason = skipReason(preference, type);
        if (skipReason != null) {
            log.info("按偏好跳过推送，用户 {}，类型 {}，原因 {}", userId, type, skipReason);
            return PushResult.fail(skipReason);
        }
        PushResult last = null;
        if (preference.pushToUser()) {
            last = sendToOpenId(userId, preference.feishuOpenId(), type, content);
        }
        if (preference.pushToGroup()) {
            last = sendToGroup(userId, type, content);
        }
        return last == null ? PushResult.fail("推送渠道配置为空") : last;
    }

    public PushResult sendToMentors(String type, String content) {
        if (!appReady()) {
            log.debug("未配置飞书应用，跳过导师推送");
            return PushResult.fail("未配置飞书应用，已跳过推送");
        }
        List<SysUser> mentors = userDirectory.mentors();
        if (mentors.isEmpty()) {
            return sendToConfiguredMentors(type, content);
        }
        PushResult last = PushResult.fail("没有可用导师接收人");
        for (SysUser mentor : mentors) {
            String openId = preferenceService.openId(mentor.userId());
            last = sendToOpenId(mentor.userId(), openId, type, content);
        }
        return last;
    }

    /**
     * 用户表没数据时回退到配置里的导师，保证首次启动也能发出去
     */
    private PushResult sendToConfiguredMentors(String type, String content) {
        if (properties.getMentors().isEmpty()) {
            return PushResult.fail("未配置导师，无法发送周报");
        }
        PushResult last = PushResult.fail("未配置导师，无法发送周报");
        for (InternRadarProperties.Mentor mentor : properties.getMentors()) {
            last = sendToOpenId(mentor.getUserId(), mentor.getFeishuOpenId(), type, content);
        }
        return last;
    }

    public PushResult sendToOpenId(String userId, String openId, String type, String content) {
        if (!appReady()) {
            log.debug("未配置飞书应用，跳过私发推送，用户 {}", userId);
            return PushResult.fail("未配置飞书应用，已跳过推送");
        }
        if (userPushers.isEmpty()) {
            PushResult result = PushResult.fail("没有可用的个人推送渠道");
            record(userId, "none", type, content, result);
            return result;
        }
        if (openId == null || openId.isBlank()) {
            PushResult result = PushResult.fail("接收人没有配置飞书标识，无法私发");
            record(userId, "none", type, content, result);
            return result;
        }
        UserPusher pusher = userPushers.get(0);
        PushResult result = pusher.push(openId, content);
        record(userId, pusher.getChannel(), type, content, result);
        return result;
    }

    /**
     * 偏好的拦截条件，返回 null 表示可以发
     */
    private String skipReason(UserPreference preference, String type) {
        if (TYPE_PLAN.equals(type) && !preference.morningPushEnabled()) {
            return "已关闭早推送";
        }
        if (TYPE_DAILY.equals(type) && !preference.eveningPushEnabled()) {
            return "已关闭晚推送";
        }
        if (TYPE_RISK.equals(type) && !preference.alertPushEnabled()) {
            return "已关闭风险告警推送";
        }
        if (preferenceService.inQuietHours(preference, LocalTime.now())) {
            return "处于静默时段";
        }
        return null;
    }

    private void record(String userId, String channel, String type, String content, PushResult result) {
        if (result.success()) {
            log.info("推送成功，渠道 {}，用户 {}，类型 {}", channel, userId, type);
        } else {
            log.warn("推送失败，渠道 {}，用户 {}，类型 {}，原因 {}", channel, userId, type, result.errorMessage());
        }
        try {
            pushLogMapper.insert(PushLogEntity.builder()
                    .teamId(teamScope.of(userId))
                    .userId(userId)
                    .channel(channel)
                    .type(type)
                    .content(content)
                    .status(result.success() ? "SUCCESS" : "FAILED")
                    .errorMsg(result.errorMessage())
                    .sentAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.error("推送日志写入失败，用户 {}", userId, e);
        }
    }
}
