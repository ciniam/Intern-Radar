package com.ciniam.internradar.controller;

import com.ciniam.internradar.model.TeamInfo;
import com.ciniam.internradar.model.TeamJoinRequest;
import com.ciniam.internradar.model.TeamMember;
import com.ciniam.internradar.security.AuthGuard;
import com.ciniam.internradar.service.AuthService;
import com.ciniam.internradar.service.TeamService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 团队接口。未入团的账号只能调这里与个人中心，其余业务接口会被拦截
 */
@RestController
@RequestMapping("/team")
public class TeamController {

    private final TeamService teamService;
    private final AuthService authService;
    private final AuthGuard authGuard;

    public TeamController(TeamService teamService, AuthService authService, AuthGuard authGuard) {
        this.teamService = teamService;
        this.authService = authService;
        this.authGuard = authGuard;
    }

    /**
     * 我的团队信息。未入团时 joinStatus 是最近一次申请的状态
     */
    @GetMapping
    public TeamInfo mine() {
        return teamService.myTeam(authGuard.currentUserId());
    }

    @GetMapping("/members")
    public List<TeamMember> members() {
        return teamService.members(authGuard.currentUserId());
    }

    @PostMapping
    public TeamInfo create(@RequestBody CreateTeam payload) {
        return teamService.create(authGuard.currentUserId(),
                payload == null ? null : payload.name(),
                payload == null ? null : payload.description());
    }

    @PostMapping("/join")
    public TeamInfo join(@RequestBody JoinTeam payload) {
        return teamService.join(authGuard.currentUserId(),
                payload == null ? null : payload.inviteCode(),
                payload == null ? null : payload.message());
    }

    @GetMapping("/requests/mine")
    public List<TeamJoinRequest> myRequests() {
        return teamService.myRequests(authGuard.currentUserId());
    }

    /**
     * 团队创建人看到的待审申请
     */
    @GetMapping("/requests")
    public List<TeamJoinRequest> pending() {
        return teamService.pendingRequests(authGuard.currentUserId());
    }

    @PostMapping("/requests/{id}/approve")
    public TeamJoinRequest approve(@PathVariable Long id,
                                   @RequestParam(required = false, defaultValue = "INTERN") String role) {
        return teamService.approve(authGuard.currentUserId(), id, role);
    }

    @PostMapping("/requests/{id}/reject")
    public TeamJoinRequest reject(@PathVariable Long id) {
        return teamService.reject(authGuard.currentUserId(), id);
    }

    @PostMapping("/invite-code")
    public TeamInfo rotateInviteCode() {
        return teamService.rotateInviteCode(authGuard.currentUserId());
    }

    /**
     * 管理员重置成员密码，返回一次性临时密码，仅展示这一次
     */
    @PostMapping("/members/{userId}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable String userId) {
        String temp = authService.resetByAdmin(authGuard.currentUserId(), userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("temporaryPassword", temp);
        result.put("tip", "把这个临时密码发给对方，他登录后必须立即改密");
        return result;
    }

    public record CreateTeam(String name, String description) {
    }

    public record JoinTeam(String inviteCode, String message) {
    }
}
