# 第三方插件市场投稿

## 投稿边界

第三方作者只能通过 Merge Request 提交投稿材料；MR CI 仅做离线校验，绝不提供 Nexus 写入凭据，也不会上传任何制品。官方审核人负责源码、许可证、依赖、JAR 内容与安全审查；只有维护者创建的受保护 `v*` 标签（或后续等价的受控手工发布流程）可调用官方 Maven/Raw 发布脚本。

投稿即使校验通过，也不代表发布批准。

## 目录与材料

将一份完整投稿置于：

```text
submissions/third-party/{plugin-code}-{version}/
├── submission.json
├── plugin.yml
├── store.json
├── plugin.jar
├── LICENSE
└── assets/
```

可从 `templates/plugin-repo/` 复制样例。`submission.json` 必须固定引用 `plugin.yml`、`store.json`、`plugin.jar`，并提供小写 SHA-256、许可证路径以及与 `store.json` 图标/截图完全相同且顺序一致的资源清单。

`license` 始终是投稿目录中的非空本地许可证文件路径；可选 `licenseId` 是受支持的 SPDX 标识，用于 descriptor 的 `plugin.license` 展示。可选 `author` 仅允许 `id`、`name` 和 HTTPS `url`，审核人将其映射为 descriptor 的 `plugin.publisher`；第三方不能声明 `verified`。可选 `source` 仅允许无 userinfo/fragment 的 HTTPS `repository` 和恰好 40 位小写十六进制 `commit`；可选 `releaseNotes` 为受长度限制、无控制字符的文本。严格 allowlist 会拒绝 publisher、JAR URL/hash、索引、凭据及其他发布控制字段。

`plugin.yml`、`store.json` 与 JAR 中根目录的 `plugin.yml` 是同一发布声明：代码、稳定 SemVer 版本、入口类和依赖语义必须一致。插件代码使用小写 kebab-case；入口类必须为限定 Java 类名；依赖范围只接受商店既有的稳定 SemVer 语法。资源只能是投稿目录内的普通文件，不能使用绝对路径、`..`、反斜杠或符号链接。历史投稿可省略新增展示字段。

JAR 不得包含不安全 archive path、重复 archive entry、`online/yudream/plugin/spi/` 类或 `META-INF/services/online.yudream.plugin.spi.YuDreamPlugin`。不得提交凭据、Nexus 配置或其他秘密。

## 不可变版本与审核

`{plugin-code}@{version}` 是不可变发布标识。发现缺陷时请提交新的稳定版本；不要重新打包、替换或覆盖已审核/已发布版本。审核人应确认：JAR SHA-256、许可证适用性、元数据一致性、依赖范围、SPI 边界、资源版权与安全扫描结果。

本地运行：

```sh
SUBMISSION_DIR="$PWD/submissions/third-party/{plugin-code}-{version}" sh ci/verify-third-party-submission.sh
```

该命令只读取本地文件，不访问网络、不上传制品，也不读取 Nexus 写入凭据。
