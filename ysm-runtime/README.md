# 玩家模型运行端

YDA 管理通用 `schema=2`（兼容读取 schema 1）模型、贴图、角色装备关系和下载授权，不储存 Minecraft 版本。上传后立即转换并可预览；管理员可关闭上传或启用审核，待审核模型通过后才可装备与同步。

## 模块边界

| 模块 | 职责 | Java |
| --- | --- | --- |
| core | 已解码文件 / ZIP 转换、通用 bundle、骨骼 / UV / 受限表达式动画、协议、下载缓存与热更新状态机 | 21 |
| server-folia | 网站桥接、观看者快照、Folia 调度；`mc26_3` 包负责虚拟实体包与资源包导出 | 25 |
| fabric-26_3 | 网络 / 纹理 / Blaze3D 渲染与 Mixin 适配 | 25 |

当前运行端适配 Minecraft 26.3。升级游戏时新增对应适配模块，复用 core 和网站数据；只有通用数据结构或协议语义改变时才升级 schema / protocol。网站无需增加游戏版本分支。

## 安装

本次发布的文件对应关系、环境变量、完整配置示例和客户端依赖见 [中文安装配置](INSTALL.zh-CN.md)。

1. YDA 安装 `yudream-skin` 和 `ysm-models`。设置环境变量 `YSM_PUBLIC_BASE_URL`（网站公网根 URL）与 `YSM_BRIDGE_TOKEN`（至少 32 字符随机密钥）。
2. Folia 安装 `YudreamModels-folia-26.3-0.2.0.jar`，填写生成的 `plugins/YudreamModels/config.yml`：`website-url`、相同 `bridge-token`，以及玩家可访问的 `pack-host.public-url`。可用反向代理把 HTTPS 下载转发至 `pack-host.bind/port`。
3. 玩家在网站「我的模型」上传原始 `.ysm` 或明文 ZIP，预览后选择本人皮肤站角色和贴图并装备。管理员可把模型加入服务器共享库、停用或删除模型。
4. 可选安装匹配版本的 `YudreamModels-fabric-26.3-0.2.0.jar`、Fabric Loader 与 Fabric API。

服务器 bridge token 仅用于服务器到网站的请求头。玩家下载地址使用限时签名，不包含 bridge token。模型内容按 SHA-256 校验与缓存；网页业务 HTTP 使用宿主 SDK。

## 玩家操作

- `/model status`：模式、资源加载结果、快照模型数量、资源包发送次数。
- `/model refresh`：原版手动获取新快照；默认冷却 60 秒，相同版本不重复发送。权限 `ysm.refresh`，默认所有玩家可用。
- `/model self on` / `/model self off`：原版自身模型预览开关。默认关闭以避免第一人称遮挡；开启后切换第三人称查看。服务端无法得知原版玩家的相机模式。
- 模型与贴图选择在网站完成。恢复皮肤外观不要求刷新资源包。

原版进服加载本人和服务器启用模型的一份快照，网站更新不会自动推送新包；旧快照继续使用旧版本。模型不在已加载快照内时回退皮肤。手动刷新会触发原版资源重载；为保证失败后仍有旧包，新包成功后才清理本插件旧包，这可能发生两次重载。不会移除其他插件的资源包。

Fabric 客户端按实际装备下载 bundle，在后台解码，在渲染线程注册局部纹理；准备完成后先通知服务器，服务器移除该观看者的原版替身并发送激活确认，客户端才切换。更新失败继续旧外观。此路径没有调用全局资源重载。每个客户端最多保留 64 个已准备或正在准备的不同模型；离线角色与恢复皮肤会释放装备引用，闲置资源按需淘汰。

## 支持范围

- 原始 `.ysm` 由网站内置 YSMParser WASM 在 Java 中解析；明文 ZIP 支持嵌套目录、`ysm.json` 指定路径或唯一主几何识别。主贴图按 `files.player.texture` 选择，附属弹射物贴图不混入。
- 主模型保留 `width_scale` / `height_scale` 的宽高比例。
- box / 逐面 UV，缺省面隐藏、负 UV 尺寸、90 度 UV 旋转；1–16 张同尺寸 PNG；position / rotation / scale、linear / step / Catmull-Rom 与 pre / post 关键帧。
- 上传 16 MiB、展开 32 MiB、512 骨骼、2048 方块、2048×2048 单贴图及 16 Mi 总像素上限。原始 YSM 解码串行、30 秒超时、独立内存上限 256 MiB，无文件系统 / 网络权限。
- Molang 使用有界 AST 支持算术、比较、条件、变量赋值、角度制数学及动画时间。默认外观变量为 0（player_size 为 1），健康 / 饥饿中立值为 20，其他游戏查询为 0；`second_order` 按目标值近似。零时刻时间线赋值和 `pre_parallel0..7` / `parallel0..7` 默认层参与播放；自定义控制器、外观配置菜单、完整物理、声音、粒子、附属实体和第一人称手臂未复现。不能解析的表达式使用通道默认值并在预览显示具体兼容说明。
- 源模型负尺寸方块规范化为等价正尺寸包围盒，这些方块的 UV 朝向可能变化；预览显示说明。
- 新上传资产使用 schema 2；请同时升级网站 0.4.0、Folia / Fabric 0.2.0。运行端 0.2.0 可继续读取旧 schema 1；旧运行端拒绝 schema 2 并保留旧外观。
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

预览复用了 Apache-2.0 项目 YSM Model Manager 的纯几何 / 动画算法，固定来源、改动与许可证见前端 `third-party/`，随最终 JAR 分发。网站解析器的二进制来源、固定提交、SHA-256、构建说明和许可证随 JAR 的 `ysm-parser/` 分发。运行端不包含解析器，也没有引入 MolangJS 或通用脚本引擎。

实测范围和环境限制见 [验证记录](VERIFICATION.md)。
