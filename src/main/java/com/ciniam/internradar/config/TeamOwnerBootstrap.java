package com.ciniam.internradar.config;

import com.ciniam.internradar.entity.SysUserEntity;
import com.ciniam.internradar.entity.TeamEntity;
import com.ciniam.internradar.mapper.SysUserMapper;
import com.ciniam.internradar.mapper.TeamMapper;
import com.ciniam.internradar.model.UserRole;
import com.ciniam.internradar.service.TeamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 给还没有创建人的团队指定一个：优先用配置里的第一个导师，其次用团队内第一个管理员或导师
 * 默认团队是启动时自动补的，没有创建人就没人能看邀请码、没人能审批加入申请
 */
@Component
@Order(11)
public class TeamOwnerBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TeamOwnerBootstrap.class);

    private final TeamMapper teamMapper;
    private final SysUserMapper sysUserMapper;
    private final TeamService teamService;
    private final InternRadarProperties properties;

    public TeamOwnerBootstrap(TeamMapper teamMapper,
                              SysUserMapper sysUserMapper,
                              TeamService teamService,
                              InternRadarProperties properties) {
        this.teamMapper = teamMapper;
        this.sysUserMapper = sysUserMapper;
        this.teamService = teamService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            for (TeamEntity team : teamMapper.selectAll()) {
                if (team.getOwnerUserId() != null && !team.getOwnerUserId().isBlank()) {
                    continue;
                }
                String owner = resolveOwner(team.getId());
                if (owner == null) {
                    log.warn("团队 {} 还没有创建人，团队里也没有导师或管理员可指定", team.getId());
                    continue;
                }
                teamService.assignOwner(team.getId(), owner);
            }
        } catch (Exception e) {
            log.warn("团队创建人初始化失败：{}", e.getMessage());
        }
    }

    private String resolveOwner(long teamId) {
        for (InternRadarProperties.Mentor mentor : properties.getMentors()) {
            if (mentor.getUserId() != null && !mentor.getUserId().isBlank()) {
                return mentor.getUserId();
            }
        }
        for (SysUserEntity entity : sysUserMapper.selectByTeam(teamId)) {
            if (UserRole.parse(entity.getRole()).canViewTeam()) {
                return entity.getUserId();
            }
        }
        return null;
    }
}
