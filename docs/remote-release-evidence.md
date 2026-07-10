# 远端发布证据

这份文档只服务真实 `v*` tag 验证。

本地脚本已经能证明“结构上正确”，但最终还需要远端流水线证明：

1. 插件仓只消费主体仓已发布契约
2. 插件仓能独立产出并发布自己的插件 JAR

## 1. 触发前检查

先跑本地审计：

```powershell
& 'C:/Program Files/Git/bin/sh.exe' ci/verify-plugin-repo-readiness.sh
```

然后确认：

- 主体仓对应版本已经发布
- `yudream-frontend/pnpm-workspace.yaml` 中的 `@yudream/plugin-sdk` / `@yudream/components` 版本与主体仓一致
- `pom.xml` 中的 `yudream.plugin.spi.version` 与主体仓一致
- `maven-public` 与 `npm-public` 已允许匿名读取
- 受保护的 `v*` tag 可以读取 `NEXUS_USERNAME`、`NEXUS_PASSWORD` 完成发布

## 2. 远端必须成功的 job

至少要看到这些 job 成功：

1. `validate:core-maven-registry`
2. `validate:core-npm-contracts`
3. `package:plugins`
4. `publish:plugin-jars`
5. `verify:published-plugin-jars`

## 3. 需要保留的证据

建议保留这些 URL 或截图：

- tag 名称
- pipeline URL
- `validate:core-maven-registry` job URL
- `validate:core-npm-contracts` job URL
- `package:plugins` job URL
- `publish:plugin-jars` job URL
- `verify:published-plugin-jars` job URL

以及最终 Nexus Maven 制品地址：

```text
https://nexus.yudream.online/repository/maven-public/online/yudream/plugins/
```

## 4. 验收时应该关注什么

### Maven 侧

- 插件仓不是从本地缓存解析 `yudream-plugin-spi`
- `validate:core-maven-registry` 能在独立 settings 下重新拉到 SPI

### npm 侧

- 插件仓不是从本地 workspace 读取 `@yudream/plugin-sdk` / `@yudream/components`
- `validate:core-npm-contracts` 能从远端 registry 装到正式包

### 插件产物侧

- `package:plugins` 产出的最终 JAR 含 `remoteEntry.js`
- 最终 JAR 不含主体 SPI 类
- `publish:plugin-jars` 上传成功
- `verify:published-plugin-jars` 回读成功

## 5. 完成标准

只有当远端流水线同时证明下面两点，插件仓这部分才算真正闭环：

1. 契约消费完全走已发布包
2. 插件 JAR 发布与发布后回读都成功
