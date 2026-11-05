package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.FeishuAuthEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FeishuAuthMapper {

    @Insert("""
            INSERT INTO feishu_auth
                (team_id, user_id, open_id, access_token, refresh_token, scope, expire_at, updated_at)
            VALUES
                (#{teamId}, #{userId}, #{openId}, #{accessToken}, #{refreshToken}, #{scope}, #{expireAt}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
                open_id = VALUES(open_id),
                access_token = VALUES(access_token),
                refresh_token = VALUES(refresh_token),
                scope = VALUES(scope),
                expire_at = VALUES(expire_at),
                updated_at = VALUES(updated_at)
            """)
    int upsert(FeishuAuthEntity entity);

    @Select("SELECT * FROM feishu_auth WHERE user_id = #{userId}")
    FeishuAuthEntity selectByUser(@Param("userId") String userId);
}
