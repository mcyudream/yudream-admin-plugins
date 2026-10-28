# MC 面板：测试副本、覆盖回退和 CE 资源安装

适用版本：面板插件 0.24.0；SFTP 目录导航、会话撤销和 CPU 不限额修复需节点 0.7.9。

## 日常使用

- 文件页的「上传文件夹」保留选中目录结构，同时上传 2 个文件，显示排队、校验、上传、失败和取消状态。单文件最多 2GiB、单批最多 10000 个文件；大体积内容可用 SFTP。浏览器目录选择器不提供空目录，离开页面会取消该页面尚未完成的上传。已写入文件不会因取消而撤销。
- 「复制测试服」独立页面在源节点分配新端口、复制实例文件并保持停止。副本关闭自动启动、自动重启、域名、P2P 和平台子服绑定。插件文件中的数据库、代理和托管配置需要在启动前调整。
- 「覆盖与回退」独立页面先选择另一个实例及相对路径，预览后确认。只合并所选目录内的源文件，保留目标独有文件、端口、规格、名称和绑定。世界目录需明确选择。源和目标都必须停止。
- 所有文件先暂存，随后逐个换入；同名旧文件保存在目标 `.mcpanel-deployments/<任务 ID>/before/`。失败时自动回退；节点断线或回退无法证实时保留维护锁，恢复节点连接并停止实例后，可在记录中重试回退。维护期间仍允许停止或强制停止实例。
- 回退撤销本次新增文件，恢复被覆盖文件；也会撤销部署后对这些文件的修改。先回退较新的部署，再处理旧记录。目录结构中的空目录不会复制，回退可能保留新建的空父目录。
- 部署记录和回退副本会保留并占用目标磁盘。保留回退能力期间，请勿手动修改或删除 `.mcpanel-deployments`。回退副本丢失或校验失败时，系统保留维护锁并提示人工恢复。
- 节点外部程序仍可能修改磁盘，请在停机窗口执行。CPU 的 `0` 表示不限，其他值为 100–256000 毫核。旧容器的 CPU 策略在显式启动或重启时对齐，升级节点不会因此批量重启正在运行的容器。

## CE 安装包 `mcpanel-ce/1`

该格式参考已有插件工程的 `CraftEngine/contents/<namespace>/`、插件配置目录和 JAR 配套方式。一个包只管理一个 CE 命名空间，可附带一个业务插件的配置和 JAR。前端选择 ZIP 后先检查清单和摘要，再确认安装；复用相同的暂存和回退流程。

ZIP 根目录示例：

```text
mcpanel-package.json
payload/
  00000/items.yml
  00001/example.png
  00002/TownPlugin.jar
```

清单示例（摘要须为对应文件的真实 SHA-256）：

```json
{
  "format": "mcpanel-ce/1",
  "name": "Town resources",
  "version": "1.0.0",
  "namespace": "town",
  "pluginDirectory": "TownPlugin",
  "files": [
    {
      "source": "payload/00000/items.yml",
      "target": "plugins/CraftEngine/contents/town/configuration/items.yml",
      "sha256": "<64 位十六进制摘要>"
    }
  ]
}
```

允许的目标：

| 内容 | 目标 |
| --- | --- |
| CE 内容（至少一项） | `plugins/CraftEngine/contents/<namespace>/**` |
| 可选业务插件配置 | `plugins/<pluginDirectory>/**` |
| 可选业务插件 JAR | `plugins/<pluginDirectory>.jar` 或 `<pluginDirectory>-<version>.jar` |

规范要求：

- `namespace` 为小写字母开头的 `[a-z0-9_]`，最长 64 字符。名称最长 128 字，版本最长 64 字。
- `pluginDirectory` 为字母开头的字母、数字、下划线或连字符，不能为 `CraftEngine`；禁止覆盖其全局配置。
- 所有 `source` 必须位于 `payload/`，每个 payload 文件恰好被声明一次；不能重复目标或产生文件/目录冲突，忽略大小写判重。
- 路径为严格相对路径，不接受 `.`、`..`、反斜杠、控制字符、盘符和面板保留路径。不跟随符号链接。
- ZIP 最多 64MiB，展开内容最多 512MiB；清单最多 1MiB、10000 个文件。
- 不运行包内脚本或控制台命令，不改变实例规格或平台绑定。只安装文件，启动后请按 CraftEngine 工作流重新构建、上传和发送资源包；客户端模型、字体和贴图仍需进服验证。
- 凭据、生产数据库密码和资源托管令牌不能进入资源发布包。打包工具拒绝常见凭据文件名；配置值仍需发布者检查。
- 安装 JAR 前检查目标现有插件名。建议统一固定 JAR 名，避免不同版本文件同时存在而被服务器重复加载；面板保留目标独有文件，不自动删除旧的其他名称 JAR。

## 一条命令生成标准包

使用 Python 3（仅标准库）：

```bash
python3 tools/mcpanel/create-ce-package.py \
  --ce-content ./release/CraftEngine/contents/town \
  --namespace town --name 'Town resources' --version 1.0.0 \
  --plugin-directory TownPlugin \
  --plugin-config ./release/TownPlugin \
  --plugin-jar ./build/TownPlugin-1.0.0.jar \
  --output ./Town-resources-1.0.0.zip
```

只安装 CE 内容时省略三个 `--plugin-*` 参数。输出文件必须尚不存在。把生成 ZIP 交给 MC 面板「安装 CE 资源」独立页面，预览并确认即可；测试副本验证后，再对正式服按同样流程安装。

## MCP 客户端连接

宿主启用 MCP 系统能力且当前用户可访问平台 MCP 页面时，实例列表和详情显示「一键连接 MCP」。支持复制模块端点、通用 JSON 配置以及添加到 VS Code；端点为 `/api/mcp/mcpanel`。API Key 由用户输入，只在弹窗内临时保留，关闭即清除；一键安装会将 Key 交给客户端保存。

VS Code 安装链接使用其[官方 MCP 安装 URI](https://code.visualstudio.com/api/extension-guides/ai/mcp) 格式。其他客户端可粘贴通用 Streamable HTTP 配置。API Key 必须具备实际调用的 MC 面板权限，连接入口不会扩展用户的数据范围。
