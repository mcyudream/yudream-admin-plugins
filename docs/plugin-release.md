# 插件仓发布说明

## 目标

`yudream-admin-plugins` 现在不只负责独立构建，还负责独立发布可部署插件 JAR。

## CI 流程

`.gitlab-ci.yml` 现在分成四段：

1. `validate`
   - 校验插件仓没有重新耦合回主体仓
   - 校验核心 Maven 契约可从 Nexus `maven-public` 解析（只读 SPI/SDK，不再写插件制品）
   - 校验插件 POM 不会写死仓库地址、系统路径，也不会回依赖主体实现模块
   - 校验核心 npm 契约可从配置的 registry 独立安装
   - 校验插件仓自己的市场发布流水线没有被改瘦
2. `build-frontend`
   - 构建所有 `@yudream/plugin-*` 前端包
3. `package-plugin`
   - 使用核心仓发布的 `yudream-plugin-spi` 执行 `mvn clean package -DskipTests`
   - 使用单独的干净 Maven 本地仓目录重新解析依赖，避免插件打包阶段误吃旧缓存
   - 校验最终插件 JAR 内确实带有 `META-INF/yudream-plugin/frontend/*/remoteEntry.js`
4. `publish-plugin`
   - 仅在受保护 `v*` tag 流水线执行
   - `publish:market` 把 `release/plugins.txt` 选择结果上传到自托管 YuDream 插件市场源
   - **不再**把插件 JAR 或 Raw catalog 上传到 Nexus

## 前端工作区边界

插件仓前端工作区应保持为：

```yaml
packages:
  - packages/plugin-*
```

不要把 `packages/*` 放回来，也不要把 `@yudream/plugin-sdk`、`@yudream/components` 的源码复制进插件仓。

插件仓 CI 的前端构建入口也应只匹配：
`yudream-frontend/packages/plugin-*/package.json`

## 发布产物

打包阶段为每个插件模块只选择一个最终包：

- 如果模块产出 `*-shaded.jar`，优先发布这个包
- 否则发布普通 `*.jar`

`ci/verify-plugin-jar-assets.sh` 还会额外校验：
- 最终插件 JAR 中不包含 `online/yudream/base/plugin/spi/*` 类文件
- 也就是插件产物不会把主体 SPI 实现契约重新打进自己的 JAR

插件版本独立于 tag：受保护 `v*` tag 只是发布事件的标记/触发器；每个被选中插件使用自己的 `plugin.yml version`。

## 默认发布地址

插件制品发布到自托管市场源（`POST /api/platform/plugin-market-source/publications`），不再写入 Nexus `maven-releases` 或 `plugin-store-releases`。Nexus 仍只用于**读取**核心 SPI / SDK 契约。

## 需要的变量

GitLab 构建/校验默认只依赖只读 Nexus 地址（已写进 yaml）：

- `NEXUS_MAVEN_PUBLIC_URL`
- `NEXUS_NPM_PUBLIC_URL`

`CI_COMMIT_TAG` 由流水线自己带。可选：`PLUGIN_PACKAGE_VERSION`。

## 自托管市场源变量

`publish:market` 把同一份 `PLUGIN_RELEASE_ONLY=1` 选择结果上传到自托管市场源。认证走 `X-API-Key`，**禁止**使用 `NEXUS_USERNAME` / `NEXUS_PASSWORD`。未配置 `YUDREAM_MARKET_URL` 时该 job 不调度。

在 GitLab 项目（或群组）CI/CD Variables 中配置：

| 变量 | 必填 | GitLab 选项 | 用途 |
| --- | --- | --- | --- |
| `YUDREAM_MARKET_URL` | 是（启用市场发布时） | Protected | 宿主根地址，如 `https://yudream.example.com`，不要带 `/api/...` |
| `YUDREAM_MARKET_API_KEY` | 是（启用市场发布时） | Protected + Masked | 具备 `platform:plugin-market-source:upload` 的 API Key；建议同时勾选 `publish` 以跳过审核 |
| `YUDREAM_MARKET_RELEASE_NOTES` | 否 | 可选 Masked | 覆盖 JAR 内 `store.json` 的单行发布说明 |

分类、标签与许可证等社区元数据写在各插件自己的 `store.json`，不要配仓库级 CI 变量。`store.json` 允许的字段包括 `license`、`category`、`tags`、`releaseNotes`、`compatibility`、`dependencies`、`icon`、`screenshots`、`source`。

调度条件：受保护 `v*` tag，且 `YUDREAM_MARKET_URL` 非空。`resource_group: yudream-plugin-market` 串行。`{code}@{pluginVersion}` 不可覆盖，重复版本会 400。

## 本地 dry-run

可以在不真正上传的情况下验证脚本选包和市场请求：

```powershell
$env:CI_COMMIT_TAG='v0.0.0-dryrun'
$env:PLUGIN_RELEASE_ONLY='1'
$env:DRY_RUN='1'
sh ci/publish-to-market.sh
```

## 常用校验

```powershell
sh ci/verify-plugin-repo-independence.sh
sh ci/verify-plugin-maven-boundary.sh
sh ci/verify-core-npm-contracts.sh
sh ci/verify-plugin-jar-assets.sh
sh ci/verify-plugin-publish-pipeline.sh
sh ci/verify-plugin-store-catalog.sh
sh ci/verify-plugin-release-selection.sh
```
