package com.ciniam.internradar.controller;

import com.ciniam.internradar.model.SystemSettings;
import com.ciniam.internradar.model.SystemSettingsView;
import com.ciniam.internradar.security.AuthGuard;
import com.ciniam.internradar.service.ConfigProbeService;
import com.ciniam.internradar.service.PreferenceService;
import com.ciniam.internradar.service.SystemConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端级配置：飞书应用、内部任务系统站点、git 兜底仓库
 * 只对导师开放。密钥只回传是否已配置，不回传明文
 */
@RestController
@RequestMapping("/settings/system")
public class SystemConfigController {

    private static final String DOMAIN_FEISHU_APP = "feishu-app";
    private static final String DOMAIN_FEISHU_WEBHOOK = "feishu-webhook";
    private static final String DOMAIN_ZENTAO = "zentao";
    private static final String DOMAIN_GIT = "git";
    private static final String DOMAIN_MODEL = "model";

    private final SystemConfigService systemConfigService;
    private final ConfigProbeService configProbeService;
    private final PreferenceService preferenceService;
    private final AuthGuard authGuard;

    public SystemConfigController(SystemConfigService systemConfigService,
                                  ConfigProbeService configProbeService,
                                  PreferenceService preferenceService,
                                  AuthGuard authGuard) {
        this.systemConfigService = systemConfigService;
        this.configProbeService = configProbeService;
        this.preferenceService = preferenceService;
        this.authGuard = authGuard;
    }

    @GetMapping
    public SystemSettingsView view() {
        authGuard.requireTeamView("服务端配置");
        return systemConfigService.view();
    }

    @PostMapping
    public SystemSettingsView save(@RequestBody SystemSettings settings) {
        authGuard.requireTeamView("服务端配置");
        systemConfigService.save(settings);
        return systemConfigService.view();
    }

    /**
     * 连接测试。候选值优先，留空则用当前生效值，因此可以先测再存
     * 飞书应用与群机器人属于服务端密钥，只对导师开放；仓库与内部系统账号是个人可配项，本人即可测
     */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestParam String domain,
                                    @RequestParam(required = false) String authors,
                                    @RequestBody(required = false) SystemSettings settings) {
        if (DOMAIN_FEISHU_APP.equals(domain) || DOMAIN_FEISHU_WEBHOOK.equals(domain)
                || DOMAIN_MODEL.equals(domain)) {
            authGuard.requireTeamView("服务端密钥配置");
        } else {
            authGuard.current();
        }
        SystemSettingsView current = systemConfigService.view();
        return switch (domain) {
            case DOMAIN_FEISHU_APP -> configProbeService.probeFeishuApp(
                    firstOf(settings == null || settings.feishu() == null ? null : settings.feishu().appId(),
                            current.feishu().appId()),
                    // 密钥留空表示沿用已保存的，因此这里取生效值
                    systemConfigService.feishuAppSecret());
            case DOMAIN_FEISHU_WEBHOOK -> configProbeService.probeFeishuWebhook(
                    firstOf(settings == null || settings.feishu() == null ? null : settings.feishu().webhookUrl(),
                            current.feishu().webhookUrl()),
                    null);
            case DOMAIN_ZENTAO -> {
                SystemSettings.Zentao candidate = settings == null ? null : settings.zentao();
                String baseUrl = firstOf(candidate == null ? null : candidate.baseUrl(), current.zentao().baseUrl());
                String account = firstOf(candidate == null ? null : candidate.account(), current.zentao().account());
                yield configProbeService.probeZentao(baseUrl, account, resolveZentaoPassword(candidate, current));
            }
            case DOMAIN_GIT -> configProbeService.probeGitRepositories(
                    resolveRepositories(settings, current), resolveAuthors(authors));
            case DOMAIN_MODEL -> {
                SystemSettings.Model candidate = settings == null ? null : settings.model();
                yield configProbeService.probeModel(
                        firstOf(candidate == null ? null : candidate.baseUrl(), current.model().baseUrl()),
                        // 密钥留空表示沿用已保存的，因此这里取生效值
                        systemConfigService.modelApiKey(),
                        firstOf(candidate == null ? null : candidate.name(), current.model().name()),
                        candidate == null || candidate.temperature() == null
                                ? current.model().temperature() : candidate.temperature(),
                        candidate == null || candidate.timeoutSeconds() == null
                                ? current.model().timeoutSeconds() : candidate.timeoutSeconds());
            }
            default -> {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("success", false);
                error.put("message", "不支持的测试类型：" + domain);
                yield error;
            }
        };
    }

    /**
     * 扫描根目录下的 git 仓库，供批量勾选
     */
    @GetMapping("/repositories/scan")
    public Map<String, Object> scan(@RequestParam String rootDir) {
        // 个人也可以扫描自己的目录来添加仓库，这里只要求登录
        authGuard.current();
        if (rootDir == null || rootDir.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rootDir 不能为空");
        }
        List<String> repositories = configProbeService.scanRepositories(rootDir);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rootDir", rootDir);
        result.put("repositories", repositories);
        result.put("message", repositories.isEmpty() ? "该目录下没有发现 git 仓库" : "发现 " + repositories.size() + " 个仓库");
        return result;
    }

    /**
     * 探针用的密码：本次填了就用它；只填了别人的账号又没填密码时，用服务账号密码去登没有意义，直接让探针报缺密码
     */
    private String resolveZentaoPassword(SystemSettings.Zentao candidate, SystemSettingsView current) {
        if (candidate != null && candidate.password() != null && !candidate.password().isBlank()) {
            return candidate.password();
        }
        boolean ownAccountGiven = candidate != null && candidate.account() != null
                && !candidate.account().isBlank() && !candidate.account().equals(current.zentao().account());
        return ownAccountGiven ? "" : systemConfigService.zentaoPassword();
    }

    private List<String> resolveRepositories(SystemSettings settings, SystemSettingsView current) {
        if (settings != null && settings.git() != null && settings.git().repositories() != null
                && !settings.git().repositories().isEmpty()) {
            return settings.git().repositories();
        }
        return current.git().repositories();
    }

    /**
     * 作者标识优先取请求里传的，没传则用调用人自己配的
     */
    private List<String> resolveAuthors(String authors) {
        if (authors != null && !authors.isBlank()) {
            return List.of(authors.split("[,，、;；\\s]+"));
        }
        return preferenceService.gitAuthors(authGuard.currentUserId());
    }

    private String firstOf(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }
}
