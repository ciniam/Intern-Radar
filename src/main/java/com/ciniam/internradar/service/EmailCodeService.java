package com.ciniam.internradar.service;

import com.ciniam.internradar.config.InternRadarProperties;
import com.ciniam.internradar.entity.EmailCodeEntity;
import com.ciniam.internradar.mail.SmtpMailSender;
import com.ciniam.internradar.mapper.EmailCodeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 邮箱验证码。只存摘要不存明文，一次使用，限制重发间隔与错误次数
 */
@Service
public class EmailCodeService {

    private static final Logger log = LoggerFactory.getLogger(EmailCodeService.class);

    public static final String PURPOSE_RESET = "RESET_PASSWORD";

    private static final int CODE_BOUND = 1_000_000;
    private static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailCodeMapper codeMapper;
    private final SmtpMailSender mailSender;
    private final InternRadarProperties properties;

    public EmailCodeService(EmailCodeMapper codeMapper, SmtpMailSender mailSender,
                            InternRadarProperties properties) {
        this.codeMapper = codeMapper;
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public boolean mailReady() {
        return mailSender.configured();
    }

    /**
     * 发验证码。同一邮箱按重发间隔限流，防止被刷
     */
    public void sendResetCode(String email) {
        if (!mailSender.configured()) {
            throw new IllegalStateException("还没有配置发信邮箱，暂时无法自助重置密码");
        }
        int resendSeconds = Math.max(properties.getAuth().getCodeResendSeconds(), 10);
        LocalDateTime now = LocalDateTime.now();
        if (codeMapper.countRecent(email, PURPOSE_RESET, now.minusSeconds(resendSeconds)) > 0) {
            throw new IllegalStateException("验证码刚发过，请 " + resendSeconds + " 秒后再试");
        }
        String code = randomCode();
        int expireMinutes = Math.max(properties.getAuth().getCodeExpireMinutes(), 1);
        codeMapper.insert(EmailCodeEntity.builder()
                .email(email)
                .purpose(PURPOSE_RESET)
                .codeHash(digest(email, code))
                .expiresAt(now.plusMinutes(expireMinutes))
                .used(false)
                .attempts(0)
                .createdAt(now)
                .build());
        mailSender.send(email, "实习雷达 - 重置密码验证码", body(code, expireMinutes));
        log.info("已发送重置密码验证码，收件人 {}", email);
    }

    /**
     * 校验验证码，通过即作废。失败累计错误次数，超过上限要求重新获取
     */
    public void verifyResetCode(String email, String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("请填写验证码");
        }
        EmailCodeEntity entity = codeMapper.selectLatest(email, PURPOSE_RESET, LocalDateTime.now());
        if (entity == null) {
            throw new IllegalArgumentException("验证码已过期，请重新获取");
        }
        if (entity.getAttempts() != null && entity.getAttempts() >= MAX_ATTEMPTS) {
            throw new IllegalArgumentException("验证码错误次数过多，请重新获取");
        }
        if (!MessageDigest.isEqual(digest(email, code.trim()).getBytes(StandardCharsets.UTF_8),
                entity.getCodeHash().getBytes(StandardCharsets.UTF_8))) {
            codeMapper.increaseAttempts(entity.getId());
            throw new IllegalArgumentException("验证码不正确");
        }
        codeMapper.markUsed(entity.getId());
    }

    public void invalidate(String email) {
        codeMapper.invalidateAll(email, PURPOSE_RESET);
    }

    private String randomCode() {
        return String.format("%06d", RANDOM.nextInt(CODE_BOUND));
    }

    /**
     * 摘要绑定邮箱，避免不同邮箱之间串用
     */
    private String digest(String email, String code) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hashed = sha256.digest((email + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (Exception e) {
            throw new IllegalStateException("验证码摘要失败", e);
        }
    }

    private String body(String code, int expireMinutes) {
        return """
                <div style="font-family: sans-serif; font-size: 14px; color: #222;">
                  <p>你正在重置实习雷达的登录密码，验证码为：</p>
                  <p style="font-size: 26px; letter-spacing: 6px; font-weight: 600;">%s</p>
                  <p>验证码 %d 分钟内有效，仅可使用一次。若不是你本人操作，请忽略这封邮件。</p>
                </div>
                """.formatted(code, expireMinutes);
    }
}
