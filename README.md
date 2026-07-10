# YuDream Admin Plugins

<p align="center">官方业务插件独立源码仓，面向远程前端模块与可部署插件 JAR 的构建发布</p>

<p align="center">
  <img alt="java" src="https://img.shields.io/badge/Java-21-437291?logo=openjdk&logoColor=white">
  <img alt="maven" src="https://img.shields.io/badge/Maven-3.9-C71A36?logo=apachemaven&logoColor=white">
  <img alt="vue" src="https://img.shields.io/badge/Vue-3-42B883?logo=vuedotjs&logoColor=white">
  <img alt="pnpm" src="https://img.shields.io/badge/pnpm-11.9-F69220?logo=pnpm&logoColor=white">
  <img alt="remote-entry" src="https://img.shields.io/badge/Remote%20Entry-enabled-7C3AED">
  <img alt="plugin-jar" src="https://img.shields.io/badge/Plugin%20JAR-ready-409EFF">
</p>

YuDream Admin Plugins 是 YuDream Admin 官方业务插件的独立仓库，负责维护插件后端模块、插件前端 remote 模块，以及最终可部署的插件 JAR 产物。

这个仓库只消费主体仓正式发布的 SPI / SDK / 共享组件，不再依赖主体仓的 root parent 或本地前端共享源码。

## ✨ 功能特性

- ✅ **官方插件独立维护**：将支付、认证、Minecraft、项目管理、钱包等官方业务插件集中到独立仓演进。
- ✅ **严格消费正式契约**：只依赖主体仓发布的 SPI / SDK / 共享组件，不再反向耦合主体仓源码。
- ✅ **前后端一体打包**：插件前端 `remoteEntry.js` 会随最终 JAR 一起打包，方便宿主运行时直接加载。
- ✅ **平铺插件产物**：CI 会将最终插件 JAR 统一整理到 `dist/plugins/*.jar`，便于分发、上传与部署。
- ✅ **独立发布链路**：支持将插件 JAR 发布到 Nexus Maven Repository，并在发布后回读校验。
- ✅ **仓库边界守卫**：内置 workspace、契约消费、JAR 内容、远端发布等校验脚本，确保拆仓后结构持续稳定。

## 仓库定位

- 官方业务插件源码集中维护
- 插件前端 remote entry 构建与打包
- 插件后端 JAR 构建
- 插件独立 CI、独立发布与回读校验
- 消费主体仓发布的插件契约，而不是复用主体仓源码工作区

## 依赖的主体契约

当前插件仓依赖以下正式契约：

- Maven: `online.yudream.base:yudream-plugin-spi`
- npm: `@yudream/plugin-sdk`
- npm: `@yudream/components`

默认消费策略：

- Maven 优先从阿里云公共仓库获取通用依赖，缺失时回退 Nexus；YuDream SPI 从 Nexus `maven-public` 获取
- `@yudream/*` 从 Nexus `npm-public` 获取，第三方 npm 包继续从 npmjs 获取
- 制品读取使用 Nexus 匿名读；只有受保护的 `v*` tag 发布使用 `NEXUS_USERNAME` / `NEXUS_PASSWORD`

相关仓库：

- Core GitHub: [mcyudream/YuDream-Admin](https://github.com/mcyudream/YuDream-Admin)
- Core GitLab: [yudream/yudreamadmin](https://gitlab.yudream.online/yudream/yudreamadmin)
- Plugins GitLab: [yudream/yudream-admin-plugins](https://gitlab.yudream.online/yudream/yudream-admin-plugins)

## 当前插件列表

| 模块 | 说明 |
| --- | --- |
| `yudream-plugin-alipay` | 支付宝相关业务插件 |
| `yudream-plugin-authlib-injector` | Authlib Injector 认证接入插件 |
| `yudream-plugin-minecraft-activity-proof` | Minecraft 活动证明插件 |
| `yudream-plugin-minecraft-server` | Minecraft 服务器管理插件 |
| `yudream-plugin-project-progress` | 项目管理、进度跟踪与打卡插件 |
| `yudream-plugin-student-info` | 学生信息管理插件 |
| `yudream-plugin-wallet` | 钱包与余额管理插件 |
| `yudream-plugin-yudream-skin` | YuDream Skin 皮肤系统插件 |

## 技术栈

- Java 21
- Maven 3.9+
- Node.js 22.22+ / 24.15+
- pnpm 11.9+
- Vue 3 + Vite

## 快速开始

### 1. 配置契约包访问

按需参考：

- [`settings.xml.example`](settings.xml.example)
- [`.npmrc.example`](.npmrc.example)

### 2. 构建插件前端

```powershell
cd yudream-frontend
pnpm install --frozen-lockfile
pnpm -r --filter=@yudream/plugin-* run build
```

### 3. 构建插件后端与最终 JAR

```powershell
mvn -s settings.xml clean package -DskipTests
```

### 4. 运行仓库完整审计

```powershell
& 'C:/Program Files/Git/bin/sh.exe' ci/verify-plugin-repo-readiness.sh
```

## 构建产物

插件仓构建完成后会得到两类关键产物：

- 插件前端 `dist/`
- 最终插件 JAR

在 CI 中，最终插件 JAR 会被平铺整理到：

```text
dist/plugins/*.jar
```

每个最终 JAR 都要求包含：

```text
META-INF/yudream-plugin/frontend/{pluginCode}/remoteEntry.js
```

这样主体仓插件运行时才能在加载 JAR 时同时发现后端插件能力与前端 remote 模块。

## 仓库结构

```text
yudream-plugins/   插件后端模块
yudream-frontend/  插件前端 workspace
ci/                独立性、契约消费、JAR 校验与发布脚本
docs/              迁移、边界、发布说明
pom.xml            插件仓聚合根
.gitlab-ci.yml     插件仓独立 GitLab CI
```

## 工作区边界

前端工作区只允许：

```yaml
packages:
  - packages/plugin-*
```

这意味着：

- 不要恢复 `packages/*`
- 不要把 `@yudream/plugin-sdk` 源码拷回本仓
- 不要把 `@yudream/components` 源码拷回本仓
- 不要让插件重新依赖主体仓前端或主体仓 root parent

## 发布流程

插件仓 tag 流水线会负责：

- 构建插件前端 remote 模块
- 构建插件后端 JAR
- 校验最终 JAR 中已包含 `remoteEntry.js`
- 将插件 JAR 和插件 catalog 发布到 Nexus `maven-releases`
- 重新回读并校验 `sha256sum.txt`、`plugins.manifest.tsv` 和每个 JAR

相关文档：

- [插件仓发布说明](docs/plugin-release.md)
- [远端发布验收记录](docs/remote-release-evidence.md)
- [迁移检查清单](docs/migration-checklist.md)
- [边界清单](docs/staging-boundaries.md)

## 为什么拆成独立仓

独立插件仓的目标不是“把代码挪个位置”，而是让插件真正具备独立发布与独立演进能力：

- 主体仓只维护平台核心与契约包
- 插件仓只维护业务插件
- 插件通过正式发布的 SPI / SDK / Components 与主体仓通信
- 插件可以独立构建、独立发布、独立验证

## 贡献

欢迎为现有插件提交 Issue 或 Pull Request。

如果改动涉及 SPI、SDK、共享组件版本，请同步确认：

- 主体仓对应契约已经发布
- 本仓 `pom.xml` 与 `yudream-frontend/pnpm-workspace.yaml` 已同步版本
- CI 校验脚本仍能通过
