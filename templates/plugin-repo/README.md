# YuDream 第三方插件投稿模板

此模板用于准备投稿材料，不授予发布权限。作者只能通过 Merge Request 提交一个 `submissions/third-party/{plugin}-{version}/` 目录；审核人验证制品后，才会在受保护标签或未来的受控手工发布流程中代发。

投稿目录必须包含：

- `plugin.yml`：JAR 根目录中同一份元数据的可审阅副本。
- `store.json`：图标、截图、兼容性与依赖的商店元数据。
- `plugin.jar`：已构建的最终 JAR；不得内嵌 SPI 类或 SPI service provider。
- `submission.json`：固定文件名、SHA-256、本地许可证路径、可选展示元数据和资源清单。
- `LICENSE`：适用于该插件的非空许可证文本。

`submission.json.license` 始终是本地许可证文件路径；可选 `licenseId` 是受支持的 SPDX 标识，用于商店展示。可选 `author` 必须恰有 `id`、`name`、HTTPS `url`，审核后映射为 descriptor 的 `plugin.publisher`，作者不能提供 `verified`。可选 `source` 必须恰有无 userinfo/fragment 的 HTTPS `repository` 及 40 位小写十六进制 `commit`；`releaseNotes` 为受长度限制、无控制字符的文本。不得提交或控制 publisher、JAR URL/hash、索引、凭据或任何发布配置。
- `assets/`：`store.json` 声明的普通资源文件。

使用 `sh ci/verify-third-party-submission.sh` 本地校验。校验不联网、不上传，也不读取 Nexus 写入凭据。版本必须是稳定且不可变的 SemVer；同一插件版本不可重新投稿或覆盖。请不要提交账号、令牌、密码或 Nexus 配置。
