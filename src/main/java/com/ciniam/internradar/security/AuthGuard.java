package com.ciniam.internradar.security;

import com.ciniam.internradar.model.SysUser;
import com.ciniam.internradar.service.TeamScope;
import org.springframework.stereotype.Component;

/**
 * 接口层的权限判定。实习生只能访问自己的数据，导师与管理员可访问同团队其他人的数据
 * 跨团队一律拒绝，团队边界不依赖调用方传参
 */
@Component
public class AuthGuard {

    private final TeamScope teamScope;

    public AuthGuard(TeamScope teamScope) {
        this.teamScope = teamScope;
    }

    public SysUser current() {
        SysUser user = AuthContext.get();
        if (user == null) {
            throw new UnauthorizedException("未登录或会话已失效，请重新登录");
        }
        return user;
    }

    public String currentUserId() {
        return current().userId();
    }

    /**
     * 判断是否在查看自己的数据
     */
    public boolean isSelf(String userId) {
        SysUser user = AuthContext.get();
        return user != null && user.userId() != null && user.userId().equals(userId);
    }

    /**
     * 目标数据如果是别人的，只有同团队的导师与管理员能看
     */
    public void requireSelfOrMentor(String targetUserId, String scene) {
        SysUser user = current();
        if (isSelf(targetUserId)) {
            return;
        }
        if (!user.canViewTeam()) {
            throw new ForbiddenException("无权查看他人的" + scene);
        }
        if (!sameTeam(user, targetUserId)) {
            throw new ForbiddenException("无权查看其他团队的" + scene);
        }
    }

    public void requireTeamView(String scene) {
        SysUser user = current();
        if (user.canViewTeam()) {
            return;
        }
        throw new ForbiddenException("只有导师可以查看" + scene);
    }

    /**
     * 实习生调接口时不带 userId 的场合，默认按登录人处理
     */
    public String resolveUserId(String requested) {
        SysUser user = current();
        if (requested == null || requested.isBlank()) {
            return user.userId();
        }
        requireSelfOrMentor(requested, "数据");
        return requested;
    }

    private boolean sameTeam(SysUser user, String targetUserId) {
        long ownTeam = user.teamId() == null ? TeamScope.NO_TEAM : user.teamId();
        if (ownTeam <= 0) {
            return false;
        }
        return teamScope.of(targetUserId) == ownTeam;
    }
}
