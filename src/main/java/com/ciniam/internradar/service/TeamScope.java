package com.ciniam.internradar.service;

import com.ciniam.internradar.entity.SysUserEntity;
import com.ciniam.internradar.mapper.SysUserMapper;
import com.ciniam.internradar.model.SysUser;
import com.ciniam.internradar.security.AuthContext;
import com.ciniam.internradar.security.TeamContext;
import org.springframework.stereotype.Service;

/**
 * 团队归属的唯一取值入口。所有落库与查询的 team_id 都从这里取，不再写死常量
 * 优先用请求上下文，任务里按用户回查一次
 */
@Service
public class TeamScope {

    /**
     * 兜底团队，只在既没有上下文又查不到用户时使用
     */
    public static final long FALLBACK_TEAM_ID = 1L;

    /**
     * 未归属任何团队的账号
     */
    public static final long NO_TEAM = 0L;

    private final SysUserMapper sysUserMapper;

    public TeamScope(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    /**
     * 当前请求或当前任务的团队
     */
    public long current() {
        Long teamId = TeamContext.get();
        return teamId == null ? FALLBACK_TEAM_ID : teamId;
    }

    /**
     * 按人员取团队。本人优先取上下文，其余情况回查一次用户表
     * 查不到这个人时返回未归属，避免把未知标识默认算进默认团队
     */
    public long of(String userId) {
        SysUser current = AuthContext.get();
        if (current != null && userId != null && userId.equals(current.userId()) && current.teamId() != null) {
            return current.teamId();
        }
        SysUserEntity entity = userId == null ? null : sysUserMapper.selectByUserId(userId);
        if (entity != null && entity.getTeamId() != null) {
            return entity.getTeamId();
        }
        return NO_TEAM;
    }
}
