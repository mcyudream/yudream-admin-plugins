# ADR：mc-pet AI 演进方向（本期不实现）

## 状态

已接受（方向预留）。mc-pet 1.0.0 仅落地接口与约定，**不包含任何 AI 调用、网关对接或权限新词**。

## 背景

网页宠物天然是全站唯一常驻、跨页面存续的 UI 入口，适合作为 AI 助手的载体。需求方提出的演进方向：宠物可以**问（ask）**、**跳转页面（navigate）**、**帮忙操作/填写内容（fill）**，并可作为**蒙版导引（mask tour）**的入口。这些能力必须接入权限系统，不能让 AI 越权操作。

## 本期已落地的扩展缝

1. **前端动作注册表** `src/pet/actions.ts`：

   ```ts
   interface PetAction {
     type: 'ask' | 'navigate' | 'fill' | 'local'
     name: string
     permission?: string      // 触发前校验的动作级权限
     handler: (ctx: { sdk: YuDreamPluginSdk }) => void | Promise<void>
   }
   registerPetAction(action) / getPetAction(type, name) / listPetActions()
   ```

   当前仅注册 `local` 类型动作（打开设置、打招呼、隐藏）。AI 驱动的 `ask`/`navigate`/`fill` 动作未来注册到同一注册表，宠物气泡菜单与对话 UI 无需感知动作来源。

2. **生效配置契约**：`GET /me/pet` 的 `clickAction`/`animation` 已把「交互风格」表达为数据，AI 版本可在此扩展（如对话气泡开关、主动触发策略），用户/管理两层配置合并链路直接复用。

## 演进方案（待实施）

### 1. 权限：`plugin:mc-pet:ai`（预留，未注册）

- AI 能力按动作粒度挂权限：`ask`（只读问答）< `navigate`（路由跳转）< `fill`（表单/内容操作，危险，需二次确认）。
- 遵循宿主权限码词表；落地前需先在宿主仓更新动作词表（如 `assist`/`operate`），禁止自造。
- AI 以**当前用户身份**执行，数据边界与 `/me/**` 完全一致；禁止借 AI 通道绕过 user/admin 边界。

### 2. 路由信息 AI 化（需宿主契约演进）

- 设想：`@PluginRoute` / `@PluginFrontend` 增加 AI 可读字段（`aiDescription`、`aiParams`），让「跳转到 X 页面并填写 Y」可被结构化描述。
- 宿主 frontend-manifest 已下发路由树，AI 导航所需的「可去哪」基本够用；缺的是「去了能干什么」的语义描述，需宿主 SPI 升 MINOR 补契约。

### 3. 蒙版导引（mask tour）入口

- 宠物气泡新增「带我看」动作（`navigate` 类型）：解析目标路由 → `router.push` → 在目标页面以蒙版高亮关键控件。
- 蒙版层与挂件同样挂在宿主全局层，复用 `pointer-events` 分层方案；高亮目标由页面以语义化 data 属性或注册表声明。

### 4. ask / fill 能力模型

- `ask`：宠物气泡内嵌对话，走宿主 AI 网关（待宿主提供 SPI/SDK 契约）；仅读能力，工具白名单由动作注册表的 `permission` 控制。
- `fill`：AI 产出结构化指令 → 映射到已注册的 `fill` 动作 → 一律走表单组件的既有校验与提交流程，AI 不直接写 DOM/发请求；执行前向用户展示 diff 并确认。

## 不做的事（本期）

- 不注册 `plugin:mc-pet:ai` 权限、不写 AI 端点、不接网关、不做蒙版导引 UI。
- 不为预留能力在 domain/DTO 中堆放死字段。
