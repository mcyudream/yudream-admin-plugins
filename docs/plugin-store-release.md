# 在线插件商店发布说明

## 目标与边界

插件发布同时维护两类制品：

- **Maven Release 制品**：可部署的插件 JAR 发布到 Maven releases；商店不复制 JAR。
- **Nexus Raw 商店目录**：发布客户端可直接读取的 JSON 索引、版本 descriptor 和可选展示资源，用于发现插件、版本及其 Maven JAR 的校验信息。

Raw 商店默认根地址为：

```text
https://nexus.yudream.online/repository/plugin-store-releases
```

可通过 `NEXUS_PLUGIN_STORE_URL` 覆盖。该变量应指向 Raw 仓库根目录，不应附加插件、版本或 `index.json` 子路径。

## Raw 目录约定

以插件代码 `project-progress`、版本 `1.2.3` 为例，Raw 仓库中的公开文件为：

```text
index.json
plugins/project-progress/index.json
plugins/project-progress/versions/1.2.3.json
plugins/project-progress/assets/icon.svg
plugins/project-progress/assets/screenshots/overview.png
```

- 根 `index.json` 是商店入口，列出可发现的插件。
- `plugins/{pluginCode}/index.json` 是单插件索引，列出该插件的已发布版本及 descriptor。
- `plugins/{pluginCode}/versions/{version}.json` 是版本 descriptor；发布脚本从最终 JAR 生成它。

`pluginCode` 和 `version` 均从最终 JAR 内的 `plugin.yml` 读取，其中 `name` 是插件代码。每个被选中 JAR 的 `plugin.yml version` 必须与去掉可选 `v` 前缀后的 `CI_COMMIT_TAG`（或 `PLUGIN_PACKAGE_VERSION`）精确一致；否则脚本在上传前失败，避免 Raw descriptor、Maven 坐标和 JAR 内元数据漂移。未被本次 release 选中的插件不参与版本一致性检查，也不要求跟随升版。展示名称不能替代 `pluginCode` 作为目录名、依赖标识或查找键。

最终 JAR 的选择逻辑与 Maven 发布复用 `ci/lib/plugin-jar-selection.sh`：优先 `dist/plugins/` 中的最终包；不存在时才从各模块 `target/` 选择，并优先 `*-shaded.jar`。当启用 release-only 选择（见下节）时，仅保留属于本次 release 模块列表的 JAR，`-am` 顺带构建的依赖模块 JAR 不会进入暂存或发布。

## 显式 release 模块列表

Tag 发布是**显式选择性发布**：受保护的 `v*` tag 流水线只打包、发布和回读 `release/plugins.txt` 中列出的 Maven artifactId（每行一个，对应 `yudream-plugins/` 下的根 reactor 模块）。发布提交必须同时编辑该文件；列表为空、重复条目或不属于根 reactor 的模块都会使 `ci/verify-plugin-release-selection.sh` 校验失败。不做基于 git diff 的“改动模块自动检测”，也不存在共享变更自动联动升版——发布范围完全由该列表决定。

- 分支与普通本地构建忽略该列表，始终构建全部 reactor 模块并保留全部 JAR 行为。
- Tag 打包使用 `mvn clean package -pl :<选中的 artifactId 列表> -am` 仅构建选中模块及其 reactor 依赖，随后只把选中模块的最终 JAR 复制进 `dist/plugins/`。
- 同一选择同时驱动 Maven JAR 发布、Raw 商店 descriptor/catalog 生成以及两个发布后回读脚本；tag 上下文中这些脚本自动启用 release-only 选择。
- 可用 `PLUGIN_RELEASE_MODULES`（逗号或空格分隔的 artifactId 列表）临时覆盖列表，或用 `PLUGIN_RELEASE_ONLY=1` 在非 tag 环境强制按列表选择；两者都经过同样的白名单/去重/非空校验。
- 校验脚本：`sh ci/verify-plugin-release-selection.sh`。提供 `CI_COMMIT_TAG` 或 `PLUGIN_PACKAGE_VERSION` 时，还会读取每个选中 JAR 的 `plugin.yml version` 并要求与 tag 版本一致；未提供版本时仅校验列表本身（fixture/本地用法）。

## JSON 契约

所有商店文件均为 UTF-8 JSON，使用相对路径引用同一 Raw 仓库内的文件。根索引、插件索引和版本 descriptor 至少保留以下语义：

- 插件标识：`pluginCode`（或 descriptor 内明确的插件代码字段）。
- 版本标识：`version` / `releaseVersion`。
- descriptor 路径：`plugins/{pluginCode}/versions/{version}.json`。
- 安装 JAR：descriptor 提供 Maven releases 中对应 JAR 的 URL 与 Maven 坐标。
- 完整性：descriptor 提供该 JAR 的小写十六进制 `sha256`，并与 Maven releases 中的 JAR 字节完全一致。
- 插件元数据：descriptor 的 `plugin` 对象写入 JAR 内实际存在的 `name`、`version`、`main`、`displayName`、`description`，官方受控的 `publisher`，以及可选商店资源、`license`、`source`、`releaseNotes`、`compatibility` 与 `dependencies`；不编造缺失字段。

`compatibility` 和 `dependencies` 必须位于 descriptor 的 `plugin` 对象内，例如：

```json
{
  "plugin": {
    "code": "project-progress",
    "version": "1.2.3",
    "main": "example.ProjectProgressPlugin",
    "compatibility": { "host": "^1.0.0" },
    "dependencies": [
      { "code": "provider-plugin", "range": "~1.2.3", "required": true }
    ]
  }
}
```

客户端应将未知字段视为可前向兼容的扩展，而不应据此拒绝完整 descriptor。

### 可选展示元数据、商店资源与兼容性约定

每个官方生成 descriptor 都会由生成器写入固定的 `plugin.publisher`：`{ "id": "yudream", "name": "YuDream", "url": "https://yudream.online", "verified": true }`。官方 `store.json` 不允许覆盖 publisher。第三方投稿的 `author` 仅在审核/发布流程确认后映射为 publisher，且作者不能自行提供 `verified`。历史 descriptor 不含该字段仍可读取。

官方 `store.json` 可选声明 `license`（受支持 SPDX 标识）、`source`（无 userinfo/fragment 的 HTTPS `repository` 和 40 位小写十六进制 `commit`）及无控制字符、受长度限制的 `releaseNotes`，生成器将其写入 `plugin.license`、`plugin.source` 和 `plugin.releaseNotes`。`submission.json` 的 `license` 则始终是本地许可证文本路径；第三方使用可选 `licenseId` 提供展示用 SPDX 标识。每个新生成的 descriptor 都会写入 `plugin.compatibility` 与 `plugin.dependencies`。默认 compatibility 为 `host: ^1.0.0`、`spi: ^2.6.0`、`frontendSdk: ^1.0.1`；默认 dependencies 从最终 JAR 内的 `plugin.yml` 派生。模块可选地在 `src/main/resources/store.json` 声明商店资源，并显式覆盖兼容性范围或依赖范围。历史 descriptor 未包含这些字段时仍可被读取。支持的完整结构如下：

```json
{
  "icon": "assets/icon.svg",
  "screenshots": ["assets/overview.png"],
  "compatibility": {
    "host": "^1.0.0",
    "spi": ">=2.6.0 <3.0.0",
    "frontendSdk": "1.0.x"
  },
  "dependencies": [
    { "code": "provider-plugin", "range": "~1.2.3", "required": true }
  ]
}
```

- `icon` 是非空字符串、`screenshots` 是非空字符串数组；两者都是相对于模块 `src/main/resources/` 的本地文件路径。资源只从该目录复制，且必须是常规文件。源路径不得为空、绝对、包含 `..`、反斜杠或 `//`；符号链接及任何解析后逃离 resources 根目录的文件均被拒绝。
- `compatibility` 是可选对象，只允许可选的 `host`、`spi`、`frontendSdk` 字符串范围，分别声明宿主、插件 SPI 和前端 SDK 版本要求；已声明的键覆盖默认值，未声明的键仍使用默认值。
- `dependencies` 是可选数组；每项必须恰好具有非空 `code`、`range` 和布尔 `required`。同一 `code` 不得重复。`required: true` 表示安装前必须已有满足范围的本地插件；`false` 仅表示可选集成状态，不触发自动安装。
- 未声明 `dependencies` 时，生成器从最终 JAR 的 `plugin.yml` 生成：`depend` 条目映射为 `required: true`，`softdepend` 映射为 `false`，每个范围为 `^` 加该 JAR 的 `plugin.yml version`；无依赖时生成空数组。若 `store.json.dependencies` 显式覆盖范围，其 code 集合和 `required` 语义必须与 `plugin.yml` 完全一致，否则生成失败。
- `compatibility` 和 `dependencies[].range` 只允许与宿主 `SemVerRange` 一致的稳定版受限语法：精确 `1.2.3`、`^1.2.3`、`~1.2.3`、`>=1.2.3 <2.0.0` 交集，以及 `1.x`、`1.2.x`（通配符仅接受 `x`/`X`，不接受 `*`）。预发布/构建标识、`||`、Maven 方括号或圆括号范围及其他格式均在 descriptor 生成时拒绝。
- 生成器拒绝未知 `store.json` 或 `compatibility` 字段，避免把拼写错误发布为不可执行的安装契约。
- 资源在 descriptor 中使用 `plugins/{pluginCode}/assets/{sourcePath}` 路径。图标、截图和 descriptor 均先于索引上传；每个插件索引随后上传，根 `index.json` 始终最后上传。

## 索引保留与发布顺序

非 `DRY_RUN` 发布会先从 Raw 公开地址读取已有根索引和本次受影响插件的索引。HTTP 404 按空索引处理；其他 HTTP、JSON 或索引结构错误会使发布失败。发布时把当前 release 条目合并进已有 entries，保留未受影响插件和历史版本，避免局部发布覆盖历史可发现性。

完整顺序为：

1. Maven JAR 发布；
2. Raw 资源与版本 descriptor 上传；
3. 受影响的插件索引上传；
4. 根 `index.json` 最后上传。

`DRY_RUN` 不读取网络、不上传且不需要凭据；它只生成当前本地内容并输出上述模拟上传顺序。

## CI 变量与凭据

只有受保护的 `v*` tag 才能运行 Maven/Raw 写入和发布后回读 jobs；普通分支与 Merge Request 不会运行这些 jobs。Raw 商店写 job 使用 CI `resource_group` 串行化，避免并发索引写入互相覆盖。第三方投稿只运行离线材料校验，审核通过后仍由受保护 tag 或受保护的手动发布者代发，且每个已发布的 `{pluginCode, version}` 不可变。

Tag 发布流水线使用：

| 变量 | 用途 |
| --- | --- |
| `CI_COMMIT_TAG` | 默认版本来源；脚本去掉前导 `v`。tag 上下文自动启用 release-only 选择。 |
| `PLUGIN_PACKAGE_VERSION` | 可选地覆盖版本来源。 |
| `PLUGIN_RELEASE_ONLY` | 为 `1` 时按 `release/plugins.txt` 限制打包/发布/回读范围；tag job 自动设置。 |
| `PLUGIN_RELEASE_MODULES` | 可选地覆盖 `release/plugins.txt`（逗号或空格分隔 artifactId），仍受白名单/去重/非空校验。 |
| `NEXUS_MAVEN_RELEASES_URL` | Maven JAR 发布地址。 |
| `NEXUS_MAVEN_PUBLIC_URL` | Maven 公开回读地址。 |
| `NEXUS_PLUGIN_STORE_URL` | Raw 商店发布及公开回读根地址。 |
| `NEXUS_USERNAME` | GitLab 受保护 CI 变量提供的 Nexus 用户名。 |
| `NEXUS_PASSWORD` | GitLab 受保护 CI 变量提供的 Nexus 密码。 |
| `DRY_RUN` | 非空时仅本地生成、验证和顺序演示。 |

凭据只允许由 GitLab 受保护 CI 变量提供。不得将密码、token、认证 header、带凭据 URL 或本机 `settings.xml` 提交到仓库、写入文档或输出到日志。Raw JSON 和展示资源应保持公开可读，不能携带认证信息。

## 发布与回读流程

GitLab CI 中 `publish:plugin-store` 依赖 `publish:plugin-jars`，`verify:published-plugin-store` 依赖前者。实际 tag 发布等价于：

```sh
sh ci/publish-plugin-jars.sh
sh ci/publish-plugin-store.sh
sh ci/verify-published-plugin-store.sh
```

回读验证根据本地最终 JAR 重新计算当前 release 的 descriptor、资源和 JAR SHA-256，然后：

1. 回读并比较当前 descriptor 与资源；
2. 验证已发布插件索引和根索引包含当前 version/plugin entry，不把本地当前局部索引等同于已合并的全量远端索引；
3. 从 Maven public 回读 descriptor 指向的 JAR，并验证其 SHA-256 与 descriptor、最终本地 JAR 一致。

## 本地 dry-run

`DRY_RUN` 用于在不上传、无需凭据且不访问网络的情况下检查选包、JSON/兼容性与依赖契约生成、资源规则和发布顺序。tag dry-run 与真实 tag 发布一样只处理 `release/plugins.txt`（或 `PLUGIN_RELEASE_MODULES`）选中的模块：

```powershell
$env:CI_COMMIT_TAG='v1.0.3' # 必须与每个选中最终 JAR 内 plugin.yml version 一致
$env:PLUGIN_RELEASE_MODULES='yudream-plugin-web-card' # 可选：只 dry-run 指定模块
$env:DRY_RUN='1'
sh ci/verify-plugin-release-selection.sh
sh ci/publish-plugin-store.sh
sh ci/verify-published-plugin-store.sh
```

dry-run 仍需要可供选择的本地最终插件 JAR，且其内嵌 `plugin.yml version` 必须与指定版本一致。未选中的模块不检查版本，因此仓库中同时存在 `1.0.0` 与 `web-card` 的 `1.0.3` 等混合版本时，可用 `PLUGIN_RELEASE_MODULES` 或裁剪后的 `release/plugins.txt` 对目标子集做 dry-run；但未选中模块绝不会被打包、发布或写入商店索引。dry-run 不是线上发布成功的证明。

## 发布后公开回读

实际 tag 发布完成后，在不设置 `DRY_RUN`、不使用凭据的环境执行：

```sh
sh ci/verify-published-plugin-store.sh
```

该命令从公开 Raw 地址回读每个当前版本 descriptor 并与本地生成的 JSON 作结构化比较，因此会确认客户端可见的 `plugin.compatibility` 和 `plugin.dependencies` 被解析接受；同时回读 Maven JAR 并校验 SHA-256。需要人工定位单个 descriptor 时，可使用：

```sh
curl -fsSL "${NEXUS_PLUGIN_STORE_URL%/}/plugins/{pluginCode}/versions/{version}.json" | python -m json.tool
```

回读失败应阻断发布结论；本地 dry-run 不能替代该线上公开读取检查。
