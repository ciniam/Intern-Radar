package com.ciniam.internradar.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ciniam.internradar.config.InternRadarProperties;
import com.ciniam.internradar.entity.SysUserEntity;
import com.ciniam.internradar.entity.UserSessionEntity;
import com.ciniam.internradar.feishu.FeishuUserAuthService;
import com.ciniam.internradar.mapper.SysUserMapper;
import com.ciniam.internradar.mapper.UserSessionMapper;
import com.ciniam.internradar.model.LoginDevice;
import com.ciniam.internradar.model.LoginResult;
import com.ciniam.internradar.model.SysUser;
import com.ciniam.internradar.model.UserDevice;
import com.ciniam.internradar.model.UserRole;
import com.ciniam.internradar.model.UserStatus;
import com.ciniam.internradar.security.ForbiddenException;
import com.ciniam.internradar.security.PasswordHasher;
import com.ciniam.internradar.security.PasswordPolicy;
import com.ciniam.internradar.security.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 认证与会话。正式入口是邮箱加密码，身份直选仅用于本地调试
 * 安全约束：统一失败文案、连续失败锁定、口令 PBKDF2 存储、改密后其它设备下线
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_PENDING = "PENDING";
    private static final String TOKEN_HEADER_KEY = "token";
    private static final String LOGIN_FAILED = "邮箱或密码不正确";
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final String TEMP_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SysUserMapper sysUserMapper;
    private final UserSessionMapper userSessionMapper;
    private final FeishuUserAuthService feishuUserAuthService;
    private final SystemConfigService systemConfigService;
    private final EmailCodeService emailCodeService;
    private final TeamService teamService;
    private final InternRadarProperties properties;
    private final ObjectMapper objectMapper;

    public AuthService(SysUserMapper sysUserMapper,
                       UserSessionMapper userSessionMapper,
                       FeishuUserAuthService feishuUserAuthService,
                       SystemConfigService systemConfigService,
                       EmailCodeService emailCodeService,
                       TeamService teamService,
                       InternRadarProperties properties,
                       ObjectMapper objectMapper) {
        this.sysUserMapper = sysUserMapper;
        this.userSessionMapper = userSessionMapper;
        this.feishuUserAuthService = feishuUserAuthService;
        this.systemConfigService = systemConfigService;
        this.emailCodeService = emailCodeService;
        this.teamService = teamService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 邮箱注册。注册后未归属团队，需要自己创建或凭邀请码加入
     */
    public LoginResult register(String email, String password, String name, LoginDevice device) {
        String normalized = normalizeEmail(email);
        if (normalized == null) {
            throw new IllegalArgumentException("请填写正确的邮箱");
        }
        if (sysUserMapper.countByEmail(normalized) > 0) {
            throw new IllegalArgumentException("这个邮箱已经注册过了，可直接登录");
        }
        PasswordPolicy.validate(password, minPasswordLength(), normalized);
        String displayName = name == null || name.isBlank() ? normalized.split("@")[0] : name.trim();
        if (displayName.length() > 32) {
            throw new IllegalArgumentException("昵称请控制在 32 个字符以内");
        }
        LocalDateTime now = LocalDateTime.now();
        String userId = uniqueUserId(normalized);
        sysUserMapper.insertRegister(SysUserEntity.builder()
                .userId(userId)
                .name(displayName)
                .email(normalized)
                .passwordHash(PasswordHasher.hash(password))
                .passwordUpdatedAt(now)
                .updatedAt(now)
                .build());
        SysUserEntity created = sysUserMapper.selectByEmail(normalized);
        if (created == null) {
            throw new IllegalStateException("注册失败，请稍后重试");
        }
        log.info("新账号已注册，用户 {}，邮箱 {}", userId, normalized);
        return createSession(toUser(created), device);
    }

    /**
     * 邮箱密码登录。账号不存在与密码错误返回同一句提示，避免被用来枚举账号
     */
    public LoginResult login(String email, String password, LoginDevice device) {
        String normalized = normalizeEmail(email);
        if (normalized == null || password == null || password.isEmpty()) {
            throw new UnauthorizedException(LOGIN_FAILED);
        }
        SysUserEntity entity = sysUserMapper.selectByEmail(normalized);
        if (entity == null || !Boolean.TRUE.equals(entity.getEnabled())) {
            throw new UnauthorizedException(LOGIN_FAILED);
        }
        LocalDateTime now = LocalDateTime.now();
        if (entity.getLockedUntil() != null && entity.getLockedUntil().isAfter(now)) {
            throw new UnauthorizedException("登录失败次数过多，请 "
                    + properties.getAuth().getLockMinutes() + " 分钟后再试");
        }
        if (entity.getPasswordHash() == null || entity.getPasswordHash().isBlank()) {
            throw new UnauthorizedException("该账号还没有设置密码，请联系管理员重置");
        }
        if (!PasswordHasher.matches(password, entity.getPasswordHash())) {
            recordFailure(entity, now);
            throw new UnauthorizedException(LOGIN_FAILED);
        }
        if (UserStatus.parse(entity.getStatus()).pending() || entity.getTeamId() == null
                || entity.getTeamId() <= 0) {
            // 未入团与待审批都允许登录，进来后被引导到团队页
            log.info("用户 {} 尚未归属团队，登录后引导加入团队", entity.getUserId());
        }
        sysUserMapper.updateLoginSuccess(entity.getUserId(), now);
        SysUserEntity latest = sysUserMapper.selectByUserId(entity.getUserId());
        LoginResult result = createSession(toUser(latest), device);
        log.info("密码登录成功，用户 {}，设备 {}", entity.getUserId(), device.device());
        return result;
    }

    /**
     * 自助改密。校验旧密码，改完把其它设备踢下线
     */
    public void changePassword(String userId, String oldPassword, String newPassword, String currentToken) {
        SysUserEntity entity = sysUserMapper.selectByUserId(userId);
        if (entity == null) {
            throw new UnauthorizedException("账号不存在");
        }
        if (entity.getPasswordHash() == null || entity.getPasswordHash().isBlank()) {
            throw new IllegalStateException("该账号还没有设置密码，请联系管理员重置");
        }
        if (!PasswordHasher.matches(oldPassword, entity.getPasswordHash())) {
            throw new IllegalArgumentException("原密码不正确");
        }
        if (PasswordHasher.matches(newPassword, entity.getPasswordHash())) {
            throw new IllegalArgumentException("新密码不能与原密码相同");
        }
        PasswordPolicy.validate(newPassword, minPasswordLength(), entity.getEmail());
        sysUserMapper.updatePassword(userId, PasswordHasher.hash(newPassword), false, LocalDateTime.now());
        if (currentToken != null && !currentToken.isBlank()) {
            userSessionMapper.deleteOthers(userId, currentToken);
        } else {
            userSessionMapper.deleteByUser(userId);
        }
        log.info("用户 {} 已修改密码，其它设备会话已失效", userId);
    }

    /**
     * 发送重置验证码。账号不存在时同样返回成功，避免被用来探测邮箱
     */
    public void sendResetCode(String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null) {
            throw new IllegalArgumentException("请填写正确的邮箱");
        }
        if (!emailCodeService.mailReady()) {
            throw new IllegalArgumentException("还没有配置发信邮箱，暂时无法自助重置，请联系团队创建人");
        }
        SysUserEntity entity = sysUserMapper.selectByEmail(normalized);
        if (entity == null || !Boolean.TRUE.equals(entity.getEnabled())) {
            log.info("请求重置密码的邮箱不存在，已忽略：{}", normalized);
            return;
        }
        emailCodeService.sendResetCode(normalized);
    }

    /**
     * 凭验证码重置密码，重置后全部会话失效
     */
    public void resetPassword(String email, String code, String newPassword) {
        String normalized = normalizeEmail(email);
        if (normalized == null) {
            throw new IllegalArgumentException("请填写正确的邮箱");
        }
        SysUserEntity entity = sysUserMapper.selectByEmail(normalized);
        if (entity == null) {
            throw new IllegalArgumentException("验证码不正确");
        }
        emailCodeService.verifyResetCode(normalized, code);
        PasswordPolicy.validate(newPassword, minPasswordLength(), normalized);
        sysUserMapper.updatePassword(entity.getUserId(), PasswordHasher.hash(newPassword), false,
                LocalDateTime.now());
        userSessionMapper.deleteByUser(entity.getUserId());
        emailCodeService.invalidate(normalized);
        log.info("用户 {} 已通过邮箱验证码重置密码", entity.getUserId());
    }

    /**
     * 管理员重置成员密码。生成一次性临时密码，用户首次登录必须改密
     */
    public String resetByAdmin(String operatorId, String targetUserId) {
        SysUser operator = toUser(requireEntity(operatorId));
        if (!teamService.canManage(operator)) {
            throw new ForbiddenException("只有团队创建人或管理员可以重置成员密码");
        }
        SysUserEntity target = requireEntity(targetUserId);
        long operatorTeam = operator.teamId() == null ? TeamScope.NO_TEAM : operator.teamId();
        if (target.getTeamId() == null || target.getTeamId() != operatorTeam) {
            throw new ForbiddenException("只能重置本团队成员的密码");
        }
        if (operatorId.equals(targetUserId)) {
            throw new IllegalArgumentException("请用「修改密码」修改自己的密码");
        }
        String temp = tempPassword();
        sysUserMapper.updatePassword(targetUserId, PasswordHasher.hash(temp), true, LocalDateTime.now());
        userSessionMapper.deleteByUser(targetUserId);
        log.info("管理员 {} 重置了用户 {} 的密码", operatorId, targetUserId);
        return temp;
    }

    /**
     * 登录页的可选身份。关闭身份直选后返回空列表
     */
    public List<SysUser> loginCandidates() {
        List<SysUser> candidates = new ArrayList<>();
        if (!properties.getAuth().isDevLoginEnabled()) {
            return candidates;
        }
        for (SysUserEntity entity : sysUserMapper.selectEnabled()) {
            candidates.add(toUser(entity));
        }
        return candidates;
    }

    public boolean devLoginEnabled() {
        return properties.getAuth().isDevLoginEnabled();
    }

    /**
     * 本地调试用：直接按用户标识登录
     */
    public LoginResult loginAs(String userId, LoginDevice device) {
        if (!properties.getAuth().isDevLoginEnabled()) {
            throw new UnauthorizedException("身份直选登录已关闭，请使用邮箱登录");
        }
        SysUserEntity entity = sysUserMapper.selectByUserId(userId);
        if (entity == null || !Boolean.TRUE.equals(entity.getEnabled())) {
            throw new UnauthorizedException("用户不存在或已停用：" + userId);
        }
        return createSession(toUser(entity), device);
    }

    /**
     * 飞书授权地址。用于绑定飞书账号获取用户标识，不再承担登录入口
     */
    public String feishuLoginUrl(LoginDevice device) {
        String state = newToken();
        userSessionMapper.insert(UserSessionEntity.builder()
                .teamId(TeamScope.NO_TEAM)
                .token(state)
                .status(STATUS_PENDING)
                .device(device.device())
                .ip(device.ip())
                .expireAt(LocalDateTime.now().plusMinutes(properties.getAuth().getPendingMinutes()))
                .build());
        return feishuUserAuthService.authorizeUrl(state, systemConfigService.feishuLoginRedirectUri());
    }

    /**
     * 飞书回调：按 open_id 反查系统用户，命中则升级会话；查不到自动建号（无密码，需管理员重置）
     */
    public String handleFeishuCallback(String code, String state) {
        if (code == null || code.isBlank()) {
            return resultPage(null, "授权失败：回调未携带 code");
        }
        UserSessionEntity pending = state == null ? null : userSessionMapper.selectByToken(state);
        if (pending == null || !STATUS_PENDING.equals(pending.getStatus())) {
            return resultPage(null, "授权失败：登录请求已失效，请回到登录页重试");
        }
        FeishuUserAuthService.FeishuIdentity identity = feishuUserAuthService.authorize(code);
        if (identity.openId() == null || identity.openId().isBlank()) {
            return resultPage(null, "授权失败：未取到飞书用户标识");
        }
        SysUserEntity entity = sysUserMapper.selectByOpenId(identity.openId());
        if (entity == null) {
            entity = registerByFeishu(identity);
        }
        SysUser user = toUser(entity);
        LocalDateTime expireAt = LocalDateTime.now().plusHours(properties.getAuth().getSessionHours());
        userSessionMapper.deleteSameDevice(user.userId(), pending.getDevice(), pending.getIp());
        userSessionMapper.activate(state, user.userId(), user.role().name(), expireAt);
        try {
            feishuUserAuthService.bindTokens(user.userId(), identity);
        } catch (Exception e) {
            log.warn("飞书令牌落库失败，不影响登录：{}", e.getMessage());
        }
        log.info("飞书授权登录，用户 {}，角色 {}", user.userId(), user.role());
        return resultPage(new LoginResult(state, user.userId(), user.name(), user.role(), expireAt,
                UserStatus.parse(entity.getStatus()).pending()), null);
    }

    private SysUserEntity registerByFeishu(FeishuUserAuthService.FeishuIdentity identity) {
        String name = identity.name() == null || identity.name().isBlank() ? "飞书用户" : identity.name().trim();
        sysUserMapper.insertPending(SysUserEntity.builder()
                .teamId(TeamScope.NO_TEAM)
                .userId(identity.openId())
                .name(name)
                .feishuOpenId(identity.openId())
                .updatedAt(LocalDateTime.now())
                .build());
        SysUserEntity created = sysUserMapper.selectByUserId(identity.openId());
        if (created == null) {
            throw new IllegalStateException("自动建号失败：" + identity.openId());
        }
        log.info("飞书首次登录已自动建号，用户 {}，尚无密码，需管理员重置后才能密码登录", created.getUserId());
        return created;
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            userSessionMapper.deleteByToken(token);
        }
    }

    public List<UserDevice> devices(String userId, String currentToken) {
        List<UserDevice> devices = new ArrayList<>();
        for (UserSessionEntity entity : userSessionMapper.selectActiveByUser(userId, LocalDateTime.now())) {
            devices.add(new UserDevice(entity.getId(), entity.getDevice(), entity.getIp(),
                    entity.getCreatedAt(), entity.getExpireAt(),
                    currentToken != null && currentToken.equals(entity.getToken())));
        }
        return devices;
    }

    public void logoutDevice(String userId, Long sessionId, String currentToken) {
        List<UserDevice> devices = devices(userId, currentToken);
        for (UserDevice device : devices) {
            if (device.id().equals(sessionId)) {
                if (device.current()) {
                    throw new ForbiddenException("当前设备请直接使用退出登录");
                }
                userSessionMapper.deleteById(sessionId);
                return;
            }
        }
        throw new UnauthorizedException("该登录设备不存在或已失效");
    }

    public int logoutAll(String userId) {
        return userSessionMapper.deleteByUser(userId);
    }

    /**
     * 个人资料自助编辑。邮箱是登录名，不允许在这里改动
     */
    public SysUser updateProfile(String userId, String name, String email) {
        String resolvedName = name == null ? null : name.trim();
        if (resolvedName != null && resolvedName.isEmpty()) {
            throw new IllegalArgumentException("姓名不能为空");
        }
        if (resolvedName != null && resolvedName.length() > 32) {
            throw new IllegalArgumentException("姓名请控制在 32 个字符以内");
        }
        sysUserMapper.updateOwnProfile(userId, resolvedName, null, LocalDateTime.now());
        return toUser(requireEntity(userId));
    }

    public boolean passwordResetRequired(String userId) {
        SysUserEntity entity = sysUserMapper.selectByUserId(userId);
        return entity != null && Boolean.TRUE.equals(entity.getPasswordResetRequired());
    }

    private void recordFailure(SysUserEntity entity, LocalDateTime now) {
        int failed = (entity.getFailedAttempts() == null ? 0 : entity.getFailedAttempts()) + 1;
        int max = Math.max(properties.getAuth().getMaxFailedAttempts(), 3);
        LocalDateTime lockedUntil = failed >= max ? now.plusMinutes(properties.getAuth().getLockMinutes()) : null;
        sysUserMapper.updateLoginFailure(entity.getUserId(), failed, lockedUntil, now);
        if (lockedUntil != null) {
            log.warn("账号 {} 连续登录失败 {} 次，已锁定至 {}", entity.getUserId(), failed, lockedUntil);
        }
    }

    private SysUserEntity requireEntity(String userId) {
        SysUserEntity entity = userId == null ? null : sysUserMapper.selectByUserId(userId);
        if (entity == null) {
            throw new UnauthorizedException("账号不存在");
        }
        return entity;
    }

    private int minPasswordLength() {
        return Math.max(properties.getAuth().getPasswordMinLength(), 8);
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String trimmed = email.trim().toLowerCase(Locale.ROOT);
        return EMAIL.matcher(trimmed).matches() && trimmed.length() <= 128 ? trimmed : null;
    }

    /**
     * 用户标识取邮箱前缀，冲突时补数字，保证可读且唯一
     */
    private String uniqueUserId(String email) {
        String base = email.split("@")[0].replaceAll("[^A-Za-z0-9_]", "");
        if (base.isEmpty()) {
            base = "user";
        }
        if (base.length() > 48) {
            base = base.substring(0, 48);
        }
        String candidate = base;
        int suffix = 1;
        while (sysUserMapper.selectByUserId(candidate) != null) {
            candidate = base + suffix;
            suffix++;
        }
        return candidate;
    }

    private String tempPassword() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            builder.append(TEMP_CHARS.charAt(RANDOM.nextInt(TEMP_CHARS.length())));
        }
        return builder.append('A').append('b').append('7').toString();
    }

    private LoginResult createSession(SysUser user, LoginDevice device) {
        userSessionMapper.deleteExpired(LocalDateTime.now());
        userSessionMapper.deleteSameDevice(user.userId(), device.device(), device.ip());
        String token = newToken();
        LocalDateTime expireAt = LocalDateTime.now().plusHours(properties.getAuth().getSessionHours());
        userSessionMapper.insert(UserSessionEntity.builder()
                .teamId(user.teamId() == null ? TeamScope.NO_TEAM : user.teamId())
                .token(token)
                .userId(user.userId())
                .role(user.role().name())
                .status(STATUS_ACTIVE)
                .device(device.device())
                .ip(device.ip())
                .expireAt(expireAt)
                .build());
        SysUserEntity entity = sysUserMapper.selectByUserId(user.userId());
        boolean resetRequired = entity != null && Boolean.TRUE.equals(entity.getPasswordResetRequired());
        log.info("登录成功，用户 {}，角色 {}，设备 {}", user.userId(), user.role(), device.device());
        return new LoginResult(token, user.userId(), user.name(), user.role(), expireAt, resetRequired);
    }

    private String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String resultPage(LoginResult result, String error) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "intern-radar-login");
        if (result != null) {
            payload.put(TOKEN_HEADER_KEY, result.token());
            payload.put("userId", result.userId());
            payload.put("name", result.name() == null ? "" : result.name());
            payload.put("role", result.role() == null ? "" : result.role().name());
        }
        if (error != null) {
            payload.put("error", error);
        }
        return """
                <!DOCTYPE html>
                <html lang="zh-CN"><head><meta charset="UTF-8"><title>飞书授权</title></head>
                <body style="font-family:sans-serif;padding:40px;color:#2c3e50">
                <h3>%s</h3>
                <p style="color:#8a99ad">本窗口会自动关闭，请回到看板继续操作。</p>
                <script>
                  var payload = %s;
                  if (window.opener) { window.opener.postMessage(payload, '*'); window.close(); }
                </script>
                </body></html>
                """.formatted(result != null ? "授权成功" : "授权未完成", payload.toString());
    }

    private SysUser toUser(SysUserEntity entity) {
        return new SysUser(entity.getUserId(), entity.getTeamId(), entity.getName(),
                UserRole.parse(entity.getRole()), entity.getMentorUserId(), entity.getFeishuOpenId(),
                entity.getEmail(), UserStatus.parse(entity.getStatus()));
    }
}
