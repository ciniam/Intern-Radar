-- 实习雷达业务表结构
-- 全部使用 IF NOT EXISTS，应用每次启动执行都不会破坏已有数据

-- 团队即租户。成员通过邀请码申请加入，由团队创建人审批
CREATE TABLE IF NOT EXISTS `team`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `name`          VARCHAR(64)     NOT NULL,
    `invite_code`   VARCHAR(16)     NOT NULL,
    `owner_user_id` VARCHAR(64)              DEFAULT NULL,
    `description`   VARCHAR(256)             DEFAULT NULL,
    `enabled`       TINYINT(1)      NOT NULL DEFAULT 1,
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_team_invite_code` (`invite_code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 加入团队申请。同一人同一团队同时只留一条待审记录
CREATE TABLE IF NOT EXISTS `team_join_request`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`     BIGINT          NOT NULL,
    `user_id`     VARCHAR(64)     NOT NULL,
    `status`      VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    `message`     VARCHAR(256)             DEFAULT NULL,
    `role`        VARCHAR(16)              DEFAULT NULL,
    `handled_by`  VARCHAR(64)              DEFAULT NULL,
    `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `handled_at`  DATETIME                 DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_team_join_team` (`team_id`, `status`, `created_at`),
    KEY `idx_team_join_user` (`user_id`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS `work_record`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`     BIGINT          NOT NULL DEFAULT 1,
    `user_id`     VARCHAR(64)     NOT NULL,
    `source`      VARCHAR(32)     NOT NULL,
    `type`        VARCHAR(32)     NOT NULL,
    `title`       VARCHAR(512)             DEFAULT NULL,
    `content`     TEXT,
    `status`      VARCHAR(32)              DEFAULT NULL,
    `occurred_at` DATETIME        NOT NULL,
    `external_id` VARCHAR(128)             DEFAULT NULL,
    `metadata`    JSON                     DEFAULT NULL,
    `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_work_record_dedup` (`user_id`, `source`, `external_id`),
    KEY `idx_work_record_user_time` (`user_id`, `occurred_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS `report`
(
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`      BIGINT          NOT NULL DEFAULT 1,
    `user_id`      VARCHAR(64)     NOT NULL,
    `type`         VARCHAR(16)     NOT NULL,
    `period_start` DATE            NOT NULL,
    `period_end`   DATE            NOT NULL,
    `content_json` JSON                     DEFAULT NULL,
    `tokens_used`  INT             NOT NULL DEFAULT 0,
    `cost`         DECIMAL(10, 6)  NOT NULL DEFAULT 0,
    `status`       VARCHAR(16)     NOT NULL,
    -- 生成这份报告的智能体运行记录，据此在界面上回看执行轨迹
    `agent_run_id` BIGINT UNSIGNED          DEFAULT NULL,
    `generated_at` DATETIME        NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_report_user_period` (`user_id`, `type`, `period_start`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS `push_log`
(
    `id`        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`   BIGINT          NOT NULL DEFAULT 1,
    `user_id`   VARCHAR(64)     NOT NULL,
    `channel`   VARCHAR(16)     NOT NULL,
    `type`      VARCHAR(32)     NOT NULL,
    `content`   TEXT,
    `status`    VARCHAR(16)     NOT NULL,
    `error_msg` VARCHAR(512)             DEFAULT NULL,
    `sent_at`   DATETIME        NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_push_log_user_type` (`user_id`, `type`, `sent_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 定时任务运行记录，唯一键保证同一人同一任务同一天只执行一次
CREATE TABLE IF NOT EXISTS `job_run`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`     BIGINT          NOT NULL DEFAULT 1,
    `user_id`     VARCHAR(64)     NOT NULL,
    `job_type`    VARCHAR(32)     NOT NULL,
    `biz_date`    DATE            NOT NULL,
    `status`      VARCHAR(16)     NOT NULL,
    `duration_ms` BIGINT          NOT NULL DEFAULT 0,
    `error_msg`   VARCHAR(512)             DEFAULT NULL,
    `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_job_run` (`user_id`, `job_type`, `biz_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 风险告警。fingerprint 由规则类型与业务键组成，唯一键用于避免同一条风险重复生成
CREATE TABLE IF NOT EXISTS `risk_alert`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`     BIGINT          NOT NULL DEFAULT 1,
    `user_id`     VARCHAR(64)     NOT NULL,
    `level`       VARCHAR(16)     NOT NULL,
    `type`        VARCHAR(32)     NOT NULL,
    `fingerprint` VARCHAR(160)    NOT NULL,
    `evidence`    TEXT,
    `suggestion`  VARCHAR(512)             DEFAULT NULL,
    `status`      VARCHAR(16)     NOT NULL,
    `agent_run_id` BIGINT UNSIGNED         DEFAULT NULL,
    `created_at`  DATETIME        NOT NULL,
    `notified_at` DATETIME                 DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_risk_alert_fingerprint` (`user_id`, `fingerprint`),
    KEY `idx_risk_alert_user_time` (`user_id`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 大模型调用埋点
CREATE TABLE IF NOT EXISTS `llm_call_log`
(
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`           BIGINT          NOT NULL DEFAULT 1,
    `user_id`           VARCHAR(64)              DEFAULT NULL,
    `scene`             VARCHAR(32)     NOT NULL,
    `model`             VARCHAR(64)              DEFAULT NULL,
    `prompt_tokens`     INT             NOT NULL DEFAULT 0,
    `completion_tokens` INT             NOT NULL DEFAULT 0,
    `duration_ms`       BIGINT          NOT NULL DEFAULT 0,
    `success`           TINYINT(1)      NOT NULL DEFAULT 1,
    `error_msg`         VARCHAR(512)             DEFAULT NULL,
    `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_llm_log_scene_time` (`scene`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 数据采集埋点
CREATE TABLE IF NOT EXISTS `collect_log`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`     BIGINT          NOT NULL DEFAULT 1,
    `user_id`     VARCHAR(64)     NOT NULL,
    `source`      VARCHAR(32)     NOT NULL,
    `stat_date`   DATE            NOT NULL,
    `fetched`     INT             NOT NULL DEFAULT 0,
    `inserted`    INT             NOT NULL DEFAULT 0,
    `duration_ms` BIGINT          NOT NULL DEFAULT 0,
    `success`     TINYINT(1)      NOT NULL DEFAULT 1,
    `error_msg`   VARCHAR(512)             DEFAULT NULL,
    `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_collect_log_user_date` (`user_id`, `stat_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 飞书用户身份授权令牌，密文保存
CREATE TABLE IF NOT EXISTS `feishu_auth`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`       BIGINT          NOT NULL DEFAULT 1,
    `user_id`       VARCHAR(64)     NOT NULL,
    `open_id`       VARCHAR(64)              DEFAULT NULL,
    `access_token`  VARCHAR(1024)   NOT NULL,
    `refresh_token` VARCHAR(1024)   NOT NULL,
    `scope`         VARCHAR(1024)            DEFAULT NULL,
    `expire_at`     DATETIME        NOT NULL,
    `updated_at`    DATETIME        NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_feishu_auth_user` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 聊天采集白名单。chat_id 只能从飞书客户端手工取得，这里存下来避免每次改配置文件
CREATE TABLE IF NOT EXISTS `chat_binding`
(
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`    BIGINT          NOT NULL DEFAULT 1,
    `user_id`    VARCHAR(64)     NOT NULL,
    `chat_id`    VARCHAR(64)     NOT NULL,
    `name`       VARCHAR(128)             DEFAULT NULL,
    `chat_type`  VARCHAR(16)     NOT NULL DEFAULT 'P2P',
    `identity`   VARCHAR(16)     NOT NULL DEFAULT 'USER',
    `enabled`    TINYINT(1)      NOT NULL DEFAULT 1,
    `verified`   TINYINT(1)      NOT NULL DEFAULT 0,
    `verify_msg` VARCHAR(512)             DEFAULT NULL,
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chat_binding` (`user_id`, `chat_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 聊天记录采集游标，记录上次拉到的位置，避免重复拉取
CREATE TABLE IF NOT EXISTS `chat_cursor`
(
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`           BIGINT          NOT NULL DEFAULT 1,
    `chat_id`           VARCHAR(64)     NOT NULL,
    `user_id`           VARCHAR(64)     NOT NULL,
    `last_message_time` DATETIME        NOT NULL,
    `updated_at`        DATETIME        NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chat_cursor` (`chat_id`, `user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 知识文档库。正文以 Markdown 文件存放，这里只存元数据与相对路径
-- 文件是唯一事实来源，启动时按目录扫描同步；唯一键带团队，各团队的相对路径互不干扰
CREATE TABLE IF NOT EXISTS `knowledge_doc`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`     BIGINT          NOT NULL DEFAULT 1,
    `title`       VARCHAR(256)    NOT NULL,
    `category`    VARCHAR(64)     NOT NULL,
    `tags`        VARCHAR(512)             DEFAULT NULL,
    `source`      VARCHAR(64)              DEFAULT NULL,
    `visibility`  VARCHAR(32)     NOT NULL DEFAULT 'TEAM',
    `content_ref` VARCHAR(512)    NOT NULL,
    `summary`     VARCHAR(1024)            DEFAULT NULL,
    `enabled`     TINYINT(1)      NOT NULL DEFAULT 1,
    -- 上传归属：只由上传动作写入，目录扫描不覆盖这两列
    `uploader_user_id` VARCHAR(64)         DEFAULT NULL,
    `uploaded_at`      DATETIME            DEFAULT NULL,
    `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_doc_team_ref` (`team_id`, `content_ref`),
    KEY `idx_knowledge_doc_category` (`category`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 坑位记录。规则层用工作记录文本匹配 tags，命中即提醒
CREATE TABLE IF NOT EXISTS `pitfall`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`     BIGINT          NOT NULL DEFAULT 1,
    `title`       VARCHAR(256)    NOT NULL,
    `tags`        VARCHAR(512)             DEFAULT NULL,
    `symptom`     TEXT,
    `cause`       TEXT,
    `solution`    TEXT,
    `content_ref` VARCHAR(512)             DEFAULT NULL,
    `enabled`     TINYINT(1)      NOT NULL DEFAULT 1,
    `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pitfall_team_title` (`team_id`, `title`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 系统用户与角色。启动时以配置清单初始化，角色与启用状态以库内为准，不被配置覆盖
-- status 与 enabled 分开：enabled 表达停用，status 表达待审批，二者互不替代
-- 登录走邮箱 + 密码，password_hash 只存 PBKDF2 结果，password_reset_required 表示需要强制改密
CREATE TABLE IF NOT EXISTS `sys_user`
(
    `id`                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    -- team_id 为 0 表示还没加入任何团队，此时只能访问个人中心与团队页
    `team_id`                 BIGINT          NOT NULL DEFAULT 1,
    `user_id`                 VARCHAR(64)     NOT NULL,
    `name`                    VARCHAR(64)              DEFAULT NULL,
    `role`                    VARCHAR(16)     NOT NULL DEFAULT 'INTERN',
    `mentor_user_id`          VARCHAR(64)              DEFAULT NULL,
    `feishu_open_id`          VARCHAR(64)              DEFAULT NULL,
    `email`                   VARCHAR(128)             DEFAULT NULL,
    `password_hash`           VARCHAR(200)             DEFAULT NULL,
    `password_updated_at`     DATETIME                 DEFAULT NULL,
    `password_reset_required` TINYINT(1)      NOT NULL DEFAULT 0,
    `failed_attempts`         INT             NOT NULL DEFAULT 0,
    `locked_until`            DATETIME                 DEFAULT NULL,
    `last_login_at`           DATETIME                 DEFAULT NULL,
    `status`                  VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    `enabled`                 TINYINT(1)      NOT NULL DEFAULT 1,
    `created_at`              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_user` (`user_id`),
    UNIQUE KEY `uk_sys_user_email` (`email`),
    KEY `idx_sys_user_open_id` (`feishu_open_id`),
    KEY `idx_sys_user_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 邮箱验证码。只存摘要不存明文，一次性使用，同一邮箱同一用途保留最近若干条
CREATE TABLE IF NOT EXISTS `email_code`
(
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `email`      VARCHAR(128)    NOT NULL,
    `purpose`    VARCHAR(32)     NOT NULL,
    `code_hash`  VARCHAR(128)    NOT NULL,
    `expires_at` DATETIME        NOT NULL,
    `used`       TINYINT(1)      NOT NULL DEFAULT 0,
    `attempts`   INT             NOT NULL DEFAULT 0,
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_email_code_email` (`email`, `purpose`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 登录会话。PENDING 是飞书登录的待授权占位，回调后置为 ACTIVE 并绑定用户
-- device 与 ip 由登录动作写入，用于个人中心的登录设备列表
CREATE TABLE IF NOT EXISTS `user_session`
(
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`      BIGINT          NOT NULL DEFAULT 1,
    `token`        VARCHAR(64)     NOT NULL,
    `user_id`      VARCHAR(64)              DEFAULT NULL,
    `role`         VARCHAR(16)              DEFAULT NULL,
    `status`       VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    `device`       VARCHAR(256)             DEFAULT NULL,
    `ip`           VARCHAR(64)              DEFAULT NULL,
    `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `expire_at`    DATETIME        NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_session_token` (`token`),
    KEY `idx_user_session_expire` (`expire_at`),
    KEY `idx_user_session_user` (`user_id`, `expire_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 智能体运行记录。一次问答、一次报告生成、一次风险检查各占一行
CREATE TABLE IF NOT EXISTS `agent_run`
(
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`           BIGINT          NOT NULL DEFAULT 1,
    `user_id`           VARCHAR(64)     NOT NULL,
    `scene`             VARCHAR(32)     NOT NULL,
    `title`             VARCHAR(256)             DEFAULT NULL,
    `status`            VARCHAR(16)     NOT NULL,
    `step_count`        INT             NOT NULL DEFAULT 0,
    `prompt_tokens`     INT             NOT NULL DEFAULT 0,
    `completion_tokens` INT             NOT NULL DEFAULT 0,
    `duration_ms`       BIGINT          NOT NULL DEFAULT 0,
    `error_msg`         VARCHAR(512)             DEFAULT NULL,
    `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_agent_run_user_time` (`user_id`, `created_at`),
    KEY `idx_agent_run_scene_time` (`scene`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 智能体单步轨迹。一步一次模型调用，调用了几个工具就写几行
CREATE TABLE IF NOT EXISTS `agent_step`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`     BIGINT          NOT NULL DEFAULT 1,
    `run_id`      BIGINT UNSIGNED NOT NULL,
    `step_no`     INT             NOT NULL,
    `thought`     TEXT,
    `tool_name`   VARCHAR(64)              DEFAULT NULL,
    `tool_args`   TEXT,
    `tool_result` TEXT,
    `duration_ms` BIGINT          NOT NULL DEFAULT 0,
    `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_agent_step_run` (`run_id`, `step_no`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 用户偏好。每个用户一行，缺行时按默认值处理
-- git_authors 与 zentao_* 是个人级数据源配置；zentao_password 存密文
-- weekly_* 与 watch_interns 是导师侧配置，实习生不用可留空
CREATE TABLE IF NOT EXISTS `user_preference`
(
    `id`                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`                 BIGINT          NOT NULL DEFAULT 1,
    `user_id`                 VARCHAR(64)     NOT NULL,
    `display_name`            VARCHAR(64)              DEFAULT NULL,
    `feishu_open_id`          VARCHAR(64)              DEFAULT NULL,
    -- 个人关联的本地仓库路径，逗号分隔；为空时回退服务端兜底列表
    `git_repositories`        VARCHAR(1024)            DEFAULT NULL,
    `git_authors`             VARCHAR(512)             DEFAULT NULL,
    `zentao_account`          VARCHAR(64)              DEFAULT NULL,
    `zentao_password`         VARCHAR(1024)            DEFAULT NULL,
    `zentao_login_allowed`    TINYINT(1)      NOT NULL DEFAULT 0,
    `morning_push_enabled`    TINYINT(1)      NOT NULL DEFAULT 1,
    `morning_push_time`       VARCHAR(5)      NOT NULL DEFAULT '09:00',
    `evening_push_enabled`    TINYINT(1)      NOT NULL DEFAULT 1,
    `evening_push_time`       VARCHAR(5)      NOT NULL DEFAULT '18:30',
    `alert_push_enabled`      TINYINT(1)      NOT NULL DEFAULT 1,
    `quiet_start`             VARCHAR(5)               DEFAULT NULL,
    `quiet_end`               VARCHAR(5)               DEFAULT NULL,
    `push_channel`            VARCHAR(16)     NOT NULL DEFAULT 'USER',
    `report_style`            VARCHAR(16)     NOT NULL DEFAULT 'DETAIL',
    `attach_commit_hash`      TINYINT(1)      NOT NULL DEFAULT 1,
    `plan_focus_limit`        INT             NOT NULL DEFAULT 3,
    `weekly_enabled`          TINYINT(1)      NOT NULL DEFAULT 1,
    `weekly_weekday`          VARCHAR(8)      NOT NULL DEFAULT 'FRI',
    `weekly_time`             VARCHAR(5)      NOT NULL DEFAULT '18:30',
    `weekly_scope`            VARCHAR(16)     NOT NULL DEFAULT 'WATCHED',
    `watch_interns`           VARCHAR(512)             DEFAULT NULL,
    `weekly_trend_enabled`    TINYINT(1)      NOT NULL DEFAULT 1,
    `risk_types`              VARCHAR(256)             DEFAULT NULL,
    `risk_min_level`          VARCHAR(16)     NOT NULL DEFAULT 'LOW',
    `learn_categories`        VARCHAR(256)             DEFAULT NULL,
    `learn_tags`              VARCHAR(256)             DEFAULT NULL,
    `learn_answer_style`      VARCHAR(16)     NOT NULL DEFAULT 'STANDARD',
    `updated_at`              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_preference` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 团队级设置。键值结构，便于以后加配置项而不用改表
-- 配置文件里的值只是默认值，这里存在则以这里为准
CREATE TABLE IF NOT EXISTS `team_setting`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`       BIGINT          NOT NULL DEFAULT 1,
    `setting_key`   VARCHAR(64)     NOT NULL,
    `setting_value` VARCHAR(512)             DEFAULT NULL,
    `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_team_setting` (`team_id`, `setting_key`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 学习问答会话。首轮提问时创建，标题取首个问题
CREATE TABLE IF NOT EXISTS `learn_conversation`
(
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`    BIGINT          NOT NULL DEFAULT 1,
    `user_id`    VARCHAR(64)     NOT NULL,
    `title`      VARCHAR(128)    NOT NULL,
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_learn_conversation_user` (`user_id`, `updated_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 学习问答消息。role 取 USER 或 ASSISTANT，引用文档只存编号、标题与分类
CREATE TABLE IF NOT EXISTS `learn_message`
(
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`           BIGINT          NOT NULL DEFAULT 1,
    `conversation_id`   BIGINT UNSIGNED NOT NULL,
    `user_id`           VARCHAR(64)     NOT NULL,
    `role`              VARCHAR(16)     NOT NULL,
    `content`           MEDIUMTEXT,
    `reference_docs`    VARCHAR(2048)            DEFAULT NULL,
    `prompt_tokens`     INT             NOT NULL DEFAULT 0,
    `completion_tokens` INT             NOT NULL DEFAULT 0,
    `agent_run_id`      BIGINT UNSIGNED          DEFAULT NULL,
    `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_learn_message_conversation` (`conversation_id`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 配置变更提案。问答助手只能提出提案，用户在界面上确认后才写到 user_preference
CREATE TABLE IF NOT EXISTS `config_proposal`
(
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`         BIGINT          NOT NULL DEFAULT 1,
    `user_id`         VARCHAR(64)     NOT NULL,
    `conversation_id` BIGINT UNSIGNED          DEFAULT NULL,
    `field_key`       VARCHAR(64)     NOT NULL,
    `field_label`     VARCHAR(128)             DEFAULT NULL,
    `field_value`     VARCHAR(1024)            DEFAULT NULL,
    `reason`          VARCHAR(512)             DEFAULT NULL,
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `handled_at`      DATETIME                 DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_config_proposal_user` (`user_id`, `status`, `created_at`),
    KEY `idx_config_proposal_conversation` (`conversation_id`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 文档检索埋点，用于统计检索次数与命中率
CREATE TABLE IF NOT EXISTS `doc_retrieve_log`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `team_id`     BIGINT          NOT NULL DEFAULT 1,
    `user_id`     VARCHAR(64)              DEFAULT NULL,
    `scene`       VARCHAR(32)     NOT NULL,
    `query_text`  VARCHAR(512)             DEFAULT NULL,
    `hit_count`   INT             NOT NULL DEFAULT 0,
    `read_count`  INT             NOT NULL DEFAULT 0,
    `read_chars`  INT             NOT NULL DEFAULT 0,
    `duration_ms` BIGINT          NOT NULL DEFAULT 0,
    `success`     TINYINT(1)      NOT NULL DEFAULT 1,
    `error_msg`   VARCHAR(512)             DEFAULT NULL,
    `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_doc_log_scene_time` (`scene`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
