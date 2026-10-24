# AI 模板办公 · Word 模板制作指南

> 面向站点管理员。宿主引擎为自研 POI 渲染器：支持文本占位符 `{{var}}` / `${var}` 与**表格行循环** `{{#listVar}} … {{/listVar}}`；SPI 2.38.0 起支持**嵌图**（见下）。产出文档务必不要使用「等线Light」字体（推荐宋体/黑体）。

## 嵌图（宿主 SPI ≥ 2.38.0）

映射声明为图片后，引擎会把占位符替换为真实嵌入图片：

- **写法**：占位符必须**独占一个段落或单元格**（如效果表格的图片列放 `{{img}}`）；映射里行数据源写 `image:item.fileKey`（`image:` 前缀），顶层字段用 transform `image` 或 `image:宽度px`（如 `image:480` = 480 像素宽）。
- **尺寸**：不写宽度按图片原始尺寸嵌入、超宽自动收缩到 6 英寸内；高度按原始比例。
- **格式**：png/jpeg/gif/bmp；按文件头自动识别，视频/PDF 等不可嵌附件该占位渲染为空白。
- **兼容开关**：站点设置 → 写作与 AI →「模板嵌图」。宿主引擎低于 2.38.0 时请关闭，此时图片占位渲染为空白、文本上下文中的图片值显示「[图片]」。
- **限制**：占位符混在正文文字中间（非独占段落）时不会嵌图；行内循环 `{{#list}}…{{/list}}` 内不支持嵌图（用表格行循环）。

## 上传方式

平台管理端 → 文档模板 → 上传 docx，**模板编码**必须与文档类型里配置的 `wordTemplateCode` 一致。导入「社团月末材料包」预设后需要以下六个模板：

| 模板编码 | 用途 | 阶段 |
|---|---|---|
| `office-activity-report` | 活动报备表 | 事前 |
| `office-activity-plan` | 活动策划书 | 事前 |
| `office-activity-effect` | 活动效果展示 | 事后 |
| `office-activity-summary` | 活动总结 | 事后 |
| `office-month-expense` | 月度经费报表 | 月度 |
| `office-month-summary` | 月度工作总结 | 月度 |

## 通用占位符

- `{todayText}` 当前日期（2026年10月23日 形式）、`{period}` 周期（2026-10）
- 组织档案（站点设置里维护）：`{org.name}` `{org.supervisorUnit}` `{org.organizer}` `{org.undertaker}` `{org.coOrganizer}` `{org.committee}` `{org.signName}`
- 目标对象（活动/记录/周期）：`{target.title}` `{target.summary}` `{target.description}` `{target.form}` `{target.place}` `{target.audience}` `{target.activityStartText}`；活动分支还有 `{target.effectsText}`（已采纳效果文字汇总）、`{target.captions}`（图注汇总）、`{target.attachments}`（附件列表，用于循环）
- AI 草稿：`{contentBody}` 等——变量名 = 文档类型映射里 `ai:提示词编码` 的映射变量
- 统计：`{stats.participants}` `{stats.activityCount}` `{stats.effectCount}`

## 各模板要点

### office-activity-report（报备表）
信息表逐格放：`{{activityName}}`、`{{activityTime}}`（活动时间，date 变换）或 `{{activityTimeText}}`（手工记录的时间文本）、`{{activityPlace}}`、`{{activityForm}}`、`{{activityAudience}}`、`{{organizer}}`、`{{undertaker}}`、`{{dateNow}}`。

### office-activity-plan（策划书）
正文区放 `{{contentBody}}`（AI 起草的分节正文，一、二、三……），封面放 `{{activityName}}` `{{orgName}}` `{{organizer}}` `{{undertaker}}` `{{dateNow}}`。

### office-activity-effect（效果展示）
图片用行循环嵌图（表格行：说明列 `{{caption}}`、图片列 `{{img}}`，`img` 映射为 `image:item.fileKey`）：

```
{{#effectImages}}
```
| `{{caption}}` | `{{img}}` |
```
{{/effectImages}}
```

再配封面字段。旧宿主（关嵌图）时 `{{img}}` 渲染为空白，可在模板里并列一行 `{{url}}` 保留下载链接。

### office-activity-summary（活动总结）
信息表同报备表；正文 `{{contentBody}}`（AI 依据活动介绍 + 效果材料起草）；参与人数 `{{participantCount}}`；图片区同效果展示的 `{{#effectImages}}` 行循环嵌图（图片列 `{{img}}`）。

### office-month-expense（经费报表）
- 表头：`{{orgName}}` `{{supervisorUnit}}` `{{period}}`
- 收入明细表（表格行循环）：
  `{{#incomeRows}}` 行内 `{{title}}` `{{category}}` `{{amount}}` `{{fundSource}}` `{{dateText}}` `{{/incomeRows}}`
- 支出明细表：
  `{{#expenseRows}}` 行内 `{{title}}` `{{category}}` `{{subCategory}}` `{{quantity}}` `{{unitPrice}}` `{{amount}}` `{{buyer}}` `{{dateText}}` `{{purpose}}` `{{fundSource}}` `{{/expenseRows}}`
- 合计与结余：`{{incomeTotal}}` `{{expenseTotal}}` `{{balanceCarryIn}}` `{{balanceCarryOut}}`（已是元文本，money 变换）
- 签字栏：`{{committee}}`（理事层名单）、`{{dateNow}}`

### office-month-summary（月度工作总结）
封面 + `{{contentBody}}`（AI 依据当月活动清单起草工作总结与发展规划）；活动清单表格循环 `{{#activityRows}}` 行内 `{{title}}` `{{summary}}` `{{status}}` `{{/activityRows}}`。

## 已知限制

1. **嵌图条件**：SPI ≥ 2.38.0 且占位符独占段落/单元格；行内循环内不可嵌图。图片按文件头识别，视频/PDF 占位渲染空白（不嵌时文本上下文显示「[图片]」）。
2. **docx→pdf**：宿主暂无转换能力，经费报表如需 PDF 请用 Office/WPS 另存。
3. 占位符必须整体出现在同一段落文本内（跨 run 的占位符请在模板里重打一遍）。
4. 缺失变量渲染为空串，生成前请用「预览映射」检查缺项。
