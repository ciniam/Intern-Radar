# 前端（Vue3 + Vite）

实习生工作推进智能体 —— 前端工程。

## 环境要求

- Node.js ≥ 16
- npm ≥ 7

## 开发

```bash
npm install
npm run dev      # 开发服务器，默认 http://localhost:3000
```

## 构建

```bash
npm run build    # 产物输出到 dist/
npm run preview  # 本地预览构建产物
```

## 后端接口

后端默认运行在 `http://localhost:8123`，开发环境的 baseURL 在 `src/api/index.js` 中配置：

```js
const API_BASE_URL = import.meta.env.PROD
  ? '/api'                          // 生产环境走相对路径（前后端同域）
  : 'http://localhost:8123/api'     // 开发环境指向本地后端
```

业务接口全部走 `/api` 前缀，清单见 `src/api/index.js`。

后端已配置全局 CORS，前后端分离部署时**不需要额外配置 Vite 代理**。

> ⚠️ 后端接口路径变更时，需同步修改 `src/api/index.js` 与 `src/views/` 下的调用。

## 目录结构

```
src/
├── api/          接口封装（axios 实例 + baseURL）
├── views/        页面组件
├── router/       路由
└── style.css     全局样式
```

## 说明

当前页面仍是骨架阶段的应用界面（对话 / 智能体演示），
后续将按技术方案改造为业务看板（报告查看、风险列表、文档检索问答）。
详见仓库根目录 `README.md` 与 `docs/实习生工作推进智能体-技术方案.md`。
