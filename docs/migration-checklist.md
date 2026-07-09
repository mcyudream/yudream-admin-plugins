# 插件迁移清单

## 迁移前提

1. 核心仓已经发布：
   - `online.yudream.base:yudream-plugin-spi`
   - `@yudream/plugin-sdk`
2. 插件前端依赖的 UI 包已经可被独立消费  
   当前仍需处理 `@fantastic-admin/components`

## 推荐迁移顺序

### 第一批

- `yudream-plugin-authlib-injector`
- `yudream-plugin-wallet`

原因：

- 体量相对可控
- 能快速验证独立 Maven / npm / JAR 链路

### 第二批

- `yudream-plugin-alipay`
- `yudream-plugin-student-info`
- `yudream-plugin-minecraft-server`

### 第三批

- `yudream-plugin-minecraft-activity-proof`
- `yudream-plugin-yudream-skin`
- `yudream-plugin-project-progress`

## 每个插件迁移时要改的点

1. 插件后端 `pom.xml`
   - 去掉对核心仓根 parent 的依赖
   - 改为继承本仓根 `pom.xml`
   - 通过版本号依赖 `yudream-plugin-spi`

2. 插件前端 `package.json`
   - 去掉 `workspace:*` 形式的核心仓依赖
   - 改为正式版本依赖

3. 插件 JAR 打包
   - 前端 `dist` 资源路径保持为 `META-INF/yudream-plugin/frontend/{pluginCode}`

4. 验证
   - 前端 build
   - Maven package
   - 把 JAR 放入核心仓 `plugins/` 目录后运行验证
