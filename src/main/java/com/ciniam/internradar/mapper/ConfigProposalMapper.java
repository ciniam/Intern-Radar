package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.ConfigProposalEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ConfigProposalMapper {

    @Insert("""
            INSERT INTO config_proposal
                (team_id, user_id, conversation_id, field_key, field_label, field_value,
                 reason, status, created_at)
            VALUES
                (#{teamId}, #{userId}, #{conversationId}, #{fieldKey}, #{fieldLabel}, #{fieldValue},
                 #{reason}, #{status}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ConfigProposalEntity entity);

    @Select("SELECT * FROM config_proposal WHERE id = #{id}")
    ConfigProposalEntity selectById(@Param("id") Long id);

    @Select("""
            SELECT * FROM config_proposal
            WHERE user_id = #{userId} AND status = 'PENDING'
            ORDER BY id DESC LIMIT #{limit}
            """)
    List<ConfigProposalEntity> selectPending(@Param("userId") String userId, @Param("limit") int limit);

    @Select("""
            SELECT * FROM config_proposal
            WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND status = 'PENDING'
            ORDER BY id
            """)
    List<ConfigProposalEntity> selectPendingByConversation(@Param("conversationId") Long conversationId,
                                                          @Param("userId") String userId);

    @Update("UPDATE config_proposal SET status = #{status}, handled_at = #{handledAt} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("handledAt") LocalDateTime handledAt);
}
