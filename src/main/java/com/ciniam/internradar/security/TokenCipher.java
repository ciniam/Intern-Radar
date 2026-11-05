package com.ciniam.internradar.security;

import com.ciniam.internradar.config.InternRadarProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 令牌加解密，用 AES/GCM，密钥由配置项派生
 * 明文令牌不落库，避免数据库泄露直接导致授权泄漏
 */
@Component
public class TokenCipher {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BIT = 128;
    private static final int KEY_LENGTH = 16;

    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    public TokenCipher(InternRadarProperties properties) {
        this.keySpec = deriveKey(properties.getSecurity().getTokenKey());
    }

    public String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("令牌加密失败", e);
        }
    }

    public String decrypt(String cipherText) {
        if (cipherText == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] decrypted = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("令牌解密失败，请确认 token-key 未被改动", e);
        }
    }

    private SecretKeySpec deriveKey(String source) {
        String material = source == null || source.isBlank() ? "intern-radar-default-token-key" : source;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            byte[] key = new byte[KEY_LENGTH];
            System.arraycopy(digest, 0, key, 0, KEY_LENGTH);
            return new SecretKeySpec(key, ALGORITHM);
        } catch (Exception e) {
            throw new IllegalStateException("令牌密钥派生失败", e);
        }
    }
}
