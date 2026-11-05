package com.ciniam.internradar.controller;

import com.ciniam.internradar.push.PushResult;
import com.ciniam.internradar.push.PushService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 推送测试接口，用于单独验证群 Webhook 与个人私发是否配置正确
 */
@RestController
@RequestMapping("/push")
public class PushController {

    private final PushService pushService;

    public PushController(PushService pushService) {
        this.pushService = pushService;
    }

    @PostMapping("/test")
    public PushResult test(
            @RequestParam(required = false) String text,
            @RequestParam(defaultValue = "group") String channel,
            @RequestParam(required = false) String userId) {
        String content = text == null || text.isBlank() ? "实习雷达推送测试" : text;
        if ("user".equalsIgnoreCase(channel)) {
            return pushService.sendToUser(userId == null || userId.isBlank() ? "test" : userId, "TEST", content);
        }
        return pushService.sendToGroup("test", "TEST", content);
    }
}
