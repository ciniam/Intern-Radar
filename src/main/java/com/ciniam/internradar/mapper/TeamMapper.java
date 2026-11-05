package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.TeamEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TeamMapper {

    @Insert("""
            INSERT INTO team (name, invite_code, owner_user_id, description, enabled, created_at, updated_at)
            VALUES (#{name}, #{inviteCode}, #{ownerUserId}, #{description}, 1, #{createdAt}, #{updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TeamEntity entity);

    @Select("SELECT * FROM team WHERE id = #{id}")
    TeamEntity selectById(@Param("id") Long id);

    @Select("SELECT * FROM team WHERE invite_code = #{inviteCode} AND enabled = 1 LIMIT 1")
    TeamEntity selectByInviteCode(@Param("inviteCode") String inviteCode);

    @Select("SELECT * FROM team WHERE enabled = 1 ORDER BY id")
    List<TeamEntity> selectAll();

    @Update("UPDATE team SET invite_code = #{inviteCode}, updated_at = #{updatedAt} WHERE id = #{id}")
    int updateInviteCode(@Param("id") Long id,
                         @Param("inviteCode") String inviteCode,
                         @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE team SET owner_user_id = #{ownerUserId}, updated_at = #{updatedAt} WHERE id = #{id}")
    int updateOwner(@Param("id") Long id,
                    @Param("ownerUserId") String ownerUserId,
                    @Param("updatedAt") LocalDateTime updatedAt);
}
