package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.PitfallEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PitfallMapper {

    /**
     * 标题唯一，按标题覆盖，便于文件重扫
     */
    @Insert("""
            INSERT INTO pitfall
                (team_id, title, tags, symptom, cause, solution, content_ref, enabled, updated_at)
            VALUES
                (#{teamId}, #{title}, #{tags}, #{symptom}, #{cause}, #{solution},
                 #{contentRef}, #{enabled}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
                tags = VALUES(tags),
                symptom = VALUES(symptom),
                cause = VALUES(cause),
                solution = VALUES(solution),
                content_ref = VALUES(content_ref),
                enabled = VALUES(enabled),
                updated_at = VALUES(updated_at)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsert(PitfallEntity entity);

    @Select("SELECT * FROM pitfall WHERE team_id = #{teamId} AND enabled = 1 ORDER BY id")
    List<PitfallEntity> selectEnabled(@Param("teamId") long teamId);

    @Select("SELECT * FROM pitfall WHERE id = #{id}")
    PitfallEntity selectById(@Param("id") Long id);

    @Delete("DELETE FROM pitfall WHERE team_id = #{teamId} AND title = #{title}")
    int deleteByTitle(@Param("teamId") long teamId, @Param("title") String title);
}
