package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.LlmCallLogEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface LlmCallLogMapper {

    @Insert("""
            INSERT INTO llm_call_log
                (team_id, user_id, scene, model, prompt_tokens, completion_tokens, duration_ms, success, error_msg)
            VALUES
                (#{teamId}, #{userId}, #{scene}, #{model}, #{promptTokens}, #{completionTokens}, #{durationMs}, #{success}, #{errorMsg})
            """)
    int insert(LlmCallLogEntity entity);

    @Select("""
            SELECT scene AS scene,
                   COUNT(*) AS calls,
                   SUM(prompt_tokens) AS promptTokens,
                   SUM(completion_tokens) AS completionTokens,
                   SUM(CASE WHEN success = 1 THEN 0 ELSE 1 END) AS failures,
                   ROUND(AVG(duration_ms)) AS avgDurationMs
            FROM llm_call_log
            WHERE created_at >= #{since}
            GROUP BY scene
            """)
    List<Map<String, Object>> statByScene(@Param("since") LocalDateTime since);

    /**
     * 按天统计问答次数，供今日热力图的「问答」一行
     */
    @Select("""
            SELECT DATE(created_at) AS statDate, COUNT(*) AS total
            FROM llm_call_log
            WHERE user_id = #{userId}
              AND scene = 'LEARN_ASK'
              AND success = 1
              AND created_at >= #{start}
              AND created_at < #{end}
            GROUP BY DATE(created_at)
            """)
    List<Map<String, Object>> selectDailyAsk(@Param("userId") String userId,
                                             @Param("start") LocalDateTime start,
                                             @Param("end") LocalDateTime end);
}
