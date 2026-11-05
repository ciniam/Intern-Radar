package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.LearnMessageEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LearnMessageMapper {

    @Insert("""
            INSERT INTO learn_message
                (team_id, conversation_id, user_id, role, content, reference_docs,
                 prompt_tokens, completion_tokens, agent_run_id, created_at)
            VALUES
                (#{teamId}, #{conversationId}, #{userId}, #{role}, #{content}, #{referenceDocs},
                 #{promptTokens}, #{completionTokens}, #{agentRunId}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(LearnMessageEntity entity);

    @Select("SELECT * FROM learn_message WHERE conversation_id = #{conversationId} ORDER BY id")
    List<LearnMessageEntity> selectByConversation(@Param("conversationId") Long conversationId);

    /**
     * 取最近若干条，用于拼接追问上下文，结果按时间正序返回
     */
    @Select("""
            SELECT * FROM (
                SELECT * FROM learn_message
                WHERE conversation_id = #{conversationId}
                ORDER BY id DESC LIMIT #{limit}
            ) recent ORDER BY recent.id
            """)
    List<LearnMessageEntity> selectRecent(@Param("conversationId") Long conversationId,
                                          @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM learn_message WHERE conversation_id = #{conversationId}")
    int countByConversation(@Param("conversationId") Long conversationId);

    @Select("SELECT COUNT(*) FROM learn_message WHERE user_id = #{userId} AND role = 'USER'")
    int countQuestions(@Param("userId") String userId);

    @Delete("DELETE FROM learn_message WHERE conversation_id = #{conversationId}")
    int deleteByConversation(@Param("conversationId") Long conversationId);

    @Delete("DELETE FROM learn_message WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    /**
     * 只保留最近 keep 条，更早的删掉，避免单条会话无限增长
     */
    @Delete("""
            DELETE FROM learn_message
            WHERE conversation_id = #{conversationId}
              AND id NOT IN (
                SELECT id FROM (
                    SELECT id FROM learn_message
                    WHERE conversation_id = #{conversationId}
                    ORDER BY id DESC LIMIT #{keep}
                ) kept
              )
            """)
    int deleteOldest(@Param("conversationId") Long conversationId, @Param("keep") int keep);
}
