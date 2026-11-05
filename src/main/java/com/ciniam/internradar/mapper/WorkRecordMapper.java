package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.WorkRecordEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface WorkRecordMapper {

    /**
     * 批量写入。唯一键 user_id + source + external_id 保证重复采集不会产生重复数据
     */
    @Insert("""
            <script>
            INSERT IGNORE INTO work_record
                (team_id, user_id, source, type, title, content, status, occurred_at, external_id, metadata)
            VALUES
            <foreach collection="list" item="it" separator=",">
                (#{it.teamId}, #{it.userId}, #{it.source}, #{it.type}, #{it.title}, #{it.content},
                 #{it.status}, #{it.occurredAt}, #{it.externalId}, #{it.metadata})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("list") List<WorkRecordEntity> list);

    @Select("""
            SELECT * FROM work_record
            WHERE user_id = #{userId} AND occurred_at >= #{start} AND occurred_at < #{end}
            ORDER BY occurred_at
            """)
    List<WorkRecordEntity> selectByUserAndRange(@Param("userId") String userId,
                                                @Param("start") LocalDateTime start,
                                                @Param("end") LocalDateTime end);

    @Select("SELECT MAX(occurred_at) FROM work_record WHERE user_id = #{userId}")
    LocalDateTime selectLastOccurredAt(@Param("userId") String userId);

    /**
     * 某个会话最近一次摘要，供会话绑定列表展示
     */
    @Select("""
            SELECT content AS content, occurred_at AS occurredAt
            FROM work_record
            WHERE user_id = #{userId}
              AND source = 'feishu-chat'
              AND JSON_UNQUOTE(JSON_EXTRACT(metadata, '$.chatId')) = #{chatId}
            ORDER BY occurred_at DESC, id DESC
            LIMIT 1
            """)
    Map<String, Object> selectLatestChatSummary(@Param("userId") String userId, @Param("chatId") String chatId);
}
