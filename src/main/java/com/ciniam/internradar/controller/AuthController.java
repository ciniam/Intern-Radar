package com.ciniam.internradar.controller;

import com.ciniam.internradar.model.LoginDevice;
import com.ciniam.internradar.model.LoginResult;
import com.ciniam.internradar.model.SysUser;
import com.ciniam.internradar.security.AuthContext;
import com.ciniam.internradar.security.AuthGuard;
import com.ciniam.internradar.security.AuthInterceptor;
import com.ciniam.internradar.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 认证入口。正式使用邮箱加密码，身份直选与飞书授权仅用于本地调试与绑定
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final String USER_AGENT_HEADER = "User-Agent";

    private final AuthService authService;
    private final AuthGuard authGuard;

    public AuthController(AuthService authService, AuthGuard authGuard) {
        this.authService = authService;
        this.authGuard = authGuard;
    }

    /**
     * 登录页的可选身份，只有开着身份直选时才返回
     */
    @GetMapping("/users")
    public Map<String, Object> users() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<SysUser> candidates = authService.loginCandidates();
        result.put("devLoginEnabled", authService.devLoginEnabled());
        result.put("users", candidates);
        return result;
    }

    @PostMapping("/register")
    public LoginResult register(@RequestBody RegisterPayload payload, HttpServletRequest request) {
        return authService.register(payload == null ? null : payload.email(),
                payload == null ? null : payload.password(),
                payload == null ? null : payload.name(),
                deviceOf(request));
    }

    @PostMapping("/login")
    public LoginResult login(@RequestBody LoginPayload payload, HttpServletRequest request) {
        return authService.login(payload == null ? null : payload.email(),
                payload == null ? null : payload.password(),
                deviceOf(request));
    }

    /**
     * 本地调试用：直接按用户标识登录，接入真实用户前必须关闭
     */
    @PostMapping("/dev-login")
    public LoginResult devLogin(@RequestParam String userId, HttpServletRequest request) {
        return authService.loginAs(userId, deviceOf(request));
    }

    /**
     * 发送重置密码验证码。无论邮箱是否存在都返回成功
     */
    @PostMapping("/password/forgot")
    public Map<String, Object> forgot(@RequestBody ForgotPayload payload) {
        authService.sendResetCode(payload == null ? null : payload.email());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "如果这个邮箱已注册，验证码已发出，请查收");
        return result;
    }

    @PostMapping("/password/reset")
    public Map<String, Object> reset(@RequestBody ResetPayload payload) {
        authService.resetPassword(payload == null ? null : payload.email(),
                payload == null ? null : payload.code(),
                payload == null ? null : payload.password());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "密码已重置，请用新密码登录");
        return result;
    }

    /**
     * 飞书授权地址。用于绑定飞书账号，回调页把结果回传给打开它的窗口
     */
    @GetMapping(value = "/feishu/url", produces = MediaType.TEXT_PLAIN_VALUE)
    public String feishuUrl(HttpServletRequest request) {
        return authService.feishuLoginUrl(deviceOf(request));
    }

    @GetMapping(value = "/feishu/callback", produces = MediaType.TEXT_HTML_VALUE)
    public String feishuCallback(@RequestParam(required = false) String code,
                                 @RequestParam(required = false) String state,
                                 @RequestParam(required = false) String error) {
        if (error != null && !error.isBlank()) {
            return authService.handleFeishuCallback(null, null);
        }
        return authService.handleFeishuCallback(code, state);
    }

    @GetMapping("/me")
    public SysUser me() {
        return authGuard.current();
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = AuthInterceptor.TOKEN_HEADER, required = false) String token) {
        authService.logout(token == null ? null : token);
        AuthContext.clear();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return result;
    }

    /**
     * 登录设备取浏览器标识与来源地址，用于个人中心的设备列表
     */
    private LoginDevice deviceOf(HttpServletRequest request) {
        return LoginDevice.of(request.getHeader(USER_AGENT_HEADER), request.getRemoteAddr());
    }

    public record RegisterPayload(String email, String password, String name) {
    }

    public record LoginPayload(String email, String password) {
    }

    public record ForgotPayload(String email) {
    }

    public record ResetPayload(String email, String code, String password) {
    }
}
