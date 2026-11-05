package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.PushLogEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface PushLogMapper {

    @Insert("""
            INSERT INTO push_log
                (team_id, user_id, channel, type, content, status, error_msg, sent_at)
            VALUES
                (#{teamId}, #{userId}, #{channel}, #{type}, #{content}, #{status}, #{errorMsg}, #{sentAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PushLogEntity entity);

    @Select("""
            SELECT channel AS channel,
                   COUNT(*) AS total,
                   SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS success,
                   SUM(CASE WHEN status = 'SUCCESS' THEN 0 ELSE 1 END) AS failures
            FROM push_log
            WHERE sent_at >= #{since}
            GROUP BY channel
            """)
    List<Map<String, Object>> statByChannel(@Param("since") LocalDateTime since);

    @Select("""
            SELECT * FROM push_log
            WHERE user_id = #{userId} AND type = #{type}
            ORDER BY sent_at DESC LIMIT 20
            """)
    List<PushLogEntity> selectRecent(@Param("userId") String userId, @Param("type") String type);
}
