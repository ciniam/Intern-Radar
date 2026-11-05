package com.ciniam.internradar.controller;

import com.ciniam.internradar.feishu.FeishuApiException;
import com.ciniam.internradar.security.ForbiddenException;
import com.ciniam.internradar.security.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把可预期的业务异常转成结构化响应
 * 否则飞书权限不足这类问题会以 500 加整段堆栈的形式返回，无法直接定位
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(FeishuApiException.class)
    public ResponseEntity<Map<String, Object>> handleFeishu(FeishuApiException e) {
        log.warn("飞书接口调用失败：{}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", e.getMessage());
        body.put("hint", "请确认对应权限已在开放平台申请并【发布版本】；涉及用户身份权限时还需重新完成授权");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorized(UnauthorizedException e) {
        log.warn("未通过登录校验：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorBody(e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ForbiddenException e) {
        log.warn("越权访问被拦截：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody(e.getMessage()));
    }

    /**
     * 参数与校验类错误按 400 返回，前端可以直接把 error 展示给用户
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("请求参数不合法：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        log.warn("业务执行失败：{}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", e.getMessage());
        Throwable cause = e.getCause();
        if (cause != null) {
            body.put("cause", cause.getMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    private Map<String, Object> errorBody(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", message);
        return body;
    }
}
