package com.ciniam.internradar.adapter.zentao;

import com.ciniam.internradar.config.InternRadarProperties;
import com.ciniam.internradar.service.SystemConfigService;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 禅道老世代接口的 HTTP 客户端
 * 会话靠 Cookie 维持，必须使用带 CookieManager 的客户端，手工拼请求头无法维持登录态
 * 内网自签证书场景按配置跳过校验
 */
@Component
public class ZentaoHttpClient {

    private final InternRadarProperties properties;
    private final SystemConfigService systemConfig;
    private final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

    private volatile HttpClient httpClient;
    private volatile boolean clientSkipTls;
    private volatile String baseUrlOverride;

    public ZentaoHttpClient(InternRadarProperties properties, SystemConfigService systemConfig) {
        this.properties = properties;
        this.systemConfig = systemConfig;
    }

    /**
     * 临时指定站点地址，供配置页的登录探针使用，不走生效配置
     */
    public void overrideBaseUrl(String baseUrl) {
        this.baseUrlOverride = baseUrl;
    }

    public String baseUrl() {
        String base = baseUrlOverride != null && !baseUrlOverride.isBlank()
                ? baseUrlOverride : systemConfig.zentaoBaseUrl();
        if (base == null) {
            return "";
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * 跳过证书校验是界面可改项，改动后重建客户端，避免一直用旧设置
     */
    private HttpClient client() {
        boolean skip = systemConfig.zentaoSkipTlsVerify();
        HttpClient local = httpClient;
        if (local != null && skip == clientSkipTls) {
            return local;
        }
        synchronized (this) {
            if (httpClient != null && skip == clientSkipTls) {
                return httpClient;
            }
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .cookieHandler(cookieManager)
                    .connectTimeout(Duration.ofSeconds(properties.getZentao().getTimeoutSeconds()))
                    .followRedirects(HttpClient.Redirect.NORMAL);
            if (skip) {
                builder.sslContext(trustAllContext());
                SSLParameters parameters = new SSLParameters();
                parameters.setEndpointIdentificationAlgorithm("");
                builder.sslParameters(parameters);
            }
            HttpClient built = builder.build();
            this.clientSkipTls = skip;
            this.httpClient = built;
            return built;
        }
    }

    public Response get(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(properties.getZentao().getTimeoutSeconds()))
                .header("Accept", "application/json")
                .GET()
                .build();
        return send(request);
    }

    public Response postForm(String path, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(properties.getZentao().getTimeoutSeconds()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    public void clearCookies() {
        cookieManager.getCookieStore().removeAll();
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = client().send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        } catch (Exception e) {
            return new Response(-1, "请求失败：" + e.getMessage());
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private SSLContext trustAllContext() {
        try {
            TrustManager[] trustManagers = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers, new SecureRandom());
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("初始化 TLS 上下文失败", e);
        }
    }

    public record Response(int status, String body) {

        public boolean reachable() {
            return status > 0;
        }
    }
}
