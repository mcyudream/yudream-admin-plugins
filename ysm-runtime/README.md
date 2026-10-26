# 玩家模型运行端

YDA 管理通用 `schema=1` 模型、贴图、角色装备关系和下载授权，不储存 Minecraft 版本。上传后立即转换并可预览，没有审核或待发布状态。

## 模块边界

| 模块 | 职责 | Java |
| --- | --- | --- |
| core | ZIP 转换、通用 bundle、骨骼 / UV / 数值动画、协议、下载缓存与热更新状态机 | 21 |
| server-folia | 网站桥接、观看者快照、Folia 调度；`mc26_3` 包负责虚拟实体包与资源包导出 | 25 |
| fabric-26_3 | 网络 / 纹理 / Blaze3D 渲染与 Mixin 适配 | 25 |

当前运行端适配 Minecraft 26.3。升级游戏时新增对应适配模块，复用 core 和网站数据；只有通用数据结构或协议语义改变时才升级 schema / protocol。网站无需增加游戏版本分支。

## 安装

1. YDA 安装 `yudream-skin` 和 `ysm-models`。设置环境变量 `YSM_PUBLIC_BASE_URL`（网站公网根 URL）与 `YSM_BRIDGE_TOKEN`（至少 32 字符随机密钥）。
2. Folia 安装 `YudreamModels-folia-26.3-0.1.0.jar`，填写生成的 `plugins/YudreamModels/config.yml`：`website-url`、相同 `bridge-token`，以及玩家可访问的 `pack-host.public-url`。可用反向代理把 HTTPS 下载转发至 `pack-host.bind/port`。
3. 玩家在网站「我的模型」上传明文 ZIP，预览后选择本人皮肤站角色和贴图并装备。管理员可把模型加入服务器共享库、停用或删除模型。
4. 可选安装匹配版本的 `YudreamModels-fabric-26.3-0.1.0.jar`、Fabric Loader 与 Fabric API。

服务器 bridge token 仅用于服务器到网站的请求头。玩家下载地址使用限时签名，不包含 bridge token。模型内容按 SHA-256 校验与缓存；网页业务 HTTP 使用宿主 SDK。

## 玩家操作

- `/model status`：模式、资源加载结果、快照模型数量、资源包发送次数。
- `/model refresh`：原版手动获取新快照；默认冷却 60 秒，相同版本不重复发送。权限 `ysm.refresh`，默认所有玩家可用。
- `/model self on` / `/model self off`：原版自身模型预览开关。默认关闭以避免第一人称遮挡；开启后切换第三人称查看。服务端无法得知原版玩家的相机模式。
- 模型与贴图选择在网站完成。恢复皮肤外观不要求刷新资源包。

原版进服加载本人和服务器启用模型的一份快照，网站更新不会自动推送新包；旧快照继续使用旧版本。模型不在已加载快照内时回退皮肤。手动刷新会触发原版资源重载；为保证失败后仍有旧包，新包成功后才清理本插件旧包，这可能发生两次重载。不会移除其他插件的资源包。

Fabric 客户端按实际装备下载 bundle，在后台解码，在渲染线程注册局部纹理；准备完成后先通知服务器，服务器移除该观看者的原版替身并发送激活确认，客户端才切换。更新失败继续旧外观。此路径没有调用全局资源重载。每个客户端最多保留 64 个已准备或正在准备的不同模型；离线角色与恢复皮肤会释放装备引用，闲置资源按需淘汰。

## 支持范围

- 明文 YSM ZIP，一份主几何、box UV，1–16 张同尺寸 PNG；数值 position / rotation / scale，linear / step / Catmull-Rom，pre / post 关键帧。
- 上传 16 MiB、解压 32 MiB、128 骨骼、2048 方块、2048×2048 单贴图及 16 Mi 总像素上限。
- 当前不支持加密 `.ysm`、逐面 UV、Molang、脚本、粒子、音效、完整 YSM 控制器或第一人称自定义手臂。包含不支持的动画表达式会明确拒绝。
- 身体替换隐藏原版装备外观，恢复皮肤或插件停用时恢复装备。真实玩家实体保留，攻击与交互仍走原版。
- 没有生产规模 TPS / FPS 保证；资源快照缓存最多 256 份，达到上限后保留当前外观并提示失败，可在维护时重启清理内存缓存。磁盘历史包 / bundle 需按运维保留策略清理。

## 构建与验证

网站 Maven 构建只依赖 core，不下载 Minecraft。先构建 `@yudream/plugin-ysm-models` 前端，再执行：

```bash
mvn -pl yudream-plugins/yudream-plugin-ysm-models -am test package
```

运行端使用 Java 25：

```bash
cd ysm-runtime
./gradlew :core:test :server-folia:jar :fabric-26_3:jar
```

Fabric 的 `prepareMinecraft` 下载官方 client / libraries 并验证 SHA-1，只用于编译，不打入分发 JAR。可通过 `minecraftClientJar`、`minecraftLibraries`、`fabricApiJar`、`fabricLoaderJar`、`mixinJar` Gradle 属性使用现有本地缓存；不会把本机路径写入源码。

预览复用了 Apache-2.0 项目 YSM Model Manager 的纯几何 / 动画算法，固定来源、改动与许可证见前端 `third-party/`，随最终 JAR 分发。没有引入桌面文件管理器、YSMParser WASM 或 MolangJS。

实测范围和环境限制见 [验证记录](VERIFICATION.md)。
