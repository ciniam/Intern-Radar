package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.ChatCursorEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ChatCursorMapper {

    @Insert("""
            INSERT INTO chat_cursor (team_id, chat_id, user_id, last_message_time, updated_at)
            VALUES (#{teamId}, #{chatId}, #{userId}, #{lastMessageTime}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
                last_message_time = VALUES(last_message_time),
                updated_at = VALUES(updated_at)
            """)
    int upsert(ChatCursorEntity entity);

    @Select("SELECT * FROM chat_cursor WHERE chat_id = #{chatId} AND user_id = #{userId}")
    ChatCursorEntity selectOne(@Param("chatId") String chatId, @Param("userId") String userId);
}
