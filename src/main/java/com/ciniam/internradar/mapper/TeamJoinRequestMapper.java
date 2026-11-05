package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.TeamJoinRequestEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TeamJoinRequestMapper {

    @Insert("""
            INSERT INTO team_join_request (team_id, user_id, status, message, created_at)
            VALUES (#{teamId}, #{userId}, #{status}, #{message}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TeamJoinRequestEntity entity);

    @Select("SELECT * FROM team_join_request WHERE id = #{id}")
    TeamJoinRequestEntity selectById(@Param("id") Long id);

    @Select("""
            SELECT * FROM team_join_request
            WHERE team_id = #{teamId} AND status = 'PENDING'
            ORDER BY id
            """)
    List<TeamJoinRequestEntity> selectPendingByTeam(@Param("teamId") Long teamId);

    @Select("""
            SELECT * FROM team_join_request
            WHERE user_id = #{userId} ORDER BY id DESC LIMIT 10
            """)
    List<TeamJoinRequestEntity> selectByUser(@Param("userId") String userId);

    @Select("""
            SELECT COUNT(*) FROM team_join_request
            WHERE team_id = #{teamId} AND user_id = #{userId} AND status = 'PENDING'
            """)
    int countPending(@Param("teamId") Long teamId, @Param("userId") String userId);

    @Update("""
            UPDATE team_join_request
            SET status = #{status}, role = #{role}, handled_by = #{handledBy}, handled_at = #{handledAt}
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int handle(@Param("id") Long id,
               @Param("status") String status,
               @Param("role") String role,
               @Param("handledBy") String handledBy,
               @Param("handledAt") LocalDateTime handledAt);
}
