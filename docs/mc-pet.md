# mc-pet：MC 皮肤网页宠物

把 Minecraft 皮肤渲染为**贯穿全站的 3D 网页宠物**（skinview3d）。管理员设定默认皮肤与交互风格，用户可选择跟随默认、使用自己的角色皮肤或衣柜收藏，并可拖拽宠物停靠位置。

## 宿主版本要求

- 宿主 SPI ≥ **2.22.0**（`@PluginGlobalWidget` 全局挂件契约）。
- 宿主运行时与前端需包含全局挂件挂载能力（`PluginGlobalWidgets` 布局组件）。宿主过旧时 `@PluginGlobalWidget` 被忽略：宠物不全局显示，但「我的宠物」页面与全部 API 不受影响。
- 皮肤能力来自 **yudream-skin ≥ 1.2.0**（`PluginSkinService.findClosetByOwner`），软依赖；缺失时自动降级为内置 Steve 皮肤，插件其余功能正常。提供方版本低于 1.2.0（无 `findClosetByOwner`）时查询调用点捕获 `LinkageError` 降级为空列表，不再 500。
- 上传皮肤（`POST /me/pet/skin`）需要 **yudream-skin ≥ 1.3.0**（`uploadClosetSkin`）；版本过低时返回明确错误提示，其余功能不受影响。

## 权限

| 权限码 | 说明 |
| --- | --- |
| `plugin:mc-pet:user` | 使用网页宠物（全局挂件可见 + `/me/**` 端点） |
| `plugin:mc-pet:manage` | 管理网页宠物（默认设置、跨用户偏好维护） |

## 全局挂件

`@PluginGlobalWidget(code="mc-pet-pet", component="mc-pet/GlobalPet", permission="plugin:mc-pet:user")`。宿主在每个页面挂载 fixed 浮层（`pointer-events:none` 容器 + 挂件自身恢复交互），不遮挡页面操作。

交互：

- **点击**：按管理端配置的点击行为（弹出菜单 / 打招呼 / 无响应）。打招呼播放挥手（wave）动画并弹出 MC 风格问候气泡（深色底 + 紫色描边，仿游戏内 tooltip）；弹出菜单时同样附带挥手动画。菜单含「我的宠物」「打招呼」「隐藏宠物」。
- **闲时碎碎念**：管理端开启交互动画后，宠物每 30~70 秒随机说一句 MC 风味台词或做一个短动作（walk / crouch / hit），页面隐藏时暂停。
- **拖拽**：左键按住拖动，拖动期间播放飞行（fly）动画，松开后持久化视口比例坐标（仅 PUT `positionX/positionY`，合并语义不会覆盖皮肤选择）；「我的宠物」页可一键回到默认停靠角。
- 宠物点击菜单内部动作走 `src/pet/actions.ts` 命令注册表，是后续 AI 能力的扩展缝（见 `docs/mc-pet-ai-evolution.md`）。

## HTTP 端点（挂载于 `/api/plugins/mc-pet/**`）

用户端（`plugin:mc-pet:user`）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/me/pet` | 当前用户生效配置（偏好 → 管理默认 → 内置兜底合并结果） |
| PUT | `/me/pet` | 保存偏好，**合并语义**：null 字段保持原值；`clearPosition=true` 显式清除拖拽位置 |
| GET | `/me/pet/options` | 我的角色与衣柜条目选择器数据（含 textureHash/model 供预览） |
| POST | `/me/pet/skin` | 上传皮肤 PNG（base64 JSON，可带 data URL 前缀，≤约 1MB）到皮肤衣柜，返回新衣柜条目；需要 yudream-skin ≥ 1.3.0 |

管理端（`plugin:mc-pet:manage`）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/PUT | `/admin/defaults` | 全局默认设置（默认皮肤模式 builtin/player/texture、角色名、纹理 hash 与模型、动画、点击行为、尺寸、停靠角） |
| GET | `/admin/pets?page=&size=` | 跨用户偏好分页列表（含每条生效配置） |
| GET | `/admin/pets/{userId}` | 单用户详情 |
| POST | `/admin/pets/{userId}/reset` | 重置用户偏好（删除文档，回到全局默认） |

## 数据与降级

- 文档存储集合：`options`（固定 id `defaults`，全局默认）、`preferences`（id=userId，用户偏好）。
- 生效配置解析顺序：用户偏好（mode=default/player/closet）→ 管理默认（builtin/player/texture）→ 内置 Steve。
- 管理默认 texture 模式：`textureHash` 指向 `/api/plugins/yudream-skin/textures/{hash}`；管理员通过 `POST /me/pet/skin` 把 PNG 上传进**自己的**衣柜（用户域接口，不新增管理上传端点），再把返回的 `textureHash` 写入全局默认；保存时 hash 缺失会被拒绝。
- 衣柜条目归属在保存时校验（`closetOf(userId)`），防止跨用户引用；被删除或皮肤插件停用时运行时回退内置皮肤。
- 内置皮肤 `assets/steve.png` 由 `scripts/gen-steve.cjs` 生成（64x64 经典布局近似 Steve），置于 `public/assets/` 以稳定文件名随 dist 入 JAR，前端经 `sdk.assets.url('assets/steve.png')` 引用。

## 前端结构

```text
src/components/GlobalPet.vue   全局挂件（拖拽、菜单、MC 风格气泡、动画状态机）
src/components/PetStage.vue    可复用 3D 预览（背景透明；idle/walk/run/fly/wave/crouch/hit）
src/components/SkinUploadModal.vue 上传皮肤弹窗（JS 创建 file input、模型选择、实时预览）
src/pages/MyPetPage.vue        我的宠物（来源/上传/尺寸/停靠角/隐藏 + 实时预览）
src/pages/AdminDefaultsPage.vue 默认设置
src/pages/AdminPetsPage.vue    用户宠物列表 + 详情抽屉 + 重置
src/pet/actions.ts             宠物动作注册表（AI 扩展缝）
```

## 已知限制

- 管理端「按角色名解析」的默认皮肤在保存后于用户端实时解析，管理页仅预览内置/已上传皮肤效果。
- skinview3d 默认相机看向模型正面，`playerWrapper.rotation.y = 0` 即正脸；`Math.PI` 展示后脑勺（已实测验证，勿再反转）。
- vue-skinview3d 钉住 skinview3d 3.0.1 类型，`PetStage` 的新动画（wave/crouch/hit）从 skinview3d 3.4.2 实例化后按鸭子类型断言传入（`PlayerAnimation` 含 protected 成员，跨版本结构不兼容，运行时无影响）。
