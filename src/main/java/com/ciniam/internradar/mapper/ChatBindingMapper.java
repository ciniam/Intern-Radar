package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.ChatBindingEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatBindingMapper {

    @Insert("""
            INSERT INTO chat_binding
                (team_id, user_id, chat_id, name, chat_type, identity, enabled, verified, verify_msg, updated_at)
            VALUES
                (#{teamId}, #{userId}, #{chatId}, #{name}, #{chatType}, #{identity}, #{enabled},
                 #{verified}, #{verifyMsg}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
                name = VALUES(name),
                chat_type = VALUES(chat_type),
                identity = VALUES(identity),
                enabled = VALUES(enabled),
                verified = VALUES(verified),
                verify_msg = VALUES(verify_msg),
                updated_at = VALUES(updated_at)
            """)
    int upsert(ChatBindingEntity entity);

    @Select("SELECT * FROM chat_binding WHERE enabled = 1 ORDER BY user_id, id")
    List<ChatBindingEntity> selectEnabled();

    @Select("SELECT * FROM chat_binding WHERE user_id = #{userId} ORDER BY id")
    List<ChatBindingEntity> selectByUser(@Param("userId") String userId);

    @Select("SELECT * FROM chat_binding WHERE id = #{id}")
    ChatBindingEntity selectById(@Param("id") Long id);

    @Delete("DELETE FROM chat_binding WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    @Delete("DELETE FROM chat_binding WHERE user_id = #{userId} AND chat_id = #{chatId}")
    int deleteByChat(@Param("userId") String userId, @Param("chatId") String chatId);
}
