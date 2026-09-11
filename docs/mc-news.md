# MC 新闻推送（mc-news）

轮询 Minecraft 官网新闻与反馈隧道版本文章，与本地缓存对比发现新内容后，调用宿主 AI 整合成中文导语，按可自定义的参量化模板推送到机器人群聊、Webhook 与订阅用户。

## 新闻源

内置三个默认源（可编辑、启停，不可删除），也可新增自定义源（上限 20）：

| 标识 | 类型 | 地址 | 默认关键词 |
| --- | --- | --- | --- |
| `mcnet-news` | `mcnet` | `https://www.minecraft.net/content/minecraftnet/language-masters/zh-hans/_jcr_content.articles.page-1.json` | `A Minecraft Java` |
| `fb-beta` | `zendesk` | `https://minecraftfeedback.zendesk.com/api/v2/help_center/en-us/sections/360001185332/articles?per_page=5` | 无 |
| `fb-release` | `zendesk` | `https://minecraftfeedback.zendesk.com/api/v2/help_center/en-us/sections/360001186971/articles?per_page=5` | 无 |

- `mcnet` 解析 minecraft.net 文章列表 JSON（`article_grid`），官网文章的稳定 ID 取自去掉语言前缀的路径，切换语言源不会导致重复推送；该接口不含发布时间，以发现时间代替。
- `zendesk` 解析 Minecraft 反馈隧道帮助中心文章 API，`per_page` 直接写在 URL 中可调。
- 关键词对标题与摘要做不区分大小写的包含匹配，命中任一即收录；留空不过滤。官网 Java 版文章副标题形如 `A Minecraft Java ...`，因此默认关键词即为筛出 Java 版动态。

## 轮询与缓存

- 定时器每分钟检查一次，距上次轮询达到配置间隔（默认 30 分钟，5~1440）即触发；关闭开关暂停自动轮询，手动轮询不受影响。
- 新闻动态与推送设置页展示轮询状态：上次轮询时间与结果摘要、下次轮询倒计时（约数分钟，定时器粒度为 1 分钟）、进行中/已暂停标签；状态每 30 秒自动刷新。
- 每个新闻源在”新闻源”页可点”测试”即时在线抓取一次：返回命中条数、耗时与最新标题，不写缓存不推送，用于验证源与关键词过滤是否正常。
- 每轮拉取全部启用源，与已见缓存（默认最近 50 条，5~200 可配）按文章 ID 对比，仅新条目进入推送。
- 首次运行（缓存为空）默认只建立基线不推送，避免启用即轰炸；可用”首次运行即推送”开关改变。
- 单源抓取失败记入轮询状态并跳过，不影响其他源。
- 管理端可随时”立即轮询”，轮询状态（上次时间与结果摘要）在新闻动态与推送设置页展示。

## AI 整合与消息模板

- AI 开启时，先抓取**文章正文**再调用宿主 AI 能力：Zendesk 走帮助中心单篇文章 API 取 body；官网新闻抓文章页并截取 `article-section` 到 `<footer>` 的正文区，转成纯文本（列表项加 • 前缀、还原实体、压缩空白）。
- 默认提示词要求 AI 输出"一句话概括 + 要点列表"，保留修复的 bug（含 MC-XXXXX 编号）、新增/调整内容、版本号与对玩家/服务器管理员的实际影响，忠于原文不编造；总长约 300 字。
- 正文截取长度可配（`aiContentMaxChars`，默认 6000 字符，500~20000）；仅对进入 AI 整合的条目抓正文（受单轮条数上限控制）。正文抓取失败时回退为仅按标题/摘要整合，不阻塞推送。
- 供应商/模型留空走宿主默认；AI 关闭、超时或失败回退官方摘要。
- 系统提示词与消息模板在设置页直接预填内置默认文案，用户在其上修改即可；保存时与默认完全一致视为未自定义，仍跟随内置默认（插件升级默认文案时自动生效），也可一键恢复默认。

模板参量（点击插入，`{{name}}` 占位，未知参量渲染为空）：

| 参量 | 含义 |
| --- | --- |
| `{{title}}` | 新闻标题 |
| `{{summary}}` | 官方摘要 / 副标题（可能为空） |
| `{{aiSummary}}` | AI 导语（AI 关闭或失败时为空） |
| `{{body}}` | 推送正文：AI 导语优先，自动回退官方摘要 |
| `{{sourceName}}` / `{{sourceId}}` | 来源名称 / 标识 |
| `{{category}}` | 分类（News / Updates / Beta 等，可能为空） |
| `{{url}}` / `{{imageUrl}}` | 文章链接 / 封面图（可能为空） |
| `{{publishedAt}}` / `{{discoveredAt}}` | 发布时间 / 发现时间 |

默认模板：

```text
📰 {{title}}

{{body}}

🔗 {{url}}
来源：{{sourceName}}｜{{category}}
```

## 推送渠道

- **机器人群聊**：选择宿主消息连接与群聊；官方 QQ 机器人协议可开 Markdown 渲染，其他协议建议纯文本。
- **Webhook（全局/个人）**：向配置地址 POST JSON，载荷含 `title`、`content`（渲染后文本）、`body`、`summary`、`aiSummary`、`source`、`sourceName`、`category`、`url`、`imageUrl`、`publishedAt`、`discoveredAt`、`timestamp`、`event`；支持自定义 Header（如 Authorization）。全局目标上限 20，个人上限 5。
- **用户订阅**：持有 `plugin:mc-news:use` 权限的用户可在“我的新闻订阅”开启绑定私信推送（走宿主消息绑定）并维护个人 Webhook；个人目标仅本人可见可测。

## 端点

管理端（`plugin:mc-news:manage`）：

- `GET/PUT /admin/settings`、`GET /admin/poll/status`、`POST /admin/poll`
- `GET/POST /admin/sources`、`PUT/DELETE /admin/sources/{id}`、`POST /admin/sources/{id}/test`
- `GET/POST /admin/targets`、`PUT/DELETE /admin/targets/{id}`、`POST /admin/targets/{id}/test`
- `GET /admin/news?page&size&sourceId&keyword`、`DELETE /admin/news/{id}`、`POST /admin/news/clear`、`POST /admin/news/tombstones/clear`、`GET /admin/logs?page&size`
- `GET /admin/options/connections`、`GET /admin/options/groups?connectionId=`
- `GET /admin/template/variables`、`POST /admin/template/preview`

用户端（`plugin:mc-news:use`）：

- `GET /me/subscription`、`PUT /me/subscription/direct`、`POST /me/subscription/direct/test`
- `POST /me/webhooks`、`PUT/DELETE /me/webhooks/{id}`、`POST /me/webhooks/{id}/test`

## 页面

- 管理端：新闻动态（`/platform/plugins/mc-news/news`）、新闻源（`/sources`）、推送目标（`/targets`）、推送设置（`/settings`）、推送记录（`/logs`），均需管理权限。
- 用户端：我的新闻订阅（`/mc-news/subscribe`），需使用权限。

## 存储集合

`mc_news_settings`（global）、`mc_news_sources`、`mc_news_targets`（含个人 Webhook，按 `ownerUserId` 归属隔离）、`mc_news_seen`（缓存单文档）、`mc_news_subs`（每用户私信开关）、`mc_news_logs`（文档 ID 带时间倒序前缀，`findAll` 首页即最新）。

## 已知行为

- 推送失败的条目不会在下轮重复推送（已入缓存），失败原因在推送记录中可查。
- 推送日志按“一条新闻 × 一批目标”记录，测试推送以 `test` 模式入日志。
- 新闻动态支持单条删除与一键清空，语义不同：
  - **单条删除**进入忽略名单（墓碑，上限 500），不再推送也不再显示，用于屏蔽不想要的条目；
  - **清空动态**只清空列表显示，不写忽略名单，下轮轮询重建缓存基线（默认不重新推送）。
- 忽略名单数量在新闻动态页状态卡展示，可一键“清空忽略名单”恢复：被拦截的新闻重新参与轮询，不在缓存中的会被视为新内容推送。
- 轮询摘要会标注被忽略名单拦截的数量（如“检查了 8 条内容，没有新新闻（8 条在忽略名单中，不会推送）”），便于发现推送被拦截的情况。
- 宿主 AI、消息连接能力缺失时对应路径显式降级：AI 回退官方摘要，无推送目标时条目标记“未推送”。
