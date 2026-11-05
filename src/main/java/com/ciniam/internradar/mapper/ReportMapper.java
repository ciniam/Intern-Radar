package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.ReportEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ReportMapper {

    @Insert("""
            INSERT INTO report
                (team_id, user_id, type, period_start, period_end, content_json,
                 tokens_used, cost, status, agent_run_id, generated_at)
            VALUES
                (#{teamId}, #{userId}, #{type}, #{periodStart}, #{periodEnd}, #{contentJson},
                 #{tokensUsed}, #{cost}, #{status}, #{agentRunId}, #{generatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ReportEntity entity);

    @Select("""
            SELECT * FROM report
            WHERE user_id = #{userId} AND type = #{type} AND period_start = #{periodStart}
            ORDER BY generated_at DESC LIMIT 1
            """)
    ReportEntity selectLatest(@Param("userId") String userId,
                              @Param("type") String type,
                              @Param("periodStart") LocalDate periodStart);

    @Select("""
            SELECT * FROM report
            WHERE user_id = #{userId} AND period_start >= #{start} AND period_start <= #{end}
            ORDER BY period_start, generated_at DESC
            """)
    List<ReportEntity> selectByUserAndRange(@Param("userId") String userId,
                                            @Param("start") LocalDate start,
                                            @Param("end") LocalDate end);

    /**
     * 按类型可选地查询区间内报告，type 为空时返回全部类型
     */
    @Select("""
            <script>
            SELECT * FROM report
            WHERE user_id = #{userId} AND period_start &gt;= #{start} AND period_start &lt;= #{end}
            <if test="type != null and type != ''">
              AND type = #{type}
            </if>
            ORDER BY period_start DESC, generated_at DESC
            </script>
            """)
    List<ReportEntity> selectByUser(@Param("userId") String userId,
                                    @Param("start") LocalDate start,
                                    @Param("end") LocalDate end,
                                    @Param("type") String type);
}
