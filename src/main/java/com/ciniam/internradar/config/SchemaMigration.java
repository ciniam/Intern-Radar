package com.ciniam.internradar.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 补列脚本。schema.sql 里全是 CREATE TABLE IF NOT EXISTS，对已存在的表加列不会生效，
 * 这里按 information_schema 逐条判断，缺了才 ALTER，重复启动安全
 * 只做加列，不做改类型与删列，避免误伤已有数据
 */
@Component
public class SchemaMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigration.class);

    private record RequiredColumn(String table, String column, String definition) {
    }

    private static final List<RequiredColumn> REQUIRED_COLUMNS = List.of(
            new RequiredColumn("user_preference", "git_repositories", "VARCHAR(1024) DEFAULT NULL")
    );

    private final JdbcTemplate jdbcTemplate;

    public SchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (RequiredColumn required : REQUIRED_COLUMNS) {
            try {
                if (exists(required)) {
                    continue;
                }
                jdbcTemplate.execute("ALTER TABLE " + required.table()
                        + " ADD COLUMN " + required.column() + " " + required.definition());
                log.info("已补齐字段 {}.{}", required.table(), required.column());
            } catch (Exception e) {
                log.warn("补齐字段 {}.{} 失败，如相关功能报错请手工执行 ALTER：{}",
                        required.table(), required.column(), e.getMessage());
            }
        }
    }

    private boolean exists(RequiredColumn required) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """, Integer.class, required.table(), required.column());
        return count != null && count > 0;
    }
}
