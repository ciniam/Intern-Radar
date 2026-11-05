package com.ciniam.internradar.config;

import com.ciniam.internradar.entity.SysUserEntity;
import com.ciniam.internradar.mapper.SysUserMapper;
import com.ciniam.internradar.model.UserRole;
import com.ciniam.internradar.model.UserStatus;
import com.ciniam.internradar.security.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用配置清单初始化系统用户。只新增与补全资料，不覆盖库里的角色与启用状态
 * 同一个飞书标识挂在多个用户上会让飞书登录无法区分角色，启动时直接告警
 */
@Component
@Order(10)
public class UserBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserBootstrap.class);

    private static final long DEFAULT_TEAM_ID = 1L;

    private final SysUserMapper sysUserMapper;
    private final InternRadarProperties properties;

    public UserBootstrap(SysUserMapper sysUserMapper, InternRadarProperties properties) {
        this.sysUserMapper = sysUserMapper;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            for (InternRadarProperties.Intern intern : properties.getInterns()) {
                upsert(intern.getUserId(), intern.getName(), UserRole.INTERN,
                        intern.getMentorUserId(), intern.getFeishuOpenId());
                applyCredential(intern.getUserId(), intern.getEmail(), intern.getPassword());
            }
            for (InternRadarProperties.Mentor mentor : properties.getMentors()) {
                upsert(mentor.getUserId(), mentor.getName(), UserRole.MENTOR, null, mentor.getFeishuOpenId());
                applyCredential(mentor.getUserId(), mentor.getEmail(), mentor.getPassword());
            }
            warnDuplicatedOpenIds();
        } catch (Exception e) {
            log.warn("系统用户初始化失败，可稍后手工维护 sys_user 表：{}", e.getMessage());
        }
    }

    /**
     * 首次启动时写入邮箱与初始密码，之后以库里的哈希为准
     * 初始密码写入后标记为必须改密，避免配置里的明文密码长期有效
     */
    private void applyCredential(String userId, String email, String password) {
        SysUserEntity entity = sysUserMapper.selectByUserId(userId);
        if (entity == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String targetEmail = entity.getEmail() == null || entity.getEmail().isBlank()
                ? normalizeEmail(email) : entity.getEmail();
        boolean hasPassword = entity.getPasswordHash() != null && !entity.getPasswordHash().isBlank();
        if (hasPassword) {
            if (entity.getEmail() == null || entity.getEmail().isBlank()) {
                sysUserMapper.updateCredential(userId, targetEmail, entity.getPasswordHash(), false, now);
            }
            return;
        }
        if (password == null || password.isBlank()) {
            log.warn("账号 {} 还没有密码，配置里也没给初始密码，暂时无法密码登录", userId);
            return;
        }
        sysUserMapper.updateCredential(userId, targetEmail, PasswordHasher.hash(password), true, now);
        log.warn("账号 {} 已写入初始密码并标记为需改密，请登录后立即修改", userId);
    }

    private String normalizeEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void upsert(String userId, String name, UserRole role, String mentorUserId, String openId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        // 配置文件里写明的账号视为已审批，只有飞书首次登录自动建号才需要导师审批
        sysUserMapper.insertIgnore(SysUserEntity.builder()
                .teamId(DEFAULT_TEAM_ID)
                .userId(userId)
                .name(name)
                .role(role.name())
                .mentorUserId(mentorUserId)
                .feishuOpenId(openId)
                .status(UserStatus.ACTIVE.name())
                .enabled(true)
                .updatedAt(now)
                .build());
        sysUserMapper.updateProfile(userId, name, mentorUserId, openId, now);
    }

    private void warnDuplicatedOpenIds() {
        Map<String, String> seen = new LinkedHashMap<>();
        for (SysUserEntity entity : sysUserMapper.selectEnabled()) {
            String openId = entity.getFeishuOpenId();
            if (openId == null || openId.isBlank()) {
                continue;
            }
            String owner = seen.putIfAbsent(openId, entity.getUserId());
            if (owner != null) {
                log.warn("飞书标识 {} 同时属于 {} 与 {}，飞书登录无法区分角色，请为其一配置真实标识",
                        openId, owner, entity.getUserId());
            }
        }
    }
}
