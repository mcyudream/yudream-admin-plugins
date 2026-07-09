# 插件仓发布说明

## 目标

`yudream-admin-plugins` 现在不只负责独立构建，还负责独立发布可部署插件 JAR。

## CI 流程

`.gitlab-ci.yml` 现在分成五段：

1. `validate`
   - 校验插件仓没有重新耦合回主体仓
   - 校验核心 Maven 契约可从远程 GitLab Maven Registry 解析
   - 校验插件 POM 不会写死仓库地址、系统路径，也不会回依赖主体实现模块
   - 校验核心 npm 契约可从配置的 registry 独立安装
   - 校验插件仓自己的 JAR 发布/回读流水线没有被改瘦
2. `build-frontend`
   - 构建所有 `@yudream/plugin-*` 前端包
3. `package-plugin`
   - 使用核心仓发布的 `yudream-plugin-spi` 执行 `mvn clean package -DskipTests`
   - 使用单独的干净 Maven 本地仓目录重新解析依赖，避免插件打包阶段误吃旧缓存
   - 校验最终插件 JAR 内确实带有 `META-INF/yudream-plugin/frontend/*/remoteEntry.js`
4. `publish-plugin`
   - 仅在 Git tag 流水线执行
   - 把最终插件 JAR 上传到当前插件仓自己的 GitLab Generic Package Registry
5. `verify-publish`
   - 在发布完成后重新从 GitLab Generic Package Registry 回读
   - 校验 `sha256sum.txt`、`plugins.manifest.tsv` 与每个插件 JAR 都与本次构建产物一致

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

`ci/publish-plugin-jars.sh` 会为每个插件模块只选择一个最终包：

- 如果模块产出 `*-shaded.jar`，优先发布这个包
- 否则发布普通 `*.jar`

同时额外上传两个索引文件：

- `sha256sum.txt`
- `plugins.manifest.tsv`

`ci/verify-published-plugin-jars.sh` 会在 tag 流水线发布后再次下载这些文件，并逐个核对：
- 索引文件内容没有漂移
- 每个已发布 JAR 都可以重新读取
- 重新读取到的 JAR 校验和与本次构建产物完全一致

`ci/verify-plugin-jar-assets.sh` 现在还会额外校验：
- 最终插件 JAR 中不包含 `online/yudream/base/plugin/spi/*` 类文件
- 也就是插件产物不会把主体 SPI 实现契约重新打进自己的 JAR

## 默认发布地址

发布目标为当前插件仓项目自己的 Generic Package Registry：

```text
${CI_API_V4_URL}/projects/${CI_PROJECT_ID}/packages/generic/yudream-admin-plugins/${CI_COMMIT_TAG}/
```

例如某个插件包会落到：

```text
.../packages/generic/yudream-admin-plugins/v1.0.0/yudream-plugin-project-progress-1.0-SNAPSHOT.jar
```

## 需要的变量

GitLab tag 流水线默认只依赖：

- `CI_API_V4_URL`
- `CI_PROJECT_ID`
- `CI_COMMIT_TAG`
- `CI_JOB_TOKEN`

可选变量：

- `PLUGIN_GENERIC_PACKAGE_NAME`
- `PLUGIN_PACKAGE_VERSION`

## 本地 dry-run

可以在不真正上传的情况下验证脚本选包和目标 URL：

```powershell
$env:CI_API_V4_URL='https://gitlab.yudream.online/api/v4'
$env:CI_PROJECT_ID='123'
$env:CI_COMMIT_TAG='v0.0.0-dryrun'
$env:DRY_RUN='1'
& 'C:/Program Files/Git/bin/sh.exe' ci/publish-plugin-jars.sh
```

## 常用校验

```powershell
& 'C:/Program Files/Git/bin/sh.exe' ci/verify-plugin-repo-independence.sh
& 'C:/Program Files/Git/bin/sh.exe' ci/verify-plugin-maven-boundary.sh
& 'C:/Program Files/Git/bin/sh.exe' ci/verify-core-npm-contracts.sh
& 'C:/Program Files/Git/bin/sh.exe' ci/verify-plugin-jar-assets.sh
```

本地也可以先做一次发布后校验的 dry-run：

```powershell
$env:CI_API_V4_URL='https://gitlab.yudream.online/api/v4'
$env:CI_PROJECT_ID='123'
$env:CI_COMMIT_TAG='v0.0.0-dryrun'
$env:DRY_RUN='1'
& 'C:/Program Files/Git/bin/sh.exe' ci/verify-published-plugin-jars.sh
```
