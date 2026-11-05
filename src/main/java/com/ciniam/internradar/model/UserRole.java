package com.ciniam.internradar.model;

/**
 * 系统角色。实习生只能操作自己的数据，导师与管理员可查看团队数据
 */
public enum UserRole {

    INTERN,
    MENTOR,
    ADMIN;

    public static UserRole parse(String value) {
        if (value == null || value.isBlank()) {
            return INTERN;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return INTERN;
        }
    }

    public boolean canViewTeam() {
        return this == MENTOR || this == ADMIN;
    }
}
