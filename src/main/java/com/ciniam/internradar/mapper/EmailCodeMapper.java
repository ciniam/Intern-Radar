package com.ciniam.internradar.mapper;

import com.ciniam.internradar.entity.EmailCodeEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface EmailCodeMapper {

    @Insert("""
            INSERT INTO email_code (email, purpose, code_hash, expires_at, used, attempts, created_at)
            VALUES (#{email}, #{purpose}, #{codeHash}, #{expiresAt}, 0, 0, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(EmailCodeEntity entity);

    /**
     * 取最近一条未使用且未过期的验证码
     */
    @Select("""
            SELECT * FROM email_code
            WHERE email = #{email} AND purpose = #{purpose} AND used = 0 AND expires_at > #{now}
            ORDER BY id DESC LIMIT 1
            """)
    EmailCodeEntity selectLatest(@Param("email") String email,
                                 @Param("purpose") String purpose,
                                 @Param("now") LocalDateTime now);

    /**
     * 重发间隔判断：这段时间内是否已经发过
     */
    @Select("""
            SELECT COUNT(*) FROM email_code
            WHERE email = #{email} AND purpose = #{purpose} AND created_at > #{since}
            """)
    int countRecent(@Param("email") String email,
                    @Param("purpose") String purpose,
                    @Param("since") LocalDateTime since);

    @Update("UPDATE email_code SET used = 1 WHERE id = #{id}")
    int markUsed(@Param("id") Long id);

    @Update("UPDATE email_code SET attempts = attempts + 1 WHERE id = #{id}")
    int increaseAttempts(@Param("id") Long id);

    /**
     * 作废旧验证码，重置成功时清理同邮箱其余未使用记录
     */
    @Update("UPDATE email_code SET used = 1 WHERE email = #{email} AND purpose = #{purpose} AND used = 0")
    int invalidateAll(@Param("email") String email, @Param("purpose") String purpose);
}
