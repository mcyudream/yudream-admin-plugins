# AI 模板办公 · Word 模板制作指南

> 面向站点管理员。宿主引擎为自研 POI 渲染器：只替换文本，**不支持嵌入图片**；支持段落内 `{{var}}` / `${var}` 占位与**表格行循环** `{{#listVar}} … {{/listVar}}`。产出文档务必不要使用「等线Light」字体（推荐宋体/黑体）。

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
引擎不能嵌图，图片以「图注 + 链接」文字呈现。正文放列表循环：

```
{{#effectImages}}
{{caption}}（{{kind}}）：{{url}}
{{/effectImages}}
```

再配 `{{captionsText}}`（全部图注）与封面字段。表格行循环写法：起始行放 `{{#effectImages}}`，结束行放 `{{/effectImages}}`，中间为模板行。

### office-activity-summary（活动总结）
信息表同报备表；正文 `{{contentBody}}`（AI 依据活动介绍 + 效果材料起草）；参与人数 `{{participantCount}}`；图片区同效果展示的 `{{#effectImages}}` 循环。

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

1. **不支持图片嵌入**：`{{var}}` 一律渲染为文本；附件以签名下载链接（约 30 分钟有效）文字呈现。
2. **docx→pdf**：宿主暂无转换能力，经费报表如需 PDF 请用 Office/WPS 另存。
3. 占位符必须整体出现在同一段落文本内（跨 run 的占位符请在模板里重打一遍）。
4. 缺失变量渲染为空串，生成前请用「预览映射」检查缺项。
