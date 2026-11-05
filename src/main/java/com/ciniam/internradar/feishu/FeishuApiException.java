package com.ciniam.internradar.feishu;

/**
 * 飞书接口异常，携带可用于排障的原始信息
 */
public class FeishuApiException extends RuntimeException {

    public FeishuApiException(String message) {
        super(message);
    }

    public FeishuApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
