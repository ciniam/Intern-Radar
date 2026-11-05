package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.DocRetrieveLogEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface DocRetrieveLogMapper {

    @Insert("""
            INSERT INTO doc_retrieve_log
                (team_id, user_id, scene, query_text, hit_count, read_count, read_chars,
                 duration_ms, success, error_msg)
            VALUES
                (#{teamId}, #{userId}, #{scene}, #{queryText}, #{hitCount}, #{readCount},
                 #{readChars}, #{durationMs}, #{success}, #{errorMsg})
            """)
    int insert(DocRetrieveLogEntity entity);

    /**
     * 命中率按「有命中的检索次数 ÷ 总检索次数」计算，读取字符数用于观察上下文成本
     */
    @Select("""
            SELECT scene AS scene,
                   COUNT(*) AS calls,
                   SUM(CASE WHEN hit_count > 0 THEN 1 ELSE 0 END) AS hitCalls,
                   SUM(hit_count) AS hits,
                   SUM(read_count) AS readDocs,
                   SUM(read_chars) AS readChars,
                   ROUND(AVG(duration_ms)) AS avgDurationMs
            FROM doc_retrieve_log
            WHERE created_at >= #{since}
            GROUP BY scene
            """)
    List<Map<String, Object>> statByScene(@Param("since") LocalDateTime since);

    /**
     * 按天统计文档阅读篇数，供今日热力图的「文档阅读」一行
     */
    @Select("""
            SELECT DATE(created_at) AS statDate, SUM(read_count) AS total
            FROM doc_retrieve_log
            WHERE user_id = #{userId}
              AND scene = 'LEARN_ASK'
              AND success = 1
              AND read_count > 0
              AND created_at >= #{start}
              AND created_at < #{end}
            GROUP BY DATE(created_at)
            """)
    List<Map<String, Object>> selectDailyRead(@Param("userId") String userId,
                                              @Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end);
}
