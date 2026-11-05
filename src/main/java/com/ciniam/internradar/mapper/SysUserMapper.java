package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.SysUserEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SysUserMapper {

    /**
     * 只新增，不覆盖已有行，保证后台调整过的角色与启用状态不会被配置改回去
     */
    @Insert("""
            INSERT IGNORE INTO sys_user
                (team_id, user_id, name, role, mentor_user_id, feishu_open_id, email, status, enabled, updated_at)
            VALUES
                (#{teamId}, #{userId}, #{name}, #{role}, #{mentorUserId}, #{feishuOpenId}, #{email},
                 #{status}, #{enabled}, #{updatedAt})
            """)
    int insertIgnore(SysUserEntity entity);

    /**
     * 飞书首次登录自动建号，落在待审批状态。已存在的行不会被覆盖
     */
    @Insert("""
            INSERT IGNORE INTO sys_user
                (team_id, user_id, name, role, status, feishu_open_id, enabled, updated_at)
            VALUES
                (0, #{userId}, #{name}, 'INTERN', 'PENDING', #{feishuOpenId}, 1, #{updatedAt})
            """)
    int insertPending(SysUserEntity entity);

    /**
     * 补全配置里能提供的资料，不动角色、状态与启用位
     */
    @Update("""
            UPDATE sys_user
            SET name = #{name},
                mentor_user_id = #{mentorUserId},
                feishu_open_id = #{feishuOpenId},
                updated_at = #{updatedAt}
            WHERE user_id = #{userId}
            """)
    int updateProfile(@Param("userId") String userId,
                      @Param("name") String name,
                      @Param("mentorUserId") String mentorUserId,
                      @Param("feishuOpenId") String feishuOpenId,
                      @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 个人资料自助编辑，只改姓名与邮箱
     */
    @Update("""
            UPDATE sys_user
            SET name = #{name}, email = #{email}, updated_at = #{updatedAt}
            WHERE user_id = #{userId}
            """)
    int updateOwnProfile(@Param("userId") String userId,
                         @Param("name") String name,
                         @Param("email") String email,
                         @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 导师审批：置为已通过并同时定角色
     */
    @Update("""
            UPDATE sys_user
            SET status = 'ACTIVE', role = #{role}, mentor_user_id = #{mentorUserId},
                joined_at = COALESCE(joined_at, #{updatedAt}), updated_at = #{updatedAt}
            WHERE user_id = #{userId}
            """)
    int approve(@Param("userId") String userId,
                @Param("role") String role,
                @Param("mentorUserId") String mentorUserId,
                @Param("updatedAt") LocalDateTime updatedAt);

    @Select("SELECT * FROM sys_user WHERE enabled = 1 ORDER BY role, user_id")
    List<SysUserEntity> selectEnabled();

    @Select("SELECT * FROM sys_user WHERE user_id = #{userId} LIMIT 1")
    SysUserEntity selectByUserId(@Param("userId") String userId);

    @Select("SELECT * FROM sys_user WHERE feishu_open_id = #{openId} AND enabled = 1 LIMIT 1")
    SysUserEntity selectByOpenId(@Param("openId") String openId);

    @Select("SELECT * FROM sys_user WHERE email = #{email} LIMIT 1")
    SysUserEntity selectByEmail(@Param("email") String email);

    @Select("SELECT COUNT(*) FROM sys_user WHERE email = #{email}")
    int countByEmail(@Param("email") String email);

    /**
     * 邮箱注册。新账号未归属团队，需要自己创建或凭邀请码加入
     */
    @Insert("""
            INSERT INTO sys_user
                (team_id, user_id, name, email, password_hash, password_updated_at,
                 password_reset_required, status, enabled, updated_at)
            VALUES
                (0, #{userId}, #{name}, #{email}, #{passwordHash}, #{passwordUpdatedAt},
                 0, 'ACTIVE', 1, #{updatedAt})
            """)
    int insertRegister(SysUserEntity entity);

    /**
     * 登录成功：清零失败次数、解除锁定、记录最近登录时间
     */
    @Update("""
            UPDATE sys_user
            SET failed_attempts = 0, locked_until = NULL, last_login_at = #{now}, updated_at = #{now}
            WHERE user_id = #{userId}
            """)
    int updateLoginSuccess(@Param("userId") String userId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_user
            SET failed_attempts = #{failedAttempts}, locked_until = #{lockedUntil}, updated_at = #{now}
            WHERE user_id = #{userId}
            """)
    int updateLoginFailure(@Param("userId") String userId,
                           @Param("failedAttempts") int failedAttempts,
                           @Param("lockedUntil") LocalDateTime lockedUntil,
                           @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_user
            SET password_hash = #{passwordHash}, password_updated_at = #{now},
                password_reset_required = #{resetRequired}, failed_attempts = 0,
                locked_until = NULL, updated_at = #{now}
            WHERE user_id = #{userId}
            """)
    int updatePassword(@Param("userId") String userId,
                       @Param("passwordHash") String passwordHash,
                       @Param("resetRequired") boolean resetRequired,
                       @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_user
            SET email = #{email}, password_hash = #{passwordHash}, password_updated_at = #{now},
                password_reset_required = #{resetRequired}, updated_at = #{now}
            WHERE user_id = #{userId}
            """)
    int updateCredential(@Param("userId") String userId,
                         @Param("email") String email,
                         @Param("passwordHash") String passwordHash,
                         @Param("resetRequired") boolean resetRequired,
                         @Param("now") LocalDateTime now);

    @Select("SELECT * FROM sys_user WHERE status = #{status} ORDER BY created_at DESC")
    List<SysUserEntity> selectByStatus(@Param("status") String status);

    @Select("SELECT * FROM sys_user WHERE team_id = #{teamId} AND enabled = 1 ORDER BY role, user_id")
    List<SysUserEntity> selectByTeam(@Param("teamId") Long teamId);

    /**
     * 入团：把归属、角色与审批状态一次写定
     */
    @Update("""
            UPDATE sys_user
            SET team_id = #{teamId}, role = #{role}, mentor_user_id = #{mentorUserId},
                status = 'ACTIVE', updated_at = #{updatedAt}
            WHERE user_id = #{userId}
            """)
    int updateTeam(@Param("userId") String userId,
                   @Param("teamId") Long teamId,
                   @Param("role") String role,
                   @Param("mentorUserId") String mentorUserId,
                   @Param("updatedAt") LocalDateTime updatedAt);

    @Select("SELECT COUNT(*) FROM sys_user WHERE team_id = #{teamId} AND enabled = 1")
    int countByTeam(@Param("teamId") Long teamId);
}
