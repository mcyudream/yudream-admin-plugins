# 预览核心来源

复用了 [YSM Model Manager](https://github.com/eghrhegpe/ysm-model-manager) 的纯渲染算法。

- 固定提交：`d74f850ff3f1ca30af20195ecb77a5f7785f770e`。
- `cube-mesh.ts`、`quaternion.ts`：原始几何、UV、骨骼位置与旋转算法；调整相对 import。
- `mesh-types.ts`：从 `spec-builder.ts` 提取必要类型，避免桌面 backend 依赖。
- `animation-evaluator.ts`：动画采样；调整 import，并将线性 / Catmull-Rom 的终点及首帧前采样改为 `pre`，与通用 Java 运行时一致。
- 本附属自行实现 Vue 生命周期、相机、通用 bundle 适配与数值动画解析。

许可证与上游 NOTICE 随附，构建时复制到前端产物和插件 JAR。没有引入 Wails、YSMParser WASM 或 MolangJS。当前上传格式为明文 YSM ZIP，不能把预览支持的格式等同于游戏运行端支持的格式。
