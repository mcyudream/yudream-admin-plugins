# YuDream Admin Plugins

## Readiness Audit

```powershell
& 'C:/Program Files/Git/bin/sh.exe' ci/verify-plugin-repo-readiness.sh
```

## Staging Guide

- [docs/staging-boundaries.md](docs/staging-boundaries.md)
- `ci/stage-plugin-repo-foundation.sh`
- `ci/stage-plugin-source-migration.sh`

## Remote Evidence

```powershell
& 'C:/Program Files/Git/bin/sh.exe' ci/verify-plugin-remote-release-evidence.sh
```

- [docs/remote-release-evidence.md](docs/remote-release-evidence.md)

这是 YuDream Admin 官方业务插件的独立仓。

这个仓库当前负责：

- 官方业务插件后端模块
- 官方业务插件前端 remote 模块
- 消费主体仓正式发布的 SPI / SDK / 共享组件
- 独立产出可部署插件 JAR
- 提供与主体仓分离的独立 CI

## 已迁入并验证的插件

以下官方业务插件已经迁入本仓，并完成了本地独立构建验证：

- `yudream-plugin-alipay`
- `yudream-plugin-authlib-injector`
- `yudream-plugin-minecraft-activity-proof`
- `yudream-plugin-minecraft-server`
- `yudream-plugin-project-progress`
- `yudream-plugin-student-info`
- `yudream-plugin-wallet`
- `yudream-plugin-yudream-skin`

当前验证链路包括：

- 前端 `pnpm install --frozen-lockfile`
- 前端 `pnpm -r --filter=@yudream/plugin-* run build`
- 后端 `mvn clean package -DskipTests`
- 最终 JAR 内包含 `META-INF/yudream-plugin/frontend/*/remoteEntry.js`
- tag 发布后会回读校验 Generic Package Registry 中的 JAR 与索引文件

## 依赖的主体契约

插件仓只消费正式发布件，不再依赖主体仓 `root parent` 或主体前端本地源码：

- Maven: `online.yudream.base:yudream-plugin-spi`
- npm: `@yudream/plugin-sdk`
- npm: `@yudream/components`

默认配置：

- 前端默认从 npmjs 消费 `@yudream/*`
- Maven 默认指向核心仓 GitLab Maven Registry：
  - `https://gitlab.yudream.online/api/v4/projects/12/packages/maven`

注意：

- 这里使用的是核心仓数值项目 ID `12`
- Maven consume 配置应优先使用数值项目 ID，而不是 path-encoded 的 `yudream%2Fyudreamadmin`

## 前端工作区边界

插件仓前端 workspace 现在只保留：

```yaml
packages:
  - packages/plugin-*
```

不要恢复 `packages/*`，也不要把 `@yudream/plugin-sdk`、`@yudream/components` 的源码复制进插件仓。

## 当前仓库结构

```text
yudream-plugins/   插件后端模块
yudream-frontend/  插件前端 workspace
docs/              迁移与发布说明
pom.xml            插件仓根 parent / aggregator
.gitlab-ci.yml     插件仓独立 CI
ci/                独立性、契约消费、JAR 产物校验脚本
```

## 当前独立性证据

当前这条拆分链已经具备比较硬的证据：

1. `yudream-plugin-spi` 可从核心仓 GitLab Maven Registry 独立解析
2. `@yudream/plugin-sdk` 与 `@yudream/components` 可从 npm registry 独立安装
3. 插件仓前端 workspace 不再允许宽泛吃入非 `plugin-*` 包
4. 插件仓最终 JAR 会自动校验 `remoteEntry.js` 已被打包进入产物
5. 插件仓 tag 流水线可以把最终插件 JAR 发布到本仓自己的 GitLab Generic Package Registry
6. 发布完成后，流水线会再次从 GitLab Generic Package Registry 回读并核对 JAR 与索引文件

## 常用入口

- [迁移清单](docs/migration-checklist.md)
- [插件仓发布说明](docs/plugin-release.md)
