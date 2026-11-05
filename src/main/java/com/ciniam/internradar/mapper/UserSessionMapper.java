package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.UserSessionEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserSessionMapper {

    @Insert("""
            INSERT INTO user_session (team_id, token, user_id, role, status, device, ip, expire_at)
            VALUES (#{teamId}, #{token}, #{userId}, #{role}, #{status}, #{device}, #{ip}, #{expireAt})
            """)
    int insert(UserSessionEntity entity);

    @Select("SELECT * FROM user_session WHERE token = #{token} LIMIT 1")
    UserSessionEntity selectByToken(@Param("token") String token);

    /**
     * 飞书回调时把待授权会话升级为已登录会话
     */
    @Update("""
            UPDATE user_session
            SET user_id = #{userId}, role = #{role}, status = 'ACTIVE', expire_at = #{expireAt}
            WHERE token = #{token} AND status = 'PENDING'
            """)
    int activate(@Param("token") String token,
                 @Param("userId") String userId,
                 @Param("role") String role,
                 @Param("expireAt") LocalDateTime expireAt);

    @Delete("DELETE FROM user_session WHERE token = #{token}")
    int deleteByToken(@Param("token") String token);

    @Delete("DELETE FROM user_session WHERE expire_at < #{now}")
    int deleteExpired(@Param("now") LocalDateTime now);

    /**
     * 登录设备列表，只取未过期的会话
     */
    @Select("""
            SELECT * FROM user_session
            WHERE user_id = #{userId} AND status = 'ACTIVE' AND expire_at > #{now}
            ORDER BY created_at DESC
            """)
    List<UserSessionEntity> selectActiveByUser(@Param("userId") String userId, @Param("now") LocalDateTime now);

    @Delete("DELETE FROM user_session WHERE user_id = #{userId}")
    int deleteByUser(@Param("userId") String userId);

    /**
     * 改密后清掉除当前会话以外的其它设备
     */
    @Delete("DELETE FROM user_session WHERE user_id = #{userId} AND token <> #{token}")
    int deleteOthers(@Param("userId") String userId, @Param("token") String token);

    @Delete("DELETE FROM user_session WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    /**
     * 同一台设备重复登录时清掉旧会话，登录设备列表里不会堆出多条同样的记录
     */
    @Delete("""
            DELETE FROM user_session
            WHERE user_id = #{userId} AND status = 'ACTIVE' AND device = #{device}
              AND IFNULL(ip, '') = IFNULL(#{ip}, '')
            """)
    int deleteSameDevice(@Param("userId") String userId,
                         @Param("device") String device,
                         @Param("ip") String ip);
}
