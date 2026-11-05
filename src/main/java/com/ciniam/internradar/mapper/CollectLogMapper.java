package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.CollectLogEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface CollectLogMapper {

    @Insert("""
            INSERT INTO collect_log
                (team_id, user_id, source, stat_date, fetched, inserted, duration_ms, success, error_msg)
            VALUES
                (#{teamId}, #{userId}, #{source}, #{statDate}, #{fetched}, #{inserted}, #{durationMs}, #{success}, #{errorMsg})
            """)
    int insert(CollectLogEntity entity);

    @Select("""
            SELECT source AS source,
                   COUNT(*) AS runs,
                   SUM(fetched) AS fetched,
                   SUM(inserted) AS inserted,
                   SUM(CASE WHEN success = 1 THEN 0 ELSE 1 END) AS failures,
                   ROUND(AVG(duration_ms)) AS avgDurationMs
            FROM collect_log
            WHERE created_at >= #{since}
            GROUP BY source
            """)
    List<Map<String, Object>> statBySource(@Param("since") LocalDateTime since);

    /**
     * 每个数据源最近一次采集记录，用于数据源状态展示
     */
    @Select("""
            SELECT c.* FROM collect_log c
            JOIN (SELECT source, MAX(id) AS max_id FROM collect_log
                  WHERE user_id = #{userId} GROUP BY source) t
              ON c.id = t.max_id
            """)
    List<CollectLogEntity> selectLatestPerSource(@Param("userId") String userId);
}
