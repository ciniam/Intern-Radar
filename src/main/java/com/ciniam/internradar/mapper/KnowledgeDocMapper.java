package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.KnowledgeDocEntity;
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
public interface KnowledgeDocMapper {

    /**
     * content_ref 唯一，文件重扫时按路径覆盖元数据，不会产生重复文档
     */
    @Insert("""
            INSERT INTO knowledge_doc
                (team_id, title, category, tags, source, visibility, content_ref, summary, enabled, updated_at)
            VALUES
                (#{teamId}, #{title}, #{category}, #{tags}, #{source}, #{visibility},
                 #{contentRef}, #{summary}, #{enabled}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
                title = VALUES(title),
                category = VALUES(category),
                tags = VALUES(tags),
                source = VALUES(source),
                visibility = VALUES(visibility),
                summary = VALUES(summary),
                enabled = VALUES(enabled),
                updated_at = VALUES(updated_at)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsert(KnowledgeDocEntity entity);

    @Select("SELECT * FROM knowledge_doc WHERE team_id = #{teamId} AND enabled = 1 ORDER BY category, id")
    List<KnowledgeDocEntity> selectEnabled(@Param("teamId") long teamId);

    @Select("SELECT * FROM knowledge_doc WHERE id = #{id}")
    KnowledgeDocEntity selectById(@Param("id") Long id);

    @Select("SELECT * FROM knowledge_doc WHERE team_id = #{teamId} AND content_ref = #{contentRef} LIMIT 1")
    KnowledgeDocEntity selectByRef(@Param("teamId") long teamId, @Param("contentRef") String contentRef);

    @Delete("DELETE FROM knowledge_doc WHERE team_id = #{teamId} AND content_ref = #{contentRef}")
    int deleteByRef(@Param("teamId") long teamId, @Param("contentRef") String contentRef);

    /**
     * 单独写上传归属，目录扫描的 upsert 不涉及这两列，因此不会被重扫覆盖
     */
    @Update("""
            UPDATE knowledge_doc
            SET uploader_user_id = #{uploaderUserId}, uploaded_at = #{uploadedAt}
            WHERE team_id = #{teamId} AND content_ref = #{contentRef}
            """)
    int updateUploader(@Param("teamId") long teamId,
                       @Param("contentRef") String contentRef,
                       @Param("uploaderUserId") String uploaderUserId,
                       @Param("uploadedAt") LocalDateTime uploadedAt);
}
