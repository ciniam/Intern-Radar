package com.ciniam.internradar.model;

/**
 * 账号状态。与启用位分开：启用位表达能不能登录，这里表达有没有经过导师审批
 * 待审批只是标记，权限与实习生相同，审批由导师改角色完成
 */
public enum UserStatus {

    ACTIVE,
    PENDING;

    public static UserStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return ACTIVE;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ACTIVE;
        }
    }

    public boolean pending() {
        return this == PENDING;
    }
}
