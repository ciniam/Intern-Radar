package com.ciniam.internradar.controller;

import com.ciniam.internradar.adapter.zentao.ZentaoDataSource;
import com.ciniam.internradar.security.AuthGuard;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部系统排障接口。raw 用于确认响应字段，避免靠猜字段名写解析逻辑
 * 只对导师开放，userId 缺省时用当前登录人的凭据
 */
@RestController
@RequestMapping("/zentao")
public class ZentaoController {

    private final ZentaoDataSource zentaoDataSource;
    private final AuthGuard authGuard;

    public ZentaoController(ZentaoDataSource zentaoDataSource, AuthGuard authGuard) {
        this.zentaoDataSource = zentaoDataSource;
        this.authGuard = authGuard;
    }

    @GetMapping(value = "/raw", produces = MediaType.TEXT_PLAIN_VALUE)
    public String raw(@RequestParam String path,
                      @RequestParam(required = false) String userId) {
        return zentaoDataSource.raw(path, authGuard.resolveUserId(userId));
    }

    @GetMapping(value = "/session", produces = MediaType.TEXT_PLAIN_VALUE)
    public String session(@RequestParam(required = false) String userId) {
        return zentaoDataSource.sessionStatus(authGuard.resolveUserId(userId));
    }
}
