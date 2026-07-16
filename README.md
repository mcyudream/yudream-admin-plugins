# YuDream Admin Plugins

<p align="center">
  YuDream Admin 官方业务插件仓库
</p>

<p align="center">
  <a href="https://github.com/mcyudream/YuDream-Admin">YuDream Admin</a> 的独立插件实现，提供可独立构建、发布和部署的后端插件 JAR 与 Vue 远程模块。
</p>

<p align="center">
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-437291?logo=openjdk&logoColor=white">
  <img alt="Maven 3.9+" src="https://img.shields.io/badge/Maven-3.9%2B-C71A36?logo=apachemaven&logoColor=white">
  <img alt="Vue 3" src="https://img.shields.io/badge/Vue-3-42B883?logo=vuedotjs&logoColor=white">
  <img alt="pnpm 11.9+" src="https://img.shields.io/badge/pnpm-11.9%2B-F69220?logo=pnpm&logoColor=white">
  <a href="https://gitlab.yudream.online/yudream/yudream-admin-plugins"><img alt="GitLab" src="https://img.shields.io/badge/GitLab-YuDream-FC6D26?logo=gitlab&logoColor=white"></a>
</p>

## 简介

本仓库承载 YuDream Admin 的官方业务插件。每个插件由两部分组成：

- **后端插件**：基于 Java 21 和 Maven 构建，产出可部署的插件 JAR。
- **前端远程模块**：基于 Vue 3、Vite 和 pnpm 构建，产出 `remoteEntry.js`，随对应插件 JAR 一同打包。

插件只依赖主项目已经发布的 SPI、SDK 和共享组件，因此可以独立开发、验证、发布与演进，无需引用主项目源码。

## 特性

- 独立维护官方业务插件，降低主项目与业务功能之间的耦合。
- 前后端一体化打包，部署一个 JAR 即可同时加载后端能力与前端页面。
- 统一使用已发布的 `yudream-plugin-spi`、`@yudream/plugin-sdk` 和 `@yudream/components` 契约。
- CI 自动构建前端远程模块、校验 JAR 内容，并在发布标签上推送插件制品。
- 提供仓库边界、依赖契约和打包产物校验，确保插件可以脱离主项目独立运行。

## 插件一览

| 插件 | 说明 |
| --- | --- |
| `alipay` | 支付宝支付与订单相关能力 |
| `ai-chatbot` | AI 聊天机器人与 Agent 应用接入 |
| `authlib-injector` | Authlib Injector 认证服务接入 |
| `codex-task-notify` | 向任务发起人的 QQ 私信推送 Codex 任务状态 |
| `minecraft-activity-proof` | Minecraft 活动证明管理 |
| `minecraft-server` | Minecraft 服务器、玩家与运行维护管理 |
| `project-progress` | 项目、任务、进度、验收与打卡管理 |
| `qq-binding` | QQ 账号绑定能力 |
| `qqbot-automation` | QQ 机器人自动化策略、入群验证与媒体任务 |
| `student-info` | 学生信息管理 |
| `wallet` | 钱包、余额、充值与交易管理 |
| `yudream-skin` | YuDream Skin 皮肤系统 |

## 架构

```text
YuDream Admin 主项目
        │
        ├── 发布的插件契约
        │   ├── yudream-plugin-spi
        │   ├── @yudream/plugin-sdk
        │   └── @yudream/components
        │
        ▼
本仓库的业务插件
        ├── yudream-plugins/             Java 后端插件
        └── yudream-frontend/packages/   Vue 前端远程模块
        │
        ▼
插件 JAR
        ├── 后端能力与插件元数据
        └── META-INF/yudream-plugin/frontend/{pluginCode}/remoteEntry.js
```

## 快速开始

### 环境要求

- JDK 21
- Maven 3.9 或更高版本
- Node.js 22.22 或 24.15 及更高版本
- pnpm 11.9 或更高版本

### 1. 配置依赖仓库

复制并按本地环境调整以下示例文件：

```powershell
Copy-Item settings.xml.example settings.xml
Copy-Item .npmrc.example .npmrc
```

- Maven 通过 `settings.xml` 访问公共依赖仓库和 YuDream Nexus。
- `@yudream/*` npm 包通过 `.npmrc` 中配置的 YuDream Nexus 获取。

### 2. 安装并构建前端模块

```powershell
Set-Location yudream-frontend
pnpm install --frozen-lockfile
pnpm -r --filter=@yudream/plugin-* run build
Set-Location ..
```

构建后的远程入口位于各前端包的 `dist/remoteEntry.js`。

### 3. 打包后端插件

```powershell
mvn -s settings.xml clean package -DskipTests
```

打包完成后，每个插件 JAR 都应包含与其插件代码对应的前端远程入口。

### 4. 验证仓库状态

在 Git Bash、WSL 或其他兼容的 POSIX shell 中运行：

```powershell
sh ci/verify-plugin-repo-readiness.sh
```

该脚本会检查独立性、Maven 与 npm 契约、开发规范、发布管道和文档边界；若已完成 JAR 打包，也会校验前端资源已被正确写入制品。

## 开发指南

### 目录结构

```text
.
├── yudream-plugins/                 后端插件 Maven 模块
│   └── yudream-plugin-{code}/
├── yudream-frontend/                前端 pnpm workspace
│   └── packages/plugin-{code}/       前端远程模块
├── ci/                              CI、制品与仓库边界校验脚本
├── docs/                            插件与集成文档
├── pom.xml                          Maven 聚合与共享 Java 契约版本
└── yudream-frontend/pnpm-workspace.yaml
                                    前端 workspace 与共享 npm 契约版本
```

### 新增或修改插件

1. 后端模块使用 `yudream-plugins/yudream-plugin-{code}` 命名，前端包使用 `yudream-frontend/packages/plugin-{code}` 命名。
2. 在后端的 `plugin.yml` 中声明插件名称、入口类、版本和依赖关系。
3. 前端模块必须产出 `dist/remoteEntry.js`；后端打包时将其写入 `META-INF/yudream-plugin/frontend/{pluginCode}/`。
4. 仅通过已发布的 SPI、SDK 和共享组件与主项目交互，不复制或依赖主项目内部源码。
5. 修改后至少完成对应前端构建和后端测试或打包，再运行仓库验证脚本。

### 单插件验证

将 `{code}` 替换为插件代码，例如 `qqbot-automation`：

```powershell
Set-Location yudream-frontend
pnpm --filter @yudream/plugin-{code} run build
Set-Location ..

mvn -pl yudream-plugins/yudream-plugin-{code} -am test
mvn -pl yudream-plugins/yudream-plugin-{code} -am package -DskipTests
```

## 构建产物与发布

CI 会按以下顺序执行：

1. 校验仓库独立性、依赖契约与发布配置。
2. 构建所有前端远程模块。
3. 打包所有插件 JAR，并验证其中包含 `remoteEntry.js`。
4. 将最终插件 JAR 收集到 `dist/plugins/`。
5. 在 `v*` 标签上发布到 Nexus，并重新读取制品进行校验。

发布依赖的凭据由 CI 环境注入。不要将 API Key、Nexus 账号、密码或其他密钥提交到仓库。

## 相关项目与文档

- [YuDream Admin（GitHub）](https://github.com/mcyudream/YuDream-Admin)
- [YuDream Admin（GitLab）](https://gitlab.yudream.online/yudream/yudreamadmin)
- [插件仓库（GitLab）](https://gitlab.yudream.online/yudream/yudream-admin-plugins)
- [Codex 任务通知插件说明](docs/codex-task-notify.md)

## 贡献

欢迎提交 Issue 和 Merge Request。提交前请确保：

- 修改范围聚焦于目标插件，避免引入主项目内部依赖。
- 前后端插件代码、路由、权限、接口和打包路径保持一致。
- 已完成相关模块的构建、测试或打包，并通过必要的仓库校验。
- 文档与实际行为同步更新，且不包含任何敏感信息。

## 许可证

本仓库的许可证与 YuDream Admin 项目保持一致。发布或复用前，请以仓库中的许可证文件及上游项目说明为准。
