package com.ciniam.internradar.mail;

import com.ciniam.internradar.service.SystemConfigService;
import org.springframework.stereotype.Component;

/**
 * 发信参数。库里的服务端配置优先，配置文件只作兜底
 */
@Component
public class MailSettings {

    private final SystemConfigService systemConfig;

    public MailSettings(SystemConfigService systemConfig) {
        this.systemConfig = systemConfig;
    }

    public boolean configured() {
        return notBlank(host()) && notBlank(from());
    }

    public String host() {
        return systemConfig.mailHost();
    }

    public int port() {
        return systemConfig.mailPort();
    }

    public String username() {
        return systemConfig.mailUsername();
    }

    public String password() {
        return systemConfig.mailPassword();
    }

    public String from() {
        return systemConfig.mailFrom();
    }

    public boolean ssl() {
        return systemConfig.mailSsl();
    }

    public boolean starttls() {
        return systemConfig.mailStarttls();
    }

    public int timeoutSeconds() {
        return systemConfig.mailTimeoutSeconds();
    }

    /**
     * EHLO 用的主机名，取发件地址的域名，避免被对方按主机名拒收
     */
    public String heloName() {
        String from = from();
        if (notBlank(from) && from.contains("@")) {
            return from.substring(from.indexOf('@') + 1);
        }
        return "localhost";
    }

    private boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }
}
