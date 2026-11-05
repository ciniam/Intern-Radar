package com.ciniam.internradar.controller;

import com.ciniam.internradar.entity.SysUserEntity;
import com.ciniam.internradar.entity.TeamEntity;
import com.ciniam.internradar.mapper.SysUserMapper;
import com.ciniam.internradar.mapper.TeamMapper;
import com.ciniam.internradar.model.SysUser;
import com.ciniam.internradar.model.UserDevice;
import com.ciniam.internradar.security.AuthContext;
import com.ciniam.internradar.security.AuthGuard;
import com.ciniam.internradar.security.AuthInterceptor;
import com.ciniam.internradar.service.AuthService;
import com.ciniam.internradar.service.UserAccountService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 个人中心：本人资料、本人统计与登录设备
 * 全部接口只作用于当前登录人，不接受他人标识
 */
@RestController
@RequestMapping("/profile")
public class ProfileController {

    private final AuthService authService;
    private final UserAccountService userAccountService;
    private final SysUserMapper sysUserMapper;
    private final TeamMapper teamMapper;
    private final AuthGuard authGuard;

    public ProfileController(AuthService authService,
                             UserAccountService userAccountService,
                             SysUserMapper sysUserMapper,
                             TeamMapper teamMapper,
                             AuthGuard authGuard) {
        this.authService = authService;
        this.userAccountService = userAccountService;
        this.sysUserMapper = sysUserMapper;
        this.teamMapper = teamMapper;
        this.authGuard = authGuard;
    }

    @GetMapping
    public Map<String, Object> view() {
        SysUser user = authGuard.current();
        SysUserEntity entity = sysUserMapper.selectByUserId(user.userId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", user);
        result.put("stats", userAccountService.stats(user.userId()));
        result.put("passwordResetRequired", authService.passwordResetRequired(user.userId()));
        // 账号信息与安全区需要的几个时间点，之前只能靠猜
        result.put("teamName", teamName(user.teamId()));
        result.put("joinedAt", entity == null ? null : entity.getJoinedAt());
        result.put("lastLoginAt", entity == null ? null : entity.getLastLoginAt());
        result.put("passwordUpdatedAt", entity == null ? null : entity.getPasswordUpdatedAt());
        result.put("createdAt", entity == null ? null : entity.getCreatedAt());
        return result;
    }

    private String teamName(Long teamId) {
        if (teamId == null || teamId <= 0) {
            return null;
        }
        TeamEntity team = teamMapper.selectById(teamId);
        return team == null ? null : team.getName();
    }

    /**
     * 自助改密。校验原密码，改完其它设备下线
     */
    @PostMapping("/password")
    public Map<String, Object> changePassword(@RequestBody PasswordUpdate payload,
                                              @RequestHeader(value = AuthInterceptor.TOKEN_HEADER, required = false)
                                              String token) {
        authService.changePassword(authGuard.currentUserId(),
                payload == null ? null : payload.oldPassword(),
                payload == null ? null : payload.newPassword(),
                token);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "密码已更新，其它设备需要重新登录");
        return result;
    }

    /**
     * 资料编辑。姓名与邮箱都可留空表示不改
     */
    @PostMapping
    public SysUser update(@RequestBody ProfileUpdate payload) {
        return authService.updateProfile(authGuard.currentUserId(),
                payload == null ? null : payload.name(),
                payload == null ? null : payload.email());
    }

    @GetMapping("/devices")
    public List<UserDevice> devices(@RequestHeader(value = AuthInterceptor.TOKEN_HEADER, required = false) String token) {
        return authService.devices(authGuard.currentUserId(), token);
    }

    /**
     * 退出指定设备，当前设备不在其中，避免误把自己踢掉
     */
    @DeleteMapping("/devices/{id}")
    public Map<String, Object> logoutDevice(@PathVariable Long id,
                                            @RequestHeader(value = AuthInterceptor.TOKEN_HEADER, required = false)
                                            String token) {
        authService.logoutDevice(authGuard.currentUserId(), id, token);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return result;
    }

    /**
     * 退出全部设备，含当前这一台，前端随后回到登录页
     */
    @PostMapping("/devices/logout-all")
    public Map<String, Object> logoutAll() {
        int removed = authService.logoutAll(authGuard.currentUserId());
        AuthContext.clear();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("removed", removed);
        return result;
    }

    public record ProfileUpdate(String name, String email) {
    }

    public record PasswordUpdate(String oldPassword, String newPassword) {
    }
}
