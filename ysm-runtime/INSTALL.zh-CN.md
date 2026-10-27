# 玩家模型安装与配置

本次发布组合：YDA `1.11.0`、网站插件 `ysm-models 0.3.0`、Folia 插件与 Fabric 客户端 Mod `0.2.0`。游戏运行端只适配 **Minecraft / Folia 26.3、Java 25**；YDA 宿主使用 Java 21。

## 1. 文件放在哪里

| 文件 | 安装位置 |
| --- | --- |
| `yudream-plugin-ysm-models-0.3.0.jar` | YDA 插件管理中安装；先启用 `yudream-skin 1.7.0` |
| `YudreamModels-folia-26.3-0.2.0.jar` | Folia 服务器的 `plugins/` |
| `YudreamModels-fabric-26.3-0.2.0.jar` | 玩家的对应游戏实例 `mods/` |

网站插件可从 YDA 插件市场安装，也可上传本次发布的 JAR。三个 JAR 分别用于网站、游戏服务器和游戏客户端。

## 2. 配置 YDA 网站

在 **YDA 后端进程 / 容器** 的环境变量中加入：

```dotenv
YSM_PUBLIC_BASE_URL=https://skin.example.com
YSM_BRIDGE_TOKEN=REPLACE_WITH_RANDOM_SECRET_AT_LEAST_32_CHARACTERS
```

将域名替换为玩家与游戏服务器都能访问的网站根地址。用 `openssl rand -hex 32` 生成一份随机密钥，保存后用于下面的服务端配置。重新创建容器或重启后端，使环境变量生效；Docker Compose 的 `.env` 文件仍需通过 `environment` 或 `env_file` 注入容器。

先安装并启用 `yudream-skin 1.7.0`，再安装并启用 `ysm-models 0.3.0`。玩家需要已有的皮肤站角色，模型装备关系绑定在本人角色上。

## 3. 配置 Folia 服务器

放入服务端 JAR，使用 Java 25 启动一次服务器。编辑生成的 `plugins/YudreamModels/config.yml`，然后重启服务器：

```yaml
website-url: 'https://skin.example.com'
bridge-token: 'REPLACE_WITH_THE_SAME_SECRET_AS_YDA'
poll-seconds: 5
refresh-cooldown-seconds: 60
resource-pack-timeout-seconds: 120
model-scale: 0.75
view-distance: 64
max-snapshot-models: 128
pack-host:
  bind: '0.0.0.0'
  port: 8767
  public-url: 'https://packs.example.com'
```

- `website-url` 与 YDA 的 `YSM_PUBLIC_BASE_URL` 填同一网站根地址。
- `bridge-token` 与 YDA 的 `YSM_BRIDGE_TOKEN` 必须完全一致，密钥只配置在网站与服务器。
- `pack-host.public-url` 是玩家下载原版资源包的公网根地址，转发到游戏服务器的 `8767` 端口。它与网站地址用途不同。
- 如果使用 HTTPS 反向代理，保留请求路径转发到 `pack-host.bind/port`。例如代理与游戏服在同一台主机时，可将 `packs.example.com` 的请求转发到 `http://127.0.0.1:8767`。
- 玩家电脑必须能够访问网站下载地址和资源包地址；仅本机能访问的 `localhost` 地址不能用于公网玩家。

## 4. 安装客户端 Mod

玩家游戏实例使用 Minecraft `26.3`、Java `25`、Fabric Loader `>=0.20.0`。在同一实例的 `mods/` 内放入：

1. `YudreamModels-fabric-26.3-0.2.0.jar`。
2. 适配 Minecraft `26.3` 的 Fabric API（构建 / 验证所用版本为 `0.165.0+26.3`）。

客户端 Mod 无需填写网站地址或 bridge token。进入已配置插件的服务器后，会自动握手并按实际装备下载模型。未安装本 Mod 的客户端可走原版资源包快照路径，需允许服务器资源包；网站换装后可用 `/model refresh` 更新快照。

## 5. 玩家使用与检查

1. 在网站「玩家模型 → 我的模型」上传原始 `.ysm` 或明文模型 ZIP。
2. 选择贴图和本人皮肤站角色，点击装备；管理员可将模型加入服务器模型库。
3. 进入游戏，执行 `/model status` 查看模式和资源加载状态。
4. 原版快照更新使用 `/model refresh`，默认冷却 60 秒；Fabric 模式支持局部热更新。
5. 原版查看本人替身可用 `/model self on`，再切换第三人称；关闭用 `/model self off`。默认关闭本人替身。
6. 在网站恢复皮肤外观即可取消模型。

## 6. 格式与验证范围

支持原始 `.ysm`、明文 ZIP、box / 逐面 UV 和受限 Molang。默认服装显隐层可运行；专用物理按目标值近似，自定义控制器、外观菜单、声音、粒子、附属实体与第一人称手臂未复现。文件含不支持或畸形的表达式时，预览会显示兼容说明。负尺寸方块规范化后可能改变其 UV 朝向。

本版新上传模型使用 **schema 2**。网站插件和游戏服务端 / 客户端应一起升级；旧模型可继续使用。网站内置解析器，不需要额外安装 Node。原版刷新资源快照可能触发两次资源重载；Fabric 更新失败会保留旧外观。

已有验证包括模型转换、前后端测试、运行端构建、本地 YDA 上传 / 装备 / 共享 / 桥接鉴权，以及使用模拟网站桥接的游戏端显示与热更新。最终游戏端连接真实 YDA 的完整往返、网页 WebGL 三维显示、跨贴图切换和完全无 Mod 的官方客户端仍未验证；详细记录见 [VERIFICATION.md](VERIFICATION.md)。
