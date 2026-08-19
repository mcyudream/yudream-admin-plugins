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
            path: D:/code/yudream-admin-plugins/yudream-plugins/yudream-plugin-demo
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
