package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.TeamSettingEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TeamSettingMapper {

    @Insert("""
            INSERT INTO team_setting (team_id, setting_key, setting_value, updated_at)
            VALUES (#{teamId}, #{settingKey}, #{settingValue}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
                setting_value = VALUES(setting_value),
                updated_at = VALUES(updated_at)
            """)
    int upsert(TeamSettingEntity entity);

    @Select("SELECT * FROM team_setting WHERE team_id = #{teamId}")
    List<TeamSettingEntity> selectByTeam(@Param("teamId") Long teamId);
}
