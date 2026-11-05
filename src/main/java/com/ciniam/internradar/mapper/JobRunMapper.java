package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.JobRunEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

@Mapper
public interface JobRunMapper {

    /**
     * 插入即抢锁，同一人同一任务同一天只有第一次能插入成功，返回 0 表示已执行过
     */
    @Insert("""
            INSERT IGNORE INTO job_run (team_id, user_id, job_type, biz_date, status, duration_ms)
            VALUES (#{teamId}, #{userId}, #{jobType}, #{bizDate}, #{status}, 0)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int tryStart(JobRunEntity entity);

    @Select("""
            SELECT * FROM job_run
            WHERE user_id = #{userId} AND job_type = #{jobType} AND biz_date = #{bizDate}
            """)
    JobRunEntity selectOne(@Param("userId") String userId,
                           @Param("jobType") String jobType,
                           @Param("bizDate") LocalDate bizDate);

    @org.apache.ibatis.annotations.Update("""
            UPDATE job_run SET status = #{status}, duration_ms = #{durationMs}, error_msg = #{errorMsg}
            WHERE id = #{id}
            """)
    int finish(@Param("id") Long id,
               @Param("status") String status,
               @Param("durationMs") long durationMs,
               @Param("errorMsg") String errorMsg);
}
