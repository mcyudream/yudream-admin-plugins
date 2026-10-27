# 预览核心来源

复用了 [YSM Model Manager](https://github.com/eghrhegpe/ysm-model-manager) 的纯渲染算法。

- 固定提交：`d74f850ff3f1ca30af20195ecb77a5f7785f770e`。
- `cube-mesh.ts`、`quaternion.ts`：原始几何、UV、骨骼位置与旋转算法；调整相对 import；逐面 UV 适配补充缺省面隐藏、缺省 UV 尺寸和 90 度旋转。
- `mesh-types.ts`：从 `spec-builder.ts` 提取必要类型，避免桌面 backend 依赖。
- `animation-evaluator.ts`：动画采样；调整 import，并将线性 / Catmull-Rom 的终点及首帧前采样改为 `pre`，与通用 Java 运行时一致。补充动态 pre 轴求值。
- 本附属自行实现 Vue 生命周期、相机、通用 bundle 适配与受限表达式适配。

许可证与上游 NOTICE 随附，构建时复制到前端产物和插件 JAR。前端没有引入 Wails 或 MolangJS。原始 `.ysm` 由网站后端内置的 YSMParser WASM 解码，来源、哈希与许可见 JAR 的 `ysm-parser/`。通用 schema 2 供网站和运行端 0.2.0 共用，仍不等同于完整 YSM 控制器兼容。
