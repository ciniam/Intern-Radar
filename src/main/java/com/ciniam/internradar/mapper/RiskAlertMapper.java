package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.RiskAlertEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RiskAlertMapper {

    /**
     * fingerprint 唯一，重复风险不会重复落库，返回 0 表示已存在
     */
    @Insert("""
            INSERT IGNORE INTO risk_alert
                (team_id, user_id, level, type, title, fingerprint, evidence, suggestion, status, agent_run_id, created_at)
            VALUES
                (#{teamId}, #{userId}, #{level}, #{type}, #{title}, #{fingerprint}, #{evidence}, #{suggestion},
                 #{status}, #{agentRunId}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIgnore(RiskAlertEntity entity);

    @Select("""
            SELECT * FROM risk_alert
            WHERE user_id = #{userId} AND status = #{status}
            ORDER BY created_at DESC
            """)
    List<RiskAlertEntity> selectByUserAndStatus(@Param("userId") String userId, @Param("status") String status);

    @Select("""
            SELECT * FROM risk_alert
            WHERE user_id = #{userId} AND fingerprint = #{fingerprint}
            """)
    RiskAlertEntity selectByFingerprint(@Param("userId") String userId, @Param("fingerprint") String fingerprint);

    @Select("SELECT * FROM risk_alert WHERE id = #{id}")
    RiskAlertEntity selectById(@Param("id") Long id);

    /**
     * 待通知告警，通知时间早于阈值的一并取出，实现同一告警 N 小时后可再次提醒
     */
    @Select("""
            SELECT * FROM risk_alert
            WHERE user_id = #{userId} AND status = 'OPEN'
              AND (notified_at IS NULL OR notified_at < #{since})
            ORDER BY created_at
            """)
    List<RiskAlertEntity> selectForNotify(@Param("userId") String userId, @Param("since") LocalDateTime since);

    @Update("UPDATE risk_alert SET notified_at = #{notifiedAt} WHERE id = #{id}")
    int markNotified(@Param("id") Long id, @Param("notifiedAt") LocalDateTime notifiedAt);

    @Update("UPDATE risk_alert SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);
}
