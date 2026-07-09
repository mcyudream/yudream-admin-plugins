# YuDream Admin Plugins

这是 YuDream Admin 的独立插件仓。

它的职责是：

- 承载官方业务插件后端模块
- 承载官方插件前端包
- 消费核心仓发布的私有包
- 单独产出插件 JAR

当前仓库已经完成基础初始化，但还没有批量迁入业务插件源码。

## 依赖的核心私有包

插件仓后续统一消费：

- Maven: `online.yudream.base:yudream-plugin-spi`
- npm: `@yudream/plugin-sdk`

说明：

当前大多数插件前端还依赖 `@fantastic-admin/components`。在这层 UI 依赖被正式发布之前，业务插件前端还不能完全独立迁移。

## 当前结构

```text
yudream-plugins/       插件后端模块
yudream-frontend/      插件前端 workspace
docs/                  迁移说明
pom.xml                插件仓根 parent / aggregator
.gitlab-ci.yml         插件仓独立 CI
settings.xml.example   Maven 私有包消费示例
```

## 下一步推荐迁移顺序

1. 先发布或抽离插件 UI 依赖
2. 先迁一个最简单的插件验证链路
3. 再批量迁移其余插件

建议先迁移：

- `yudream-plugin-authlib-injector`
- 或 `yudream-plugin-wallet`

详细见：

- [迁移清单](./docs/migration-checklist.md)
