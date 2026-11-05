---
title: 环境搭建：本地启动后端服务
category: 环境搭建
tags: 环境搭建,后端,启动,端口,数据库
source: 团队Wiki
visibility: TEAM
summary: 后端依赖 JDK21 与 MySQL，启动前确认端口与数据库连通，依赖下载慢时用离线模式。
---

# 环境搭建：本地启动后端服务

## 前置条件

- JDK 21，`java -version` 能打印版本号
- Maven 3.9 及以上
- MySQL 8，本地 3306 端口可连
- 项目根目录已 clone 到本地

## 启动步骤

1. 准备本地配置：复制 `src/main/resources/application-local.yml`，填入数据库账号密码与模型平台的 `api-key`。这份文件已被 .gitignore 忽略，不会提交。
2. 拉依赖：`mvn -Dmaven.repo.local=./tmp/m2repo dependency:go-offline`。依赖下载慢时加国内镜像，或指定已有的本地仓库目录跳过下载。
3. 编译：`mvn -o -Dmaven.repo.local=./tmp/m2repo compile -DskipTests`。
4. 启动：`mvn spring-boot:run -Dspring-boot.run.profiles=local`，或直接跑打包后的 jar。
5. 验证：浏览器或 curl 访问 `http://localhost:8123/api/health`，返回 `ok` 即启动成功。

## 端口与数据库

- 后端默认端口 8123，上下文路径为 `/api`，所以接口地址形如 `http://localhost:8123/api/xxx`
- 建库脚本在 `src/main/resources/db/schema.sql`，启动时自动执行，全部是 `CREATE TABLE IF NOT EXISTS`，重复启动安全
- 数据库连不上时启动会直接失败，先确认 MySQL 已启动、账号密码正确、`allowPublicKeyRetrieval=true` 已带上

## 依赖下载慢的处理路径

1. 判断瓶颈在哪：是 Maven 中央仓库慢，还是镜像源不通
2. 有完整缓存的本地仓库目录时，用 `-Dmaven.repo.local` 直接指定，完全跳过下载
3. 没有缓存时改用国内镜像源，并配 `settings.xml` 的 mirror
4. 依赖私有制品时，需要在 `settings.xml` 单独配置 repository 与凭据

## 常见问题

- 启动报端口占用：先确认 8123 是否已被上一个实例占着，Windows 用 `Get-NetTCPConnection -LocalPort 8123`
- 启动报表不存在：说明建表脚本没执行成功，检查 `spring.sql.init.mode` 是否为 `always`
- 接口 401：登录态失效，重新登录拿新令牌
- 接口 403：当前身份没有该权限，实习生只能访问自己的数据
