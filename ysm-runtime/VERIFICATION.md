# 玩家模型验证记录

验证日期：2026-10-26。当前适配 Minecraft 26.3，网站数据使用通用 `schema=1`，不包含 Minecraft 版本或资源包格式号。

## 构建与仓库检查

| 检查 | 结果 |
| --- | --- |
| 前端 `test` | 3 项通过；覆盖 pre/post、linear / step / Catmull-Rom、循环、镜像小数 UV 与方块旋转 |
| 前端 `vue-tsc` 与生产构建 | 通过 |
| Maven `test package` | 15 项通过：转换与客户端状态机 9 项、网站业务 6 项 |
| Gradle `:core:test :server-folia:jar :fabric-26_3:jar` | 通过；其中 core 为同一组 9 项测试，不重复累计 |
| 最终网站 JAR | remoteEntry、manifest、CSS、第三方 LICENSE / NOTICE 齐全，并与最终 dist 逐字节一致；未打入宿主 SPI |
| 仓库独立性、Maven 依赖边界、插件依赖无环、开发规范、发布流水线、文档独立性 | 检查通过；UI 审计没有新增问题 |
| 已发布 Maven SPI 解析 | 通过，版本 `2.38.0` |
| 完整 readiness | **未完成**：停在已发布 npm 契约重装步骤并超时；不能据此声明整套 readiness 通过 |

## Minecraft 本地 MCP 实测

环境为独立 Folia 26.3 测试服、Java 25，两个真实 Minecraft 客户端通过本地 MinecraftMcp 操作和截图：

- `YsmTester`：安装本工程 Fabric 客户端、Fabric API 和 MinecraftMcp。
- `YsmObserver`：安装 Fabric API 和 MinecraftMcp，**没有安装本工程客户端**，用于验证原版资源包与虚拟展示实体路径。
- 新工程这轮未再启动完全不含 Fabric / Mod 的官方客户端；不能把观察者等同于完全无 Mod 客户端验收。旧原型的无 Mod 测试属于其他工程，不计入这里。
- 网站 HTTP 使用 mock bridge，模型 bundle 来自实际 `ModelCompiler`。本轮没有完成真实 YDA 宿主的上传、角色选择与装备页面联调。

| 场景 | 实测结果 |
| --- | --- |
| 初次进服 | 两端各发送 1 次资源包；Fabric 客户端显示实体 0，原版显示路径看到 37 个虚拟部件 |
| 服务器实体数量 | 验证辅助插件读取服务器世界，真实 ItemDisplay 数量为 0 |
| Fabric 下载新版本失败 | 保留旧外观；全局资源重载增加 0 |
| Fabric 更新模型 | 新 SHA-256 激活；全局资源重载增加 0；原版观察者继续旧快照 |
| 原版 `/model refresh` | 仅请求者资源包发送次数从 1 变 2；Fabric 保持 1；观察者发生 2 次全局资源重载 |
| 相同版本再次刷新 | 不重复发送资源包 |
| 恢复皮肤 | 原版虚拟实体清零，Fabric 清除模型 |
| 跨 Folia 区域 | 相距 2048 格时解除显示，重新靠近后恢复 37 个部件 |
| 跨维度 | 下界解除并重新建立模型显示，返回主世界后正常 |
| 最终 JAR 停用补测 | 两端清除模型；Fabric 收到 `YSM_RESET`；观察者和 Fabric 第三人称均恢复默认皮肤与已穿戴钻石胸甲；插件错误为 0 |

手动刷新出现两次重载，是因为先添加新包，新包加载成功后再移除本插件旧包，以保留下载失败时的旧资源。使用 `addResourcePack`，不会通过替换全部资源包移除其他插件的包。Fabric 后续更新只注册 / 释放模型局部纹理。

当前样例为 CC0 Default Boy 的数值动画副本，明确移除了 `parallel0` / `parallel1` 两条 Molang 动画。样例有 58 个骨骼、37 个可显示部件、156 个方块。此测试不能证明支持原始样例的全部 YSM 功能；Molang、加密 `.ysm`、逐面 UV、脚本和第一人称自定义手臂仍不支持。

精简机器结果和最终产物 SHA-256 见 [verification/summary.json](verification/summary.json)。原始 MCP 调用记录、完整结果和日志保存在本地 `/home/yudream/code/plugins/ysm-verification/runtime/evidence/`，未把运行配置或密钥加入仓库。

### 截图

原版显示路径，真实角色已经穿戴胸甲，模型显示时隐藏原版身体与装备：

![原版观察者看到的模型](verification/vanilla-model.png)

Fabric 热更新后的第三人称模型：

![Fabric 热更新后的模型](verification/fabric-hot-update.png)

最终 JAR 停用后，原版观察者看到恢复的皮肤与胸甲：

![原版停用恢复](verification/vanilla-disabled.png)

Fabric 收到 reset 后的第三人称恢复：

![Fabric 停用恢复](verification/fabric-disabled.png)

## 网页预览验收限制

已复用 [YSM Model Manager](https://github.com/eghrhegpe/ysm-model-manager) 提交 `d74f850ff3f1ca30af20195ecb77a5f7785f770e` 的网格、四元数和动画采样算法，并随产物分发 Apache-2.0 许可与来源说明。

内置浏览器已加载本地预览页、模型与源码，但 Mesa / ANGLE 在创建 WebGL 上下文时返回 `BindToCurrentSequence failed`。代码已增加中文失败提示；数学测试、类型检查和构建通过。**网页三维画面尚未成功验收**，也没有把游戏截图作为网页预览通过的证据。

## 后续验收范围

- 在可用 WebGL 的浏览器和真实 YDA 宿主中完成上传、预览、角色装备、管理员共享 / 停用 / 删除联调。
- 完成已发布 npm 契约重装和整套 readiness。
- 使用最终运行端测试完全无 Mod 的官方客户端、其他资源包叠加，以及生产规模 TPS / FPS。

以上构建和功能结果不代表上述未验收项目已经通过。
