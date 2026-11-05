package com.ciniam.internradar.config;

import com.ciniam.internradar.service.TeamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时确保默认团队存在。老数据都挂在 team_id=1 上，没有这一行团队信息就查不出来
 */
@Component
@Order(1)
public class TeamBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TeamBootstrap.class);

    private static final long DEFAULT_TEAM_ID = 1L;

    private final JdbcTemplate jdbcTemplate;
    private final TeamService teamService;

    public TeamBootstrap(JdbcTemplate jdbcTemplate, TeamService teamService) {
        this.jdbcTemplate = jdbcTemplate;
        this.teamService = teamService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM team WHERE id = ?", Integer.class, DEFAULT_TEAM_ID);
            if (count != null && count > 0) {
                return;
            }
            jdbcTemplate.update("""
                    INSERT INTO team (id, name, invite_code, owner_user_id, description)
                    VALUES (?, ?, ?, NULL, ?)
                    """, DEFAULT_TEAM_ID, "默认团队", teamService.randomInviteCode(),
                    "系统初始化，接入多团队之前的原有数据都归在这里");
            log.info("已创建默认团队，原有账号与数据继续归属 team_id={}", DEFAULT_TEAM_ID);
        } catch (Exception e) {
            log.warn("默认团队初始化失败：{}", e.getMessage());
        }
    }
}
