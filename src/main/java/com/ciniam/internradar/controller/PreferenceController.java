package com.ciniam.internradar.controller;

import com.ciniam.internradar.model.DataSourceStatus;
import com.ciniam.internradar.model.OnboardingView;
import com.ciniam.internradar.model.TeamSettings;
import com.ciniam.internradar.model.UserPreference;
import com.ciniam.internradar.security.AuthGuard;
import com.ciniam.internradar.security.ForbiddenException;
import com.ciniam.internradar.service.DataSourceStatusService;
import com.ciniam.internradar.service.PreferenceService;
import com.ciniam.internradar.service.TeamSettingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 偏好与设置接口。个人偏好一律读写自己的那份，导师也不能代改他人，团队级设置只对导师开放
 */
@RestController
@RequestMapping("/settings")
public class PreferenceController {

    private final PreferenceService preferenceService;
    private final TeamSettingService teamSettingService;
    private final DataSourceStatusService dataSourceStatusService;
    private final AuthGuard authGuard;

    public PreferenceController(PreferenceService preferenceService,
                                TeamSettingService teamSettingService,
                                DataSourceStatusService dataSourceStatusService,
                                AuthGuard authGuard) {
        this.preferenceService = preferenceService;
        this.teamSettingService = teamSettingService;
        this.dataSourceStatusService = dataSourceStatusService;
        this.authGuard = authGuard;
    }

    @GetMapping("/preferences")
    public Map<String, Object> preferences() {
        return view(authGuard.currentUserId());
    }

    /**
     * 保存偏好。只接受登录人自己的配置，带了别人的标识直接拒绝
     * 密码字段约定：不带表示不改动，空串表示清除
     */
    @PostMapping("/preferences")
    public Map<String, Object> save(@RequestBody UserPreference preference) {
        String target = preference.userId() == null || preference.userId().isBlank()
                ? authGuard.currentUserId() : preference.userId();
        if (!authGuard.isSelf(target)) {
            throw new ForbiddenException("只能修改自己的配置");
        }
        preferenceService.save(preference.withUserId(target));
        return view(target);
    }

    @GetMapping("/datasources")
    public List<DataSourceStatus> dataSources() {
        return dataSourceStatusService.list(authGuard.currentUserId());
    }

    /**
     * 个人配置引导，供今日页提示还差哪几项
     */
    @GetMapping("/onboarding")
    public OnboardingView onboarding() {
        return preferenceService.onboarding(authGuard.currentUserId());
    }

    @GetMapping("/team")
    public TeamSettings team() {
        authGuard.requireTeamView("团队级设置");
        return teamSettingService.current();
    }

    @PostMapping("/team")
    public TeamSettings saveTeam(@RequestBody TeamSettings settings) {
        authGuard.requireTeamView("团队级设置");
        return teamSettingService.save(settings);
    }

    @GetMapping("/options")
    public Map<String, Object> options() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("riskTypes", List.of("NO_OUTPUT", "TASK_STALL", "DEADLINE_RISK", "TREND_ANOMALY", "PITFALL"));
        result.put("riskLevels", List.of("LOW", "MEDIUM", "HIGH"));
        result.put("pushChannels", List.of("USER", "GROUP", "BOTH"));
        result.put("reportStyles", List.of("DETAIL", "COMPACT"));
        result.put("answerStyles", List.of("STANDARD", "CONCISE", "THOROUGH"));
        result.put("weeklyScopes", List.of("WATCHED", "ALL"));
        result.put("weekdays", List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"));
        return result;
    }

    private Map<String, Object> view(String userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("preference", preferenceService.get(userId));
        result.put("zentaoPasswordSet", preferenceService.passwordSet(userId));
        return result;
    }
}
