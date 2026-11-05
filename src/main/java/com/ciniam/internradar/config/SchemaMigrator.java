package com.ciniam.internradar.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 建表脚本里全是 CREATE TABLE IF NOT EXISTS，已存在的表不会被改结构
 * 这里在启动时补齐后来新增的字段，重复执行安全，先于其他初始化动作运行
 */
@Component
@Order(0)
public class SchemaMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    private record Column(String table, String name, String definition) {
    }

    /**
     * 唯一键从「业务键唯一」改成「团队内业务键唯一」，多团队才不会互相顶掉
     */
    private record TeamIndex(String table, String legacyName, String name, String columns) {
    }

    private static final List<Column> COLUMNS = List.of(
            new Column("user_preference", "git_authors", "VARCHAR(512) DEFAULT NULL"),
            new Column("user_preference", "zentao_account", "VARCHAR(64) DEFAULT NULL"),
            new Column("user_preference", "zentao_password", "VARCHAR(1024) DEFAULT NULL"),
            new Column("user_preference", "zentao_login_allowed", "TINYINT(1) NOT NULL DEFAULT 0"),
            new Column("user_preference", "weekly_enabled", "TINYINT(1) NOT NULL DEFAULT 1"),
            new Column("user_preference", "weekly_weekday", "VARCHAR(8) NOT NULL DEFAULT 'FRI'"),
            new Column("user_preference", "weekly_time", "VARCHAR(5) NOT NULL DEFAULT '18:30'"),
            new Column("user_preference", "weekly_scope", "VARCHAR(16) NOT NULL DEFAULT 'WATCHED'"),
            new Column("user_preference", "watch_interns", "VARCHAR(512) DEFAULT NULL"),
            new Column("knowledge_doc", "uploader_user_id", "VARCHAR(64) DEFAULT NULL"),
            new Column("knowledge_doc", "uploaded_at", "DATETIME DEFAULT NULL"),
            new Column("sys_user", "status", "VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'"),
            new Column("sys_user", "email", "VARCHAR(128) DEFAULT NULL"),
            new Column("sys_user", "password_hash", "VARCHAR(200) DEFAULT NULL"),
            new Column("sys_user", "password_updated_at", "DATETIME DEFAULT NULL"),
            new Column("sys_user", "password_reset_required", "TINYINT(1) NOT NULL DEFAULT 0"),
            new Column("sys_user", "failed_attempts", "INT NOT NULL DEFAULT 0"),
            new Column("sys_user", "locked_until", "DATETIME DEFAULT NULL"),
            new Column("sys_user", "last_login_at", "DATETIME DEFAULT NULL"),
            new Column("sys_user", "joined_at", "DATETIME DEFAULT NULL"),
            new Column("user_session", "device", "VARCHAR(256) DEFAULT NULL"),
            new Column("user_session", "ip", "VARCHAR(64) DEFAULT NULL"),
            new Column("report", "agent_run_id", "BIGINT UNSIGNED DEFAULT NULL"),
            new Column("learn_message", "agent_run_id", "BIGINT UNSIGNED DEFAULT NULL"),
            new Column("risk_alert", "agent_run_id", "BIGINT UNSIGNED DEFAULT NULL"),
            // 告警标题原先只存在内存模型里，落库时丢失，前端只能拿 type 拼；补列后可直接展示
            new Column("risk_alert", "title", "VARCHAR(200) DEFAULT NULL")
    );

    /**
     * 加列之后的一次性回填，WHERE 里带了空值判断，重复执行安全
     */
    private static final List<String> BACKFILLS = List.of(
            // 历史账号没有入团时间，用账号创建时间顶上；没入团的保持为空
            "UPDATE sys_user SET joined_at = created_at WHERE joined_at IS NULL AND team_id > 0"
    );

    private static final List<TeamIndex> TEAM_INDEXES = List.of(
            new TeamIndex("knowledge_doc", "uk_knowledge_doc_ref", "uk_knowledge_doc_team_ref", "team_id, content_ref"),
            new TeamIndex("pitfall", "uk_pitfall_title", "uk_pitfall_team_title", "team_id, title"),
            // 登录名是邮箱，必须唯一；历史数据里 email 为空的行不受影响
            new TeamIndex("sys_user", null, "uk_sys_user_email", "email")
    );

    private final JdbcTemplate jdbcTemplate;

    public SchemaMigrator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Column column : COLUMNS) {
            try {
                if (exists(column)) {
                    continue;
                }
                jdbcTemplate.execute("ALTER TABLE `" + column.table() + "` ADD COLUMN `"
                        + column.name() + "` " + column.definition());
                log.info("已补充字段 {}.{}", column.table(), column.name());
            } catch (Exception e) {
                log.warn("补充字段失败 {}.{}：{}", column.table(), column.name(), e.getMessage());
            }
        }
        for (String sql : BACKFILLS) {
            try {
                int updated = jdbcTemplate.update(sql);
                if (updated > 0) {
                    log.info("已回填 {} 行：{}", updated, sql);
                }
            } catch (Exception e) {
                log.warn("回填失败 {}：{}", sql, e.getMessage());
            }
        }
        for (TeamIndex index : TEAM_INDEXES) {
            migrateIndex(index);
        }
    }

    /**
     * 先建团队维度的新唯一键，再删掉旧键。新键比旧键宽松，正常情况下不会因已有数据失败
     */
    private void migrateIndex(TeamIndex index) {
        try {
            if (indexExists(index.table(), index.name())) {
                return;
            }
            jdbcTemplate.execute("ALTER TABLE `" + index.table() + "` ADD UNIQUE KEY `"
                    + index.name() + "` (" + index.columns() + ")");
            log.info("已补充唯一键 {}.{}", index.table(), index.name());
            if (index.legacyName() != null && indexExists(index.table(), index.legacyName())) {
                jdbcTemplate.execute("ALTER TABLE `" + index.table() + "` DROP INDEX `"
                        + index.legacyName() + "`");
                log.info("已移除旧唯一键 {}.{}", index.table(), index.legacyName());
            }
        } catch (Exception e) {
            log.warn("迁移唯一键失败 {}.{}：{}", index.table(), index.name(), e.getMessage());
        }
    }

    private boolean exists(Column column) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """, Integer.class, column.table(), column.name());
        return count != null && count > 0;
    }

    private boolean indexExists(String table, String name) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?
                """, Integer.class, table, name);
        return count != null && count > 0;
    }
}
