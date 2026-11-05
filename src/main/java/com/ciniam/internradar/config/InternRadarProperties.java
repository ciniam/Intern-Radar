package com.ciniam.internradar.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 实习雷达业务配置，对应 application.yml 里的 intern-radar 节点
 */
@Data
@ConfigurationProperties(prefix = "intern-radar")
public class InternRadarProperties {

    private List<Intern> interns = new ArrayList<>();
    private List<Mentor> mentors = new ArrayList<>();
    private Git git = new Git();
    private Feishu feishu = new Feishu();
    private Zentao zentao = new Zentao();
    private Risk risk = new Risk();
    private Scheduler scheduler = new Scheduler();
    private Security security = new Security();
    private Trend trend = new Trend();
    private Knowledge knowledge = new Knowledge();
    private Auth auth = new Auth();
    private Mail mail = new Mail();
    private Model model = new Model();
    private Search search = new Search();

    @Data
    public static class Intern {

        private String userId;
        private String name;
        private String email;
        /**
         * 仅用于首次初始化账号，写入后即改由库里的哈希校验；登录后强制改密
         */
        private String password;
        private String feishuOpenId;
        private String mentorUserId;
        private List<String> gitAuthors = new ArrayList<>();
    }

    @Data
    public static class Mentor {

        private String userId;
        private String name;
        private String email;
        private String password;
        private String feishuOpenId;
    }

    @Data
    public static class Git {

        private String executable = "git";
        private int timeoutSeconds = 30;
        private int maxCommits = 200;
        private List<String> repositories = new ArrayList<>();
    }

    @Data
    public static class Feishu {

        private String webhookUrl;
        private int timeoutSeconds = 10;
        private String appId;
        private String appSecret;
        /**
         * 聊天采集授权用的回调地址，与登录回调必须分开，两者的处理逻辑不同
         */
        private String oauthRedirectUri = "http://localhost:8123/api/feishu/oauth/callback";
        /**
         * 登录用的回调地址，回调页会把令牌回传给登录窗口并自动关闭
         */
        private String loginRedirectUri = "http://localhost:8123/api/auth/feishu/callback";
        /**
         * 授权时申请的权限，必须先在开放平台申请过，否则授权页会报 20027
         * offline_access 用于换取 refresh_token
         */
        private String oauthScope = "im:message:readonly im:chat:readonly "
                + "im:message.p2p_msg:get_as_user offline_access";
        /**
         * 首次接入某个会话时向前回补的天数，之后按游标增量采集
         */
        private int chatLookbackDays = 30;
        private List<ChatTarget> chats = new ArrayList<>();
    }

    /**
     * 白名单会话，只采这里列出的会话，且正文不落库只保留摘要
     */
    @Data
    public static class ChatTarget {

        private String chatId;
        private String name;
        private String type = "GROUP";
        private String userId;
        /**
         * 调用身份。APP 用应用身份，适合机器人所在的群与机器人与用户的单聊，无需任何人授权；
         * USER 用用户身份，需要该用户完成一次 OAuth 授权
         */
        private String identity = "APP";
        private boolean enabled = true;
    }

    @Data
    public static class Zentao {

        private String baseUrl;
        private String account;
        private String password;
        private String apiPrefix;
        private boolean allowLogin = false;
        private boolean skipTlsVerify = true;
        private int timeoutSeconds = 15;
        private int sessionTtlMinutes = 20;
        private int maxConsecutiveFailures = 3;
        private int maxPages = 5;
    }

    @Data
    public static class Risk {

        private int noOutputDays = 2;
        private int taskStallDays = 3;
        private int deadlineDays = 2;
        private int deadlineProgress = 50;
        private int alertThrottleHours = 6;
    }

    /**
     * 早晚推送按窗口扫描：每个人在自己配置的时间点被推送，job_run 保证当天只推一次
     */
    @Data
    public static class Scheduler {

        private boolean enabled = false;
        private boolean morningEnabled = true;
        private String morningScanCron = "0 */5 6-23 * * ?";
        private boolean eveningEnabled = true;
        private String eveningScanCron = "0 */5 6-23 * * ?";
        private boolean weeklyEnabled = true;
        private String weeklyScanCron = "0 */5 6-23 * * ?";
        private boolean riskEnabled = true;
        private String riskCheckCron = "0 0 19 * * ?";
    }

    @Data
    public static class Security {

        private String tokenKey;
    }

    /**
     * Python 趋势统计服务地址，为空时跳过远程统计
     */
    @Data
    public static class Trend {

        private String baseUrl;
        private int timeoutSeconds = 10;
    }

    /**
     * 知识库。文档与坑位以文件为准，docs 目录存知识文档，pitfalls 目录存坑位记录
     */
    @Data
    public static class Knowledge {

        private String rootDir = "knowledge";
        private boolean syncOnStartup = true;
        private int maxReadChars = 6000;
        private int searchTopK = 8;
    }

    /**
     * 登录相关。devLogin 是本地调试用的身份直选，接入真实团队时必须关闭
     */
    @Data
    public static class Auth {

        private boolean devLoginEnabled = true;
        private int sessionHours = 72;
        private int pendingMinutes = 10;
        /**
         * 密码最短长度，同时要求含大写、小写与数字
         */
        private int passwordMinLength = 8;
        /**
         * 连续失败达到这个次数后锁定
         */
        private int maxFailedAttempts = 5;
        private int lockMinutes = 15;
        /**
         * 邮箱验证码有效期与重发间隔
         */
        private int codeExpireMinutes = 10;
        private int codeResendSeconds = 60;
        /**
         * 同一来源每小时允许注册的次数
         */
        private int registerPerHour = 10;
    }

    /**
     * 发信邮箱。留空表示不启用自助找回密码，此时只能由管理员重置
     */
    @Data
    public static class Mail {

        private String host;
        private int port = 465;
        private String username;
        private String password;
        private String from;
        /**
         * true 走隐式 SSL（465），false 时可选择 STARTTLS（587）
         */
        private boolean ssl = true;
        private boolean starttls = false;
        private int timeoutSeconds = 15;
    }

    /**
     * 联网搜索。留空表示不启用，学习问答里不会注册联网搜索工具
     * 配置只在这里兜底，界面改过后以库中的值为准
     */
    @Data
    public static class Search {

        private String apiKey;
        private String baseUrl = "https://api.firecrawl.dev/v2";
        private int timeoutSeconds = 20;
        /**
         * 单次检索返回条数，条数越多上下文越长
         */
        private int limit = 5;
    }

    /**
     * 模型接入。走 OpenAI 兼容协议，换平台只改这一组值
     * 配置只在这里兜底，界面改过后以库中的值为准
     */
    @Data
    public static class Model {

        private String baseUrl = "https://api.deepseek.com";
        private String apiKey;
        private String name = "deepseek-chat";
        private Double temperature = 0.7;
        private int timeoutSeconds = 60;
    }
}
