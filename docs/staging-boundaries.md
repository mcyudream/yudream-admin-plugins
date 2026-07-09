# 插件仓提交边界

这份文档用来把 `yudream-admin-plugins` 当前工作树里的拆仓相关改动收成更清楚的提交。

## 插件仓建议分组

### 1. 独立仓骨架 / CI / 说明文档

这一组决定插件仓是否真的是一个独立仓。

建议重点检查并分组：

- `.gitlab-ci.yml`
- `ci/`
- `README.md`
- `docs/`
- `.npmrc.example`
- `settings.xml.example`
- `pom.xml`
- `yudream-frontend/package.json`
- `yudream-frontend/pnpm-workspace.yaml`
- `yudream-frontend/pnpm-lock.yaml`

典型命令：

```powershell
git add .gitlab-ci.yml
git add ci
git add README.md docs
git add .npmrc.example settings.xml.example pom.xml
git add yudream-frontend/package.json
git add yudream-frontend/pnpm-workspace.yaml
git add yudream-frontend/pnpm-lock.yaml
```

### 2. 已迁移业务插件源码

这一组是实际迁出的业务插件后端与前端模块。

建议重点检查并分组：

- `yudream-frontend/packages/plugin-*/`
- `yudream-plugins/yudream-plugin-*/`

典型命令：

```powershell
git add yudream-frontend/packages/plugin-*
git add yudream-plugins/yudream-plugin-*
```

## 提交前建议

先跑插件仓一键审计：

```powershell
& 'C:/Program Files/Git/bin/sh.exe' ci/verify-plugin-repo-readiness.sh
```

只看拆仓相关改动时，可以先缩小 `git status` 视野：

```powershell
& 'C:/Program Files/Git/bin/sh.exe' ci/show-plugin-repo-status.sh
```

如果准备验证远端发布，再补这些环境：

- `CORE_PACKAGE_TOKEN` 或 `CI_JOB_TOKEN`
- `VERIFY_PUBLISHED_PLUGIN_JARS=true`

最后再人工复查暂存内容：

```powershell
git diff --cached --stat
git diff --cached
```

## 辅助脚本

如果你想先只暂存“仓库骨架 / CI / 文档”这一组，可以直接用：

```powershell
& 'C:/Program Files/Git/bin/sh.exe' ci/stage-plugin-repo-foundation.sh --dry-run
& 'C:/Program Files/Git/bin/sh.exe' ci/stage-plugin-repo-foundation.sh
```

如果你想再单独暂存“迁入的业务插件源码”这一组，可以用：

```powershell
& 'C:/Program Files/Git/bin/sh.exe' ci/stage-plugin-source-migration.sh --dry-run
& 'C:/Program Files/Git/bin/sh.exe' ci/stage-plugin-source-migration.sh
```
