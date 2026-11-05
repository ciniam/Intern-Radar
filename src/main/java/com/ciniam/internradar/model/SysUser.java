package com.ciniam.internradar.model;

/**
 * 登录态里的用户信息。teamId 为 0 表示还没加入任何团队
 */
public record SysUser(
        String userId,
        Long teamId,
        String name,
        UserRole role,
        String mentorUserId,
        String feishuOpenId,
        String email,
        UserStatus status
) {

    public boolean canViewTeam() {
        return role != null && role.canViewTeam();
    }

    public boolean pending() {
        return status != null && status.pending();
    }

    /**
     * 有没有归属团队，没有的话只能访问团队页与个人中心
     */
    public boolean joinedTeam() {
        return teamId != null && teamId > 0;
    }
}
