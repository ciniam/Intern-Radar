package com.ciniam.internradar.service;

import com.ciniam.internradar.entity.SysUserEntity;
import com.ciniam.internradar.mapper.SysUserMapper;
import com.ciniam.internradar.model.SysUser;
import com.ciniam.internradar.model.UserRole;
import com.ciniam.internradar.model.UserStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 系统用户目录。sys_user 是用户与角色的唯一事实来源
 * 名单一律取当前团队，跨团队互相看不到
 */
@Service
public class UserDirectory {

    private final SysUserMapper sysUserMapper;
    private final TeamScope teamScope;

    public UserDirectory(SysUserMapper sysUserMapper, TeamScope teamScope) {
        this.sysUserMapper = sysUserMapper;
        this.teamScope = teamScope;
    }

    public List<SysUser> all() {
        return toUsers(sysUserMapper.selectByTeam(teamScope.current()));
    }

    public List<SysUser> interns() {
        return byRole(UserRole.INTERN);
    }

    public List<SysUser> mentors() {
        return byRole(UserRole.MENTOR);
    }

    public SysUser byUserId(String userId) {
        SysUserEntity entity = sysUserMapper.selectByUserId(userId);
        return entity == null ? null : toUser(entity);
    }

    /**
     * 跨团队取实习生，供定时任务使用。未归属团队的账号不参与
     */
    public List<SysUser> internsAcrossTeams() {
        return acrossTeams(UserRole.INTERN);
    }

    public List<SysUser> mentorsAcrossTeams() {
        return acrossTeams(UserRole.MENTOR);
    }

    private List<SysUser> acrossTeams(UserRole role) {
        List<SysUser> users = new ArrayList<>();
        for (SysUserEntity entity : sysUserMapper.selectEnabled()) {
            if (entity.getTeamId() == null || entity.getTeamId() <= 0) {
                continue;
            }
            if (role == UserRole.parse(entity.getRole())) {
                users.add(toUser(entity));
            }
        }
        return users;
    }

    private List<SysUser> byRole(UserRole role) {
        List<SysUser> users = new ArrayList<>();
        for (SysUserEntity entity : sysUserMapper.selectByTeam(teamScope.current())) {
            if (role == UserRole.parse(entity.getRole())) {
                users.add(toUser(entity));
            }
        }
        return users;
    }

    private List<SysUser> toUsers(List<SysUserEntity> entities) {
        List<SysUser> users = new ArrayList<>();
        for (SysUserEntity entity : entities) {
            users.add(toUser(entity));
        }
        return users;
    }

    private SysUser toUser(SysUserEntity entity) {
        return new SysUser(entity.getUserId(), entity.getTeamId(), entity.getName(),
                UserRole.parse(entity.getRole()), entity.getMentorUserId(), entity.getFeishuOpenId(),
                entity.getEmail(), UserStatus.parse(entity.getStatus()));
    }
}
