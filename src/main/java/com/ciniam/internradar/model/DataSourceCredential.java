package com.ciniam.internradar.model;

/**
 * 内部任务系统采集用的凭据。个人配置优先，没有则回退到服务账号
 */
public record DataSourceCredential(String account, String password, boolean allowLogin, String origin) {

    public boolean usable() {
        return account != null && !account.isBlank() && password != null && !password.isBlank();
    }
}
