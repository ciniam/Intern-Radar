package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.AgentStepEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentStepMapper {

    @Insert("""
            INSERT INTO agent_step
                (team_id, run_id, step_no, thought, tool_name, tool_args, tool_result, duration_ms, created_at)
            VALUES
                (#{teamId}, #{runId}, #{stepNo}, #{thought}, #{toolName}, #{toolArgs}, #{toolResult},
                 #{durationMs}, #{createdAt})
            """)
    int insert(AgentStepEntity entity);

    @Select("SELECT * FROM agent_step WHERE run_id = #{runId} ORDER BY step_no, id")
    List<AgentStepEntity> selectByRun(@Param("runId") Long runId);
}
