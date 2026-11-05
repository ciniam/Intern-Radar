package com.ciniam.internradar.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ciniam.internradar.entity.SysUserEntity;
import com.ciniam.internradar.entity.UserSessionEntity;
import com.ciniam.internradar.mapper.SysUserMapper;
import com.ciniam.internradar.mapper.UserSessionMapper;
import com.ciniam.internradar.model.SysUser;
import com.ciniam.internradar.model.UserRole;
import com.ciniam.internradar.model.UserStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 登录校验与角色限权
 * 角色限权按路径前缀集中判断，避免每个接口各写一遍漏掉
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String TOKEN_HEADER = "X-Auth-Token";

    private static final List<String> TEAM_ONLY_PREFIXES = List.of("/mentor/", "/admin/", "/zentao/", "/push/");
    private static final List<String> TEAM_ONLY_PATHS = List.of("/learn/docs/refresh");

    private final UserSessionMapper userSessionMapper;
    private final SysUserMapper sysUserMapper;
    private final ObjectMapper objectMapper;

    public AuthInterceptor(UserSessionMapper userSessionMapper,
                           SysUserMapper sysUserMapper,
                           ObjectMapper objectMapper) {
        this.userSessionMapper = userSessionMapper;
        this.sysUserMapper = sysUserMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 跨域预检不带业务头，走的是同一条拦截器链，放行后由 Spring 的 CORS 处理回包
        // 这里拦掉会让所有带自定义请求头的跨域调用在浏览器里直接失败
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        String token = request.getHeader(TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            return reject(response, HttpStatus.UNAUTHORIZED, "缺少登录凭证，请先登录");
        }
        UserSessionEntity session = userSessionMapper.selectByToken(token);
        if (session == null || !"ACTIVE".equals(session.getStatus())) {
            return reject(response, HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        if (session.getExpireAt() == null || session.getExpireAt().isBefore(LocalDateTime.now())) {
            return reject(response, HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        SysUserEntity entity = sysUserMapper.selectByUserId(session.getUserId());
        if (entity == null || !Boolean.TRUE.equals(entity.getEnabled())) {
            return reject(response, HttpStatus.UNAUTHORIZED, "账号不存在或已停用");
        }
        SysUser user = new SysUser(entity.getUserId(), entity.getTeamId(), entity.getName(),
                UserRole.parse(entity.getRole()), entity.getMentorUserId(), entity.getFeishuOpenId(),
                entity.getEmail(), UserStatus.parse(entity.getStatus()));
        String path = pathWithinContext(request);
        // 初始密码与管理员重置后必须先改密，否则只放行个人中心与团队页
        if (Boolean.TRUE.equals(entity.getPasswordResetRequired()) && !allowedBeforeJoining(path)) {
            return reject(response, HttpStatus.FORBIDDEN, "请先修改初始密码再继续使用");
        }
        if (!user.joinedTeam() && !allowedBeforeJoining(path)) {
            return reject(response, HttpStatus.FORBIDDEN, "还没有加入团队，请先创建或加入一个团队");
        }
        if (requiresTeamView(path) && !user.canViewTeam()) {
            return reject(response, HttpStatus.FORBIDDEN, "该功能只对导师开放");
        }
        AuthContext.set(user);
        TeamContext.set(user.teamId());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
        TeamContext.clear();
    }

    /**
     * 未归属团队时仍然可用的入口：登录态、个人中心与团队页本身
     */
    private boolean allowedBeforeJoining(String uri) {
        if (uri == null) {
            return false;
        }
        return uri.startsWith("/auth/") || uri.startsWith("/profile") || uri.startsWith("/team");
    }

    /**
     * 去掉上下文路径后的请求路径，前缀规则按它匹配
     */
    private String pathWithinContext(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (uri == null) {
            return "";
        }
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }

    private boolean requiresTeamView(String uri) {
        if (uri == null) {
            return false;
        }
        for (String path : TEAM_ONLY_PATHS) {
            if (uri.equals(path) || uri.startsWith(path + "/")) {
                return true;
            }
        }
        for (String prefix : TEAM_ONLY_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean reject(HttpServletResponse response, HttpStatus status, String message) throws Exception {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
        return false;
    }
}
