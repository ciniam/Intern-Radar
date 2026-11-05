package com.ciniam.internradar.push;

/**
 * 推送结果
 */
public record PushResult(boolean success, String errorMessage) {

    public static PushResult ok() {
        return new PushResult(true, null);
    }

    public static PushResult fail(String errorMessage) {
        return new PushResult(false, errorMessage);
    }
}
