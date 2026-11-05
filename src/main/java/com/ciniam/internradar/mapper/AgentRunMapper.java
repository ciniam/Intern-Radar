package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.AgentRunEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AgentRunMapper {

    @Insert("""
            INSERT INTO agent_run
                (team_id, user_id, scene, title, status, step_count, prompt_tokens,
                 completion_tokens, duration_ms, error_msg, created_at)
            VALUES
                (#{teamId}, #{userId}, #{scene}, #{title}, #{status}, #{stepCount}, #{promptTokens},
                 #{completionTokens}, #{durationMs}, #{errorMsg}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AgentRunEntity entity);

    @Select("SELECT * FROM agent_run WHERE id = #{id}")
    AgentRunEntity selectById(@Param("id") Long id);

    @Update("""
            UPDATE agent_run
            SET status = #{status}, step_count = #{stepCount}, prompt_tokens = #{promptTokens},
                completion_tokens = #{completionTokens}, duration_ms = #{durationMs}, error_msg = #{errorMsg}
            WHERE id = #{id}
            """)
    int finish(@Param("id") Long id,
               @Param("status") String status,
               @Param("stepCount") int stepCount,
               @Param("promptTokens") int promptTokens,
               @Param("completionTokens") int completionTokens,
               @Param("durationMs") long durationMs,
               @Param("errorMsg") String errorMsg);

    /**
     * 轨迹列表，scene 为空时返回全部场景，只看指定用户自己的运行记录
     */
    @Select("""
            <script>
            SELECT * FROM agent_run
            WHERE user_id = #{userId}
            <if test="scene != null and scene != ''">
              AND scene = #{scene}
            </if>
            ORDER BY id DESC LIMIT #{limit}
            </script>
            """)
    List<AgentRunEntity> selectByUser(@Param("userId") String userId,
                                      @Param("scene") String scene,
                                      @Param("limit") int limit);
}
