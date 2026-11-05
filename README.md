# 实习雷达（Intern Radar）

面向实习生与导师的工作推进智能体。它自动采集日常工作中的真实数据，生成今日计划、日报与周报，识别进度风险，并把导师经验沉淀为可检索的团队知识库。

系统按「团队即租户」隔离数据，一个账号一个角色。实习生看到自己的今日、报告与问答，导师额外拥有看板、团队成员与报告查看能力。

## 适合谁

- **实习生**：不知道今天该做什么、做完不知道有没有价值、遇到问题找不到人问。
- **导师**：不清楚实习生每天在做什么、风险什么时候出现、经验只能靠口头传递。
- **团队**：产出与风险不透明，周报靠手动拼，知识散落在聊天记录里。

## 功能

### 我的今日

- 今日记录数、未处理告警、本周报告等指标带。
- 三张图：近 7 天记录柱图、今日来源构成环图、近 7 天来源 × 天热力图。
- 今日计划与当日总结按「一封信」排版，页签切换。
- 风险提醒与推荐阅读收进弹窗，配置缺项有引导入口。
- 个人配置未配齐时，顶部提示还差哪几项，可一键跳到对应设置。

### 报告

- 报告列表按类型（日报、今日计划、周报）与日期区间筛选。
- 实习生看自己的报告；导师多一个「查看对象」选择器。
- 报告详情按标题、生成信息、正文、结构化条目排版，支持复制内容与查看执行轨迹。
- 可手动生成当日总结；生成完成后自动选中新报告。
- 报告状态分为成功、空数据与失败，空数据表示当天没有可用工作记录。

### 导师看板

- 团队产出趋势，支持 7 / 14 / 30 天切换。
- 成员活跃热力图，按天着色，成员不足五行时补空行，超过五行分页。
- 未处理告警按等级构成环图，「查看风险」可打开清单并逐条处理。
- 成员明细可进入个人视角，查看该实习生的报告与推荐。

### AI 助手

- 基于团队知识库的两段式流式问答：先推送引用清单，再逐字输出答复。
- 答复内嵌执行轨迹，可展开查看每一步工具调用与耗时。
- 引用可展开，标明文档编号与分类；知识库没有依据时会明确说明。
- 可选用联网搜索（Firecrawl，需在服务端配置密钥）。
- 推荐阅读与坑位提醒收进弹窗。
- 助手可提出个人配置变更建议，用户在问答页确认后才会写入。

### 文档库

- 知识文档与坑位库两个页签，支持关键词搜索与分类筛选。
- 文档详情用抽屉展示，正文来自 Markdown 文件。
- 支持上传文档，同名文件可选择覆盖或另存，记录上传人。
- 坑位按「症状 / 原因 / 解法」三行展示。

### 会话绑定

- 绑定需要采集的飞书会话，展示最近摘要时间与摘要内容。
- 新增绑定、查看摘要、解绑都在弹窗或抽屉内完成。
- 会话标识需从飞书客户端复制后手工绑定，系统不会主动给联系人发消息。

### 我的团队

- 成员表展示角色、本周记录、未完成任务、最近活跃与账号状态。
- 创建团队或用邀请码申请加入，申请由团队创建人或管理员审批。
- 邀请码对团队成员可见，可重新生成；创建人可为成员重置一次性密码。

### 个人中心

- 身份区展示姓名、角色、邮箱、飞书绑定状态与近 30 天统计。
- 最近两周活跃色块，附有记录天数与最长连续天数。
- 编辑资料、修改密码、登录设备管理（可退出指定设备或全部设备）。

### 我的配置

- 左分组导航 + 单列表单 + 底部保存条，按分组保存。
- 个人分组：个人信息、学习偏好、代码提交、内部任务、消息与推送、报告偏好、风险偏好。
- 导师额外可见：团队设置、服务端配置（飞书、发信邮箱、内部系统、Git 兜底仓库、模型、联网搜索）。
- 密钥类字段只回传「是否已设置」，不回显明文；配置保存后即时生效。

## 工作原理

1. **采集**：按人读取本地 Git 仓库提交、内部任务系统的任务与工时、白名单会话的聊天摘要。
2. **入库**：工作记录按来源与外部标识去重，聊天只落摘要不落原文。
3. **报告**：智能体通过工具查询当日数据，输出结构化计划、日报与周报；失败时带提示重试一次。
4. **风险**：规则层先判断连续无产出、任务停滞、临期未完成与坑位命中，再由模型复核误报并补判定理由。
5. **触达**：报告与告警可推送飞书群或私聊；站内始终保留可查看的记录。

## 技术栈

| 层 | 选型 |
| --- | --- |
| 后端 | Spring Boot 3.4.4、Java 21 |
| 智能体 | Spring AI 1.0.0，OpenAI 兼容协议 |
| 存储 | MySQL 8、MyBatis |
| 前端 | Vue 3、Vite、Element Plus、ECharts |
| 趋势统计 | Python 服务（可选） |
| 外部系统 | 飞书开放平台、内部任务系统、Firecrawl（可选） |
| 部署 | Docker、Nginx |

## 目录结构

```
.
├── src/main/java/com/ciniam/internradar/
│   ├── adapter/         Git、内部任务系统、飞书会话采集适配器
│   ├── agent/           实习雷达智能体（学习、报告、结构化输出）
│   ├── config/          启动期补列、团队与用户初始化、知识同步
│   ├── controller/      REST 接口
│   ├── entity/ mapper/  表实体与 MyBatis 映射
│   ├── model/           接口出入参
│   ├── push/ feishu/    飞书推送、用户授权与聊天采集
│   ├── scheduler/       早计划、晚总结、周报、风险检查
│   ├── security/        登录态、角色与团队上下文、密码与令牌加密
│   ├── service/         报告、风险、知识、问答、配置、团队等业务
│   ├── tools/           智能体可调用的业务工具
│   └── trend/           趋势服务客户端
├── src/main/java/com/ciniam/core/
│   ├── agent/           智能体内核（BaseAgent / ReActAgent / ToolCallAgent）
│   ├── config/          跨域
│   └── controller/      健康检查
├── src/main/resources/
│   ├── application.yml  主配置
│   ├── db/schema.sql    建表脚本，启动时自动执行
│   └── application-local.yml  本机配置（不入库）
├── intern-radar-frontend/     前端工程
├── knowledge/                 知识文档与坑位记录
├── trend-service/             Python 趋势服务
├── Dockerfile                 后端镜像
└── pom.xml
```

## 数据存储

数据库共 25 张表，应用启动时按 `db/schema.sql` 条件建表；给已有表补列、调整唯一键由启动期迁移组件完成。

| 分类 | 主要表 |
| --- | --- |
| 身份与团队 | `sys_user`、`team`、`team_join_request`、`team_setting`、`user_preference`、`user_session` |
| 工作与报告 | `work_record`、`report`、`push_log`、`job_run` |
| 风险 | `risk_alert` |
| 知识与问答 | `knowledge_doc`、`pitfall`、`learn_conversation`、`learn_message`、`config_proposal` |
| 飞书 | `feishu_auth`、`chat_binding`、`chat_cursor` |
| 运行观测 | `agent_run`、`agent_step`、`collect_log`、`llm_call_log`、`doc_retrieve_log` |
| 账号辅助 | `email_code` |

## 快速开始

### 环境要求

- JDK 21
- Maven 3.9+
- Node.js 18+
- MySQL 8

### 1. 准备数据库

新建一个 MySQL 库即可，表结构会在后端启动时自动创建。库名与账号写在本地配置里。

### 2. 本地配置

把敏感项写在 `src/main/resources/application-local.yml`，该文件已被忽略规则排除：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/intern_radar?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
    username: root
    password: 你的数据库密码
  ai:
    openai:
      api-key: 你的模型密钥
      base-url: https://api.deepseek.com
      chat:
        options:
          model: deepseek-chat

intern-radar:
  # 首次启动写入的账号，邮箱即登录名，密码只用于首次建号
  interns:
    - user-id: intern1
      name: 张三
      email: intern1@example.com
      password: 初始密码
      git-authors:
        - intern1@example.com
      mentor-user-id: mentor1
  mentors:
    - user-id: mentor1
      name: 李四
      email: mentor1@example.com
      password: 初始密码
  git:
    repositories:
      - d:/workspace/project-a
  # 飞书、内部任务系统、发信邮箱等按需补充，未配置时对应能力自动跳过
```

### 3. 启动后端

```bash
mvn spring-boot:run
```

- 端口 8123，上下文路径 `/api`
- 健康检查：`http://localhost:8123/api/health`
- 接口文档：`http://localhost:8123/api/doc.html`

### 4. 启动前端

```bash
cd intern-radar-frontend
npm install
npm run dev
```

默认地址 `http://localhost:3000`，开发环境直接请求 `http://localhost:8123/api`，后端已配置跨域，不需要额外的 Vite 代理。

### 5. 首次登录

使用本地配置里的邮箱与初始密码登录。首次登录会要求修改密码。未加入团队时只能进入团队页与个人中心，需要先创建团队或用邀请码申请加入。

## 定时任务

定时任务默认关闭，需要时在配置里打开。开启后按窗口扫描，并由 `job_run` 保证同一天不重复推送。

| 任务 | 默认行为 | 配置项 |
| --- | --- | --- |
| 早间计划 | 每人按自己的时间点推送，6 点到 23 点每 5 分钟扫描 | `intern-radar.scheduler.morning-*` |
| 晚间总结 | 同上，按个人配置时间推送 | `intern-radar.scheduler.evening-*` |
| 周报 | 按导师设置的时间与范围推送 | `intern-radar.scheduler.weekly-*` |
| 风险检查 | 每天 19:00 执行一次 | `intern-radar.scheduler.risk-check-cron` |

风险阈值默认值：连续两天无产出、任务停滞三天、截止两天内且进度低于五成，告警节流 6 小时。阈值均可在团队设置里修改。

## 配置说明

主配置在 `application.yml` 的 `intern-radar` 节点下，本机真实值写在 `application-local.yml`。

| 配置组 | 作用 |
| --- | --- |
| `interns` / `mentors` | 首次启动写入的实习生与导师账号，只新增不覆盖 |
| `git` | Git 可执行文件、超时、单次提交上限、兜底仓库列表 |
| `feishu` | 群机器人 Webhook、自建应用凭据、两个回调地址、聊天白名单 |
| `zentao` | 内部任务系统地址、账号、登录闸门、会话有效期与失败熔断 |
| `risk` | 无产出天数、停滞天数、临期天数与进度线、告警节流 |
| `scheduler` | 四类定时任务的开关与 cron |
| `security` | 飞书令牌加密密钥，必须自行替换 |
| `trend` | Python 趋势服务地址，留空则跳过 |
| `knowledge` | 知识目录、启动同步、单篇读取上限、检索条数 |
| `search` | 联网搜索密钥与地址，留空不注册该工具 |
| `model` | 模型地址、密钥、模型名、温度与超时 |
| `auth` | 身份直选开关、会话时长、密码策略、失败锁定、验证码有效期 |
| `mail` | SMTP 发信配置，未配置时无法自助找回密码 |

## 接口概览

所有接口以 `/api` 为前缀，除登录相关外都需要请求头 `X-Auth-Token`。角色与团队限权在拦截器里按路径统一判断。

| 分组 | 用途 |
| --- | --- |
| `auth` | 注册、登录、身份直选、找回与重置密码、当前用户、退出 |
| `profile` | 本人资料、修改密码、登录设备 |
| `team` | 团队信息、成员、创建、邀请码加入、申请与审批、重置成员密码 |
| `report` | 采集、日报、今日计划、周报、区间报告、工作记录、按天统计 |
| `risk` | 风险检查、通知、告警查询与确认 |
| `mentor` | 团队总览、实习生状态、趋势、全部告警 |
| `learn` | 问答（同步与流式）、推荐、文档库、上传与刷新、坑位、会话、提案 |
| `settings` | 个人偏好、数据源状态、配置引导、团队设置、服务端配置与连接测试 |
| `feishu` | 用户授权、会话探针、会话列表、白名单绑定与解绑 |
| `agent` | 智能体运行列表与详情，供页内轨迹使用 |
| `push` / `admin` / `zentao` | 推送测试、可观测统计、内部系统排障 |

## 前端开发

| 命令 | 说明 |
| --- | --- |
| `npm run dev` | 本地开发，默认 3000 端口 |
| `npm run build` | 生产构建，产物在 `dist/` |
| `npm run preview` | 预览构建产物 |
| `npm run check:tokens` | 校验设计令牌副本一致并扫描裸色值与裸尺寸 |

前端页面：登录、我的今日、报告、导师看板、AI 助手、文档库、会话绑定、我的团队、个人中心、我的配置。路由按登录态、角色与团队归属做前置校验，无权限时回到对应首页。

## 部署

后端镜像：

```bash
docker build -t intern-radar .
docker run -p 8123:8123 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://host:3306/intern_radar" \
  -e SPRING_DATASOURCE_USERNAME=root \
  -e SPRING_DATASOURCE_PASSWORD=密码 \
  intern-radar
```

前端镜像：

```bash
cd intern-radar-frontend
docker build -t intern-radar-web .
docker run -p 8080:80 intern-radar-web
```

Nginx 已把 `/api/` 反向代理到 `host.docker.internal:8123`，并关闭了 SSE 缓冲。前后端同域部署时，把代理地址改成实际后端地址即可。

## 安全与运维

- 密钥、初始密码、内部系统账号只写本地配置或环境变量，不进入仓库。
- 对外提供服务前务必关闭身份直选（`intern-radar.auth.dev-login-enabled: false`）。
- 更换 `intern-radar.security.token-key`，用于加密飞书用户令牌。
- 聊天采集只覆盖白名单会话，只落摘要；不建议全量采集。
- 数据库与知识目录需要纳入备份；知识目录是文档正文的唯一来源。
- 日志中不要输出令牌、密码与聊天原文。

## 已知限制

- 报告生成不是流式输出；问答是两段式流式，首段用于检索与推引用。
- 内部任务系统只对接「我的工作、我的待办、我的工时日志」三类端点。
- 飞书不提供枚举单聊的接口，会话标识需要手工绑定；私发需要用户 open_id。
- 未引入 Redis，幂等与节流依赖库表唯一键。
- 可观测性只有埋点表与统计接口，没有独立监控面板。
- 项目当前没有自动化测试。
- 趋势统计依赖可选的 Python 服务，未启动时相关能力自动降级。
