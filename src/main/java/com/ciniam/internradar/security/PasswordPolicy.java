package com.ciniam.internradar.security;

import java.util.Locale;

/**
 * 口令强度校验。要求长度达标，且同时含大写、小写与数字
 */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    /**
     * 校验不通过时抛异常，消息直接面向用户
     */
    public static void validate(String raw, int minLength, String email) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("请填写密码");
        }
        if (raw.length() < minLength) {
            throw new IllegalArgumentException("密码至少 " + minLength + " 位");
        }
        if (raw.length() > 64) {
            throw new IllegalArgumentException("密码不要超过 64 位");
        }
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        for (char ch : raw.toCharArray()) {
            if (Character.isUpperCase(ch)) {
                upper = true;
            } else if (Character.isLowerCase(ch)) {
                lower = true;
            } else if (Character.isDigit(ch)) {
                digit = true;
            }
        }
        if (!upper || !lower || !digit) {
            throw new IllegalArgumentException("密码需要同时包含大写字母、小写字母与数字");
        }
        if (raw.contains(" ")) {
            throw new IllegalArgumentException("密码不能包含空格");
        }
        if (email != null && !email.isBlank()) {
            String local = email.trim().toLowerCase(Locale.ROOT).split("@")[0];
            if (local.length() >= 4 && raw.toLowerCase(Locale.ROOT).contains(local)) {
                throw new IllegalArgumentException("密码不要包含邮箱账号");
            }
        }
    }
}
