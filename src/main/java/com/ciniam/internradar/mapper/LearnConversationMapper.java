package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.LearnConversationEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface LearnConversationMapper {

    @Insert("""
            INSERT INTO learn_conversation (team_id, user_id, title, created_at, updated_at)
            VALUES (#{teamId}, #{userId}, #{title}, #{createdAt}, #{updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(LearnConversationEntity entity);

    @Select("SELECT * FROM learn_conversation WHERE id = #{id}")
    LearnConversationEntity selectById(@Param("id") Long id);

    /**
     * 按用户取会话，keyword 为空时返回全部，否则按标题模糊匹配
     */
    @Select("""
            <script>
            SELECT * FROM learn_conversation
            WHERE user_id = #{userId}
            <if test="keyword != null and keyword != ''">
                AND title LIKE CONCAT('%', #{keyword}, '%')
            </if>
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<LearnConversationEntity> selectByUser(@Param("userId") String userId,
                                               @Param("keyword") String keyword,
                                               @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM learn_conversation WHERE user_id = #{userId}")
    int countByUser(@Param("userId") String userId);

    @Update("UPDATE learn_conversation SET title = #{title}, updated_at = #{updatedAt} WHERE id = #{id}")
    int updateTitle(@Param("id") Long id, @Param("title") String title,
                    @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE learn_conversation SET updated_at = #{updatedAt} WHERE id = #{id}")
    int touch(@Param("id") Long id, @Param("updatedAt") LocalDateTime updatedAt);

    @Delete("DELETE FROM learn_conversation WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
