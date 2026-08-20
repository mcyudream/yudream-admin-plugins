# 插件开发模式（源码热重载）

宿主持久支持「开发模式」：不打包 JAR，直接从本仓插件模块的 `target/classes` 加载插件，监听源码与前端产物变化自动热重载。仅限本地开发，生产环境禁止开启。

## 目录约定

以插件模块 `yudream-plugins/yudream-plugin-demo` 为例：

```
yudream-plugin-demo/
├── pom.xml
├── src/main/...                # Java 源码（watcher 监听，可触发自动编译）
├── target/
│   ├── classes/                # mvn compile 产物，含 plugin.yml（宿主从这里加载）
│   └── plugin-dev/lib/*.jar    # dev-export profile 导出的运行时依赖
yudream-frontend/packages/plugin-demo/dist/   # 前端产物（watcher 监听，变化通知宿主重挂载）
```

- `target/classes/plugin.yml` 是权威描述文件，`name` 必须与宿主配置的 `code` 一致。
- `target/plugin-dev/lib` 由 `dev-export` profile 导出（runtime scope；provided 的 SPI 与插件间 api 由宿主/提供者 ClassLoader 链提供，不会导出）。

## 首次准备

```bash
# 1. 编译并导出运行时依赖（每个要开发的插件执行一次；依赖变化后重跑）
mvn -pl yudream-plugins/yudream-plugin-demo -am -P dev-export package -DskipTests

# 2. 前端产物（持续开发时用 watch）
pnpm --dir yudream-frontend --filter @yudream/plugin-demo exec vite build --watch
# 或全部插件一起：pnpm --dir yudream-frontend run watch
```

## 宿主配置

在宿主 `application.yml`（或本地 profile）添加：

```yaml
yudream:
  platform:
    plugin:
      dev-mode:
        enabled: true
        poll-interval-ms: 1000   # 轮询间隔
        debounce-ms: 800         # 变化防抖
        projects:
          - code: demo            # 必须与 plugin.yml 的 name 一致
            path: /path/to/yudream-admin-plugins/yudream-plugins/yudream-plugin-demo
            auto-compile: true    # 监听到 src/main/java 变化自动执行 compile-command
            compile-command: mvn -q compile -DskipTests -P dev-export
            # frontend-dist: ...  # 可覆盖，默认按本仓布局推导 packages/plugin-{code}/dist
```

启动宿主后：开发模式项目优先于 `plugins/` 目录里的同 code JAR；前端资源优先从 dist 目录取。宿主的 `PluginDevModeWatcher` 会：

1. `src/main/java` 变化 → 防抖执行 `compile-command`（失败会推送事件、不重载陈旧产物）；
2. `target/classes` 变化 → 自动走 禁用 → 卸载 → 目录加载 → 恢复启用 管线；
3. `dist` 变化 → 通知管理后台调试抽屉重挂载远程前端模块。

## 注意

- 硬/软依赖的提供者插件必须已启用；热重载只重建本插件，依赖方如有 ABI 变化需手动重载依赖方。
- 开发模式插件不要走市场安装/更新/回滚流程；删除插件记录不会删除源码目录。
- Windows 下 `compile-command` 需要 `mvn`（或 `mvn.cmd`）在 PATH；否则填绝对路径，例如 IntelliJ 捆绑 Maven。

## 开发者调试浮窗

宿主管理后台悬浮按钮打开非模态开发者调试浮窗（需 `platform:plugin-devtools:view` 权限；宿主前端 DEV 模式下始终可见），对普通插件零适配：

- **插件资产**：选中本仓插件即可查看其运行时贡献——HTTP 端点、QQ 指令、菜单、权限、前端路由、AI 工具、声明式 Agent、平台能力、消息交互、首页卡片、服务导出；开发模式插件支持一键重载。
- **指令模拟器**：指令行的「模拟触发」直接调用单插件 handler，适合低级单元调试；要验证真实 `/`/`!` grammar、绑定和权限，请使用 QQ 沙盒。
- **QQ 沙盒**：选择真实且已启用的策略连接和目标插件，构造群聊/私聊、@机器人、额外提及与 reply；随机触发可选真实概率、强制命中或强制未命中。ai-chatbot 只读加载真实群策略与历史种子，沙盒短期历史留在会话内存，活动/画像/限流/语义索引不写生产数据；触发、阻断、Agent、工具与捕获回复按时间线展示，绝不向真实 QQ 发送。
- **端点测试器**：端点行的「试用」在面板内填路径参数/查询/请求体发起真实请求，展示真实状态码与响应原文（接口加密开启时为密文，属预期）。
- **Agent 追踪**：插件声明式 Agent 的执行链路（来源标记 PLUGIN）实时逐步展示输入、思考过程、工具调用与输出；历史记录保留 7 天，详情可导出 JSON 用于缺陷上报。
- **事件流**：COMPILE/RELOAD/FRONTEND_RELOAD 等事件实时推送；COMPILE 失败时不重载陈旧产物，先看这里排障。

宿主侧机制与配置细节见宿主仓 `docs/plugin-system/dev-mode.md`。
