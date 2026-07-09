# 插件迁移清单

## 迁移目标

把官方业务插件从主体仓迁入独立插件仓，并满足以下条件：

- 插件后端不再继承主体仓 `root parent`
- 插件前端不再依赖主体仓本地 `workspace:*` 包
- 插件仓可以独立执行前端构建和 Maven 打包
- 最终交付的 JAR 内包含前端 `remoteEntry.js`
- 插件仓可以独立消费主体仓正式发布的 Maven / npm 契约包

## 迁移前提

迁移前先确认以下发布件可用：

1. Maven 契约：
   - `online.yudream.base:yudream-plugin-spi`
2. npm 契约：
   - `@yudream/plugin-sdk`
   - `@yudream/components`

默认核心 Maven endpoint：

```text
https://gitlab.yudream.online/api/v4/projects/12/packages/maven
```

说明：

- `12` 是核心仓在 GitLab 上的数值 `project_id`
- 不要把 Maven endpoint 配成 `projects/yudream%2Fyudreamadmin/packages/maven` 这种 path-encoded 写法

## 前端工作区边界

插件仓前端 workspace 应保持为：

```yaml
packages:
  - packages/plugin-*
```

不要恢复 `packages/*`，也不要把 `@yudream/plugin-sdk`、`@yudream/components` 的源码复制进插件仓。

## 每个插件迁移时需要修改的点

### 1. 后端 `pom.xml`

- 去掉对主体仓 `YudreamAdmin` parent 的继承
- 改为继承插件仓根 `pom.xml`
- 保持只依赖 `yudream-plugin-spi`
- 如有额外技术依赖，可继续保留在插件模块自身

### 2. 前端 `package.json`

- 去掉 `workspace:*`
- 改为版本化依赖，推荐通过 `pnpm-workspace.yaml` 的 catalog 统一锁定：
  - `@yudream/plugin-sdk`
  - `@yudream/components`
- 补齐独立仓需要的显式 devDependencies，例如 `vue`、`vite`、`typescript`、`vue-tsc`

### 3. 前端 `vite.config.ts`

- 直接依赖正式发布的 `@yudream/plugin-sdk/vite-shared`
- 共享包只使用公开入口，不要依赖 `@yudream/plugin-sdk/src/*`、`@yudream/components/src/*` 之类内部路径
- 不再保留本地 shared helper fallback

### 4. JAR 资源打包

- 确认前端 `dist` 会复制到：

```text
META-INF/yudream-plugin/frontend/{pluginCode}
```

## 标准验证步骤

### 1. 安装前端依赖

```powershell
cd yudream-frontend
corepack enable
corepack prepare pnpm@11.9.0 --activate
pnpm --config.engine-strict=false install --frozen-lockfile
```

### 2. 构建所有插件前端

```powershell
pnpm --config.engine-strict=false --config.verifyDepsBeforeRun=false -r --filter=@yudream/plugin-* run build
```

### 3. 打包整个插件仓

```powershell
$env:JAVA_HOME='C:/path/to/your/jdk-21'
$env:Path="$env:JAVA_HOME/bin;$env:Path"
mvn -f pom.xml -DskipTests package -B -e
```

### 4. 校验插件仓独立性

```powershell
sh ci/verify-plugin-repo-independence.sh
```

### 5. 校验核心 Maven 契约可远程解析

```powershell
sh ci/verify-core-maven-registry.sh
```

### 6. 校验核心 npm 契约可独立安装

```powershell
sh ci/verify-core-npm-contracts.sh
```

### 7. 校验最终 JAR 内存在 remoteEntry

```powershell
sh ci/verify-plugin-jar-assets.sh
```

## 当前已迁入插件

- `yudream-plugin-alipay`
- `yudream-plugin-authlib-injector`
- `yudream-plugin-minecraft-activity-proof`
- `yudream-plugin-minecraft-server`
- `yudream-plugin-project-progress`
- `yudream-plugin-student-info`
- `yudream-plugin-wallet`
- `yudream-plugin-yudream-skin`

## 当前剩余工作

1. 在真实 tag 流水线中验证 `publish:plugin-jars`
2. 结合版本治理策略，决定是否把 `yudream-plugin-spi` 固化为非 SNAPSHOT release 版本
