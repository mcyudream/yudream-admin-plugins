# 高校学历认证插件（edu-verify）可行性评估与落地说明

> 评估日期 2026-09-05。状态：**1.5.4 已落地（未列入发布清单；商店仍可能是 1.5.2）**。
> 插件 code `edu-verify`；包名 `online.yudream.base.plugin.eduverify`；前端包 `@yudream/plugin-edu-verify`。
> 本期实现：教育邮箱域名白名单自动核验、学信网 16 位官方报告页核验（经宿主通用网页抓取 SPI 读取完整 HTML，再用可配置 CSS 选择器解析）、人工审核。CARSI 仅模型预留，管理端展示「未开通」，不做 iframe / 反向代理。
> 1.3.0：学信网转入人工、直接人工审核均须填写真实姓名与学校；管理员通过时可补全或纠正，并写入人员管理标签（`name`/`school`/`status`）留档。1.2.0：学信网渠道改为 16 位官方报告页（`bg.do?vcode=`）；去掉 12 位数字码与 zwfw/图形验证码。需宿主 SPI 2.21.0 与已开启的消息渲染能力。1.1.4：匿名注册也可上传人工审核材料（插件公开 JSON 上传，不走宿主需登录的 `/api/files/upload`）；仅图片/PDF，校验魔数、大小、扩展名与声明类型，并按 IP/邮箱限流。1.1.3：人工审核/学信网邮箱改为联系邮箱（用于绑定记录，不必是教育邮箱）；注册页核验查询防抖。1.1.2：白名单教育邮箱填写即视为已核验，不再发邮件验证码；注册成功后也不再二次发送宿主邮箱验证信。1.1.1：注册页「去核验」在 FaModal 内完成，独立 `/edu-verify` 仅作兜底。1.1.0：认证全部在注册前完成，普通用户没有个人页；PENDING 拒绝注册并提示「人工审核中，审核通过后即可注册」；学校与状态写入人员管理标签（SPI 2.20.0）。

## 1. 目标

注册时对新用户进行高校学历/身份认证审查，认证通过才能注册并使用系统功能。提供四个认证渠道：学信网、教育邮箱、CARSI、人工审核。

## 2. 渠道可行性结论（TL;DR）

| 渠道 | 评级 | 落地 |
|---|---|---|
| 教育邮箱 | **高** | **已实现**：域名白名单自动核验（edu.cn 默认条目覆盖子域），无需验证码 |
| 人工审核 | **高** | **已实现**：公开 JSON 上传（魔数/大小/限流）+ 待审队列 + 凭证定期清理 |
| 学信网（自动 API / 逆向 zwfw） | **不做** | 无公开 API；zwfw 属未授权逆向，已从产品路径移除 |
| 学信网（16 位官方报告页） | **中（有杀开关）** | **已实现为独立渠道**：用户提交 16 位码；宿主通用网页抓取读取官方报告 HTML；CSS 选择器可配；失败/不可用降级人工 |
| CARSI | **不可行（当前）** | **模型预留、默认关闭**：不实现 iframe、JS 注入或反向代理 |

推荐组合：**教育邮箱自动放行 + 学信网在线验证码（可关）+ 人工审核兜底**。任一渠道 `PASSED` 且未过期即满足宿主门禁 `edu-verify`。

## 3. 分渠道详评

### 3.1 学信网（CHSI）

- **没有面向公众的开放平台**。`open.chsi.com.cn`、`verify.chsi.com.cn` 均不存在（DNS 不可解析）。官方对外核验仅两条路：本人在「学信档案」申请《学籍在线验证报告》/《学历证书电子注册备案表》拿到在线验证码；第三方打开公开报告页输入验证码核验。
- **当前码制**：学籍在线验证报告使用 **16 位字母数字码**（例 `APEVUKH9C8SSGS5D`），官方页 `https://www.chsi.com.cn/xlcx/bg.do?vcode={code}&srcid=bgcx` 服务端渲染完整报告。旧 12 位数字码与 zwfw 政务平台查询已从产品路径移除。
- **单位会员服务**：用人单位注册为单位用户，需营业执照等资质、签协议、按量付费、逐人授权，定位招聘背调，非标准 REST API，小网站商务流程偏重。
- **学信网 App 扫码**只用于登录本人学信档案，无第三方 OAuth 授权服务。
- **第三方「学历核验 API」**（聚合数据/背调公司）多为转售或爬取通道，学信网近年多次公告打击，有随时切断与法律连带风险。
- **直接 HttpClient 访问官网会 412**（数据中心 IP / 非浏览器 UA）。因此抓取走宿主通用能力：`PluginRenderService.htmlFromUrl` → render-server `POST /v1/render/url-html`（`urlB64` 传完整 URL）。该端点开 JavaScript，等文档有正文（以过 WAF cookie 挑战），并放行同站脚本/样式/XHR/图片/字体；跨站请求、媒体与内网地址仍拦截。插件再用 jsoup + 可配置 CSS 选择器解析姓名/学校/学籍状态/在线验证码。
- **产品落地**：用户自行申请报告并提交 16 位码；管理端可改 URL 模板与选择器（仅 `www.chsi.com.cn`、必须含 `{code}`）；日配额与杀开关保留；解析失败、渲染能力未启用或超时 **DEGRADED 转入人工**。管理员开启邮件二次确认后，官方报告页通过只进入 `PENDING_MAIL`，须再匹配学信网官方发到站点入站邮箱的报告邮件：核对发件域，并从主题/正文/HTML/PDF 附件抽取验证码与用户填写的 16 位码比对。审核员仍可到官方页抽查。**不做打码平台、不接 CARSI、不逆向 zwfw。**

### 3.2 教育邮箱

- **域名数据集**：以 JetBrains/swot（MIT，实测 `lib/domains/cn/edu/` 约 480 个 edu.cn 条目，维护活跃）为底，打包进插件资源；叠加管理端可维护的自定义域名白名单（覆盖 `stu.pku.edu.cn`、`mail.ustc.edu.cn` 等非 edu.cn 校域）。**不要**简单信任任意 `*.edu.cn`（教科网节点、研究院等非高校机构同域）。
- **流程**：注册页输入教育邮箱 → `/public/status` 按白名单识别域名（默认 `edu.cn` 覆盖子域）→ 按钮直接「已核验」，不发验证码、不打开弹层。宿主 `check()` 同样按白名单放行，并在注册时写入 EMAIL PASSED 记录；通过身份核验的注册账号直接 `emailVerified=true`，不再发宿主邮箱验证信。
- **局限（如实告知运营）**：校友邮箱毕业后保留、教职工与学生同域、部分高职无学生邮箱——教育邮箱只能证明「与该域有关系」，定位是**低门槛准入/氛围筛选**，不是强学籍核验。任意能拿到白名单域邮箱的人即可注册。
- **发信可达性**：教育邮箱渠道不再依赖发信。审核结果通知与学信网无关的邮件仍走 `framework().mail()`；高校 MX 反垃圾严格，部署侧仍建议配 SPF+DKIM+DMARC。

### 3.3 CARSI

- **准入条件是硬伤**（官方 wiki：carsi.atlassian.net/wiki/spaces/CAW/pages/27103679）：仅面向教学科研相关应用；申请主体必须是内容/资源/产品生产方；需**两所已上线高校推荐**；已上线厂商新增资源需**至少 5 份有效期内的正式高校采购合同**。普通公司/社会组织无法满足。
- 即便接入，CARSI 属性下发**不提供学工号**，只有匿名化 `pairwise-id`（可用 `eduPersonScopedAffiliation` 区分校域与 student/faculty 类别）。
- 技术上基于 Shibboleth/SAML2（宿主无 SAML 基础设施，需插件自建 SP 或用官方 OAuth 网关），费用目前免费，周期以月计。
- **iframe/侧信道探测方案评估（2026-09-05 实测）**：`ds.carsi.edu.cn/login/index.html`（用户选校 → 跳转校 IdP 登录的发现服务页）**实测响应头无 X-Frame-Options、无 CSP frame-ancestors，iframe 嵌入不被浏览器拦截**。但方案仍不可行，死在四个更底层的点上：
  1. **同源策略**：父页面跨域无法读 iframe 的 DOM、最终 URL、表单状态；`iframe.onload` 只能告诉你「发生了一次导航」，无法区分登录成功/失败/用户随手关掉。历史上检测登录态的侧信道（history.length、跳转计时、错误页差异）早已被各浏览器修掉或不可靠。
  2. **第三方 Cookie 拦截**：iframe 内 ds.carsi.edu.cn 的会话 Cookie 属第三方上下文，Chrome 弃用第三方 Cookie、Safari/Firefox 默认拦截，**iframe 内登录流程本身就可能走不通**（SAML 链路上 DS→IdP→SP 多次跨站跳转，任何一环 Cookie 丢失即中断）。
  3. **没有可信回传**：就算能「探测到用户似乎登录了」，这个信号来自客户端，**任何会开 DevTools 的用户都能伪造**——认证价值为零。能当凭证的只有「签发给我们的 SAML 断言」，而拿到断言的前提就是注册 SP（entityID+metadata），回到准入门槛。
  4. **「iframe JS 注入构造双向通信」不成立**（2026-09-05 复查）：跨域 iframe 里**注入不了任何 JS**——父页访问跨域 iframe 的 `contentDocument`/`contentWindow` 成员即被同源策略拒绝，注入与读取是同一把锁；唯一跨域通道 `postMessage` 需要**被嵌入方主动监听并回话**，ds.carsi.edu.cn 不向任意父页发消息，我们单方面发消息无人接收。剩下两种变相「注入」：① 浏览器扩展 content script（客户端方案，上报可伪造、分发成本高，见上条）；② 反向代理把 CARSI 流量代理到自己域名下变成同源——纯技术角度看，TLS 终结型反向代理重写域名/Cookie/页面确实能把第三方站点变「同源」，这正是 Evilginx 类钓鱼框架的工作原语；但落到 CARSI 场景**技术上也失败**：SAML 链路横跨 ds.carsi.edu.cn + 900+ 所高校各自 IdP 域名，需逐校枚举代理并重写各自的绝对 URL/CSP/子资源，任何一处漏改链路静默断裂；高校 IdP 的 WebAuthn/passkey 凭证绑定 origin，代理域名下直接硬失败，登录根本无法完成；各校风控会标记数据中心代理 IP 与设备指纹异常；SAML 断言始终签发给真实 SP，代理观察到的「登录成功」对我们不产生任何可核验凭证。且无论意图如何，用户真实学工号密码必然经过我方服务器，这在架构定义上就是凭证截获（钓鱼基础设施），**明确不做、也不提供实现**。
  5. **更本质的问题：对手模型**。我们要防的伪造者就是用户本人，而浏览器是用户的领地——任何「在用户浏览器里运行的代码报告他登录成功了」都可被本人伪造（DevTools/抓包重放/扩展）。不可伪造的证据只有一种：CARSI/校 IdP **签发给我们的 entityID** 的 SAML 断言（或带 redirect_uri 回跳的 OAuth 授权码），其前提就是 SP 注册。客户端侧的一切花样都绕不开这个密码学事实。
  6. **浏览器扩展方案**：content script 确实能读 CARSI 门户登录后的页面 DOM（可见校名），但——扩展上报的数据同样可伪造；分发安装 UX 成本极高；从他人服务的页面抽取断言/属性属于抓取第三方凭证，合规红线。不值得。
  - 用户引导式「登录后粘贴点什么回来」同理：URL/截图无签名不可核验，截图即退化回人工审核渠道。
- **设计处理**：领域模型与注册门禁扩展点按渠道抽象，CARSI 作为一个 provider 类型预留在模型里（`CARSI` 渠道枚举 + 管理端「未开通」展示），不实现接入代码；未来若主体获得资质，单独评估 SAML SP / 官方 OAuth 网关接入。

### 3.4 人工审核

- **业界主流兜底**（GitHub Education 中国用户大量走此通道），**必配**。
- 材料类型：学生证（含注册章页）/校园卡/录取通知书/毕业证照片，或**学信网 16 位在线验证码 + 报告截图**（推荐，核验成本最低）。
- **姓名与学校**：学信网自动核验从官方报告页截取，用户只需填联系邮箱和 16 位验证码。直接人工审核、学信网转入人工后再补真实姓名与学校；管理员通过时可补全或纠正，纠正结果写入认证记录，并在注册绑定后进入人员管理标签（`edu-verify` 命名空间：`name` / `school` / `status`）。
- 审核要素：姓名+学校+年份一致性；学生证注册章/有效期；截图元数据；对学信网验证码按比例到官方页抽查；同图哈希去重防一证多用。
- **隐私合规（PIPL）**：学历学籍不在第 28 条敏感个人信息列举内，但稳妥起见按敏感标准做——注册/提交时单独同意条款、最小化收集（只存核验结论 + 必要凭证，审核完成后定期清理原始图片）、限定审核人员访问、全量审计日志、注销即删。接第三方 OCR 属于委托处理需披露（本期不接 OCR，人工目检即可）。

## 4. 宿主 SPI / 前端 SDK 能力盘点与缺口

### 4.1 现成可用（SPI 2.20.0：本仓根 pom 已对齐；标签接口需本地/Nexus 发布后编译）

| 能力 | SPI | 用途 |
|---|---|---|
| **注册认证门禁** | `IdentityVerificationProvider`（SPI **2.18.0** 新增，`system/auth`） | **官方为「注册时认证审查」量身定做的扩展点**：宿主注册时调 `check(VerificationSubject)`；站点设置 `system.auth.registration.required-verifications` 声明必需方式，未通过 **fail-closed** 拒绝注册。配套还有 `RegisterInterceptor`/`LoginInterceptor`（前置否决）与 `AuthEventListener`。Javadoc 示例直接就是「学信网、教育邮箱、CARSI、人工审核」 |
| 邮件发送 | `FrameworkServices.mail()`（异步，text/html） | 教育邮箱验证码、审核结果通知 |
| 用户信息 | `users()` → `PluginUserProfile`（id/username/nickname/email/phone/avatar/status） | 展示归属 |
| **用户标签** | `users().listTags` / `replaceTags(userId, namespace, tags)`（SPI **2.20.0**） | 把姓名、学校名与认证状态写到人员管理只读标签；插件命名空间 `edu-verify`，code=`name`/`school`/`status` |
| 文档存储 | `documents(pluginCode)` | 认证记录、验证码、域名白名单、审计日志 |
| 文件存储 | `files(pluginCode)`（S3，put/get/delete） | 人工审核凭证图片 |
| 平台文件桥 | `platformFile(fileId)` | 登录后物料库等仍可用；**学历认证注册材料不再走此路**（宿主上传需登录，匿名会 401） |
| 凭证预览 | `filePreview().signedFileUrl(...)`（SPI 2.15.0，双闸门） | 管理端审核时看凭证图 |
| QQ 私聊通知 | `messaging().sendDirectToBoundUser(userId, ...)` | 审核结果通知（邮件之外的第二通道） |
| 动态表单 | `forms()` | 可选：人工审核申请表复用表单能力 |
| 定时任务 | 无 SPI，规范允许插件自建 `ScheduledExecutorService` + `context.onDispose` | 验证码/待审记录过期清理、凭证图片定期删除 |
| HTTP 外呼 | 无 SPI 端口但无沙箱，直接用 JDK `HttpClient` | 仅用于非学信网场景；学信网走宿主 `framework().render().htmlFromUrl` |

### 4.2 缺口与处置（按「可自行添加」授权）

| 缺口 | 影响 | 处置 |
|---|---|---|
| 无 Web 站内信（宿主内部也没有） | 审核结果通知体验 | 本期用 **邮件 + QQ 私聊**；若后续要站内信，在宿主新增 NotificationService 再透出 SPI（单独评估） |
| 用户无扩展字段（读不到注册时间、不能给用户打「已认证」标） | 其他页面想展示认证标识 | **1.1.0 已用 SPI 2.20.0 用户标签覆盖人员管理展示**：`replaceTags(userId, "edu-verify", …)` 写入学校名（`school`）与状态（`status`：已认证/审核中/已驳回/已撤销）。PENDING 在用户尚未注册时只存在插件审核队列，注册成功后再打标签。跨插件消费仍走插件 `*.api` 包 |
| 插件端点无 multipart | 材料上传 | **1.1.4**：`POST /public/manual/upload` 收 JSON base64（插件端点只能吃 String body）；落 `context.files()` 暂存 2 小时，提交时再迁入审核凭证。不再依赖宿主 `/api/files/upload` |
| SDK 无上传进度 | 大图上传体验 | 小图直接 `sdk.files.uploadImage`；需要进度时照 material 的 XHR 姿势自写（已有现成代码可搬） |
| 宿主注册流程不支持「先认证后注册」的页面内嵌 | 注册页 UX | **1.1.1 已落地、1.1.2 已合并教育邮箱与注册邮箱**：白名单教育邮箱直接「已核验」；其它渠道仍走 `FaModal`；失败时可回退独立公开页 `/edu-verify` |

结论：**门禁、邮件、存储、上传、预览、通知、人员管理标签都有现成契约**。1.1.0 已新增 SPI 2.20.0 用户标签；站内信仍可后置。

## 5. 插件设计基线

### 5.1 核心流程（注册门禁）

```
注册前（匿名，注册页弹层，公开页 /edu-verify 仅作兜底）：
  EMAIL  GET /public/status?email=
         → 白名单域名 → passed=true, eduDomain=true（无需验证码）
  CHSI   POST /public/chsi/begin → captcha? → verify {email, vcode, captcha?}
         → PASSED / CAPTCHA_REQUIRED / STALE_SESSION / DEGRADED(转 MANUAL)
  MANUAL POST /public/manual/upload {email, filename, contentType, content(base64)}
         → 暂存 fileId（2h）；仅 JPEG/PNG/GIF/WebP/PDF，校验魔数
         POST /public/manual/submit {email, 材料 fileId...}
         → PENDING，等管理员审核
注册时（宿主，插件启用即强制）：
  1. 注册页有核验方式时，全部 PASSED 才能点「注册」；PENDING / PENDING_MAIL / 未核验禁用按钮，提交时 toast 并打开核验弹层（宿主 register.vue）
  2. POST /api/user/register → RegisterInterceptor.onBeforeRegister（edu-verify 1.5.3+）
     → 空白邮箱 deny「请先填写注册邮箱」
     → 任一渠道未过期 PASSED，或邮箱域名在白名单 → allow（并补写 EMAIL PASSED）
     → PENDING_MAIL → deny「学信网报告邮件确认中…」
     → PENDING → deny「人工审核中，审核通过后即可注册」
     → 否则 deny「请先完成高校学历认证…」
  3. IdentityVerificationProvider.check 仍参与可选核验：通过后 emailVerified=true，跳过宿主验证邮件。
     站点设置 system.auth.registration.required-verifications 无后台 UI，空值时宿主不按提供器 fail-closed；强制拦截不依赖该配置。
注册成功：
  AuthEventListener.onUserRegistered 确保白名单邮箱写入 EMAIL PASSED，把 userId 绑到该邮箱记录，并 replaceTags 写入人员管理标签
公开页返回注册（兜底）：
  走 /login?form=register（不要 /register，宿主会丢掉 email 查询参数），可附带 email
注册页主路径：
  输入白名单教育邮箱 → 行按钮直接「已核验」，「注册」可点
  学信网等待官方邮件 → 「邮件确认中」，「注册」禁用
  人工审核中 → 「审核中」，「注册」禁用，可点开弹层查看进度
  其它邮箱 → 「去核验」打开 FaModal；PASSED 关闭弹窗并回填邮箱，未通过时「注册」禁用
```

`VerificationSubject` 只含 username/email/attributes，因此**预注册认证记录以 email（小写）为键**。`PluginPrincipal.userId` 是 Long，插件侧一律 `String.valueOf`。JSON 长整型保持 string。

### 5.2 渠道与状态机

- 渠道：`EMAIL`（自动）、`CHSI`（zwfw 12 位码，可关、失败转人工）、`MANUAL`（人工，材料含学生证/录取/毕业证/学信码截图等）、`CARSI`（预留，`enabled=false`）。
- 记录状态：`PASSED` / `PENDING` / `PENDING_MAIL` / `REJECTED` / `EXPIRED` / `REVOKED`。
- 教育邮箱按白名单即时视为 `PASSED`（注册时落 EMAIL 记录）；学信网即时 `PASSED`；人工 `PENDING → 通过 PASSED / 驳回 REJECTED`；已通过可撤销。有效期默认 365 天（可配 0=不过期）。
- 门禁方法码只有一个：`edu-verify`。任一渠道未过期 PASSED 即满足。
- 学信网适配器独立于业务层：官方报告页 HTML 抓取走宿主通用能力，解析失败/渲染不可用走 DEGRADED，不把错误吞成通过。

### 5.3 数据模型（documents()，集合均 per-plugin）

| 集合 | 关键字段 |
|---|---|
| `edu_verifications` | id（倒置毫秒-随机）、userId（可空，注册后绑定）、email、channel、status、schoolName、materialType、chsiCode（可选）、validUntil、reviewedBy/reviewedAt/reviewNote、createdAt/updatedAt |
| `edu_email_codes` | id=emailLower、codeHash（不存明文）、expiresAt、attempts、sentAt（限频依据） |
| `edu_materials` | verificationId、objectKey（files() 键 `edu-verify/{verificationId}/{n}`）、originalName、imageHash、createdAt |
| `edu_domains` | domain（小写）、schoolName、source（SWOT/MANUAL）、enabled |
| `edu_audit_logs` | id、actorId、action、targetId、detail、createdAt（审核与敏感操作全量留痕） |

沿用本仓已知规避：id 用倒置毫秒保证字典序=最新在前；`findByField` 有回归，一律 findAll 200/页扫描 + 内存过滤；save 会覆写 `id` 字段，业务字段不用 `id` 名。

### 5.4 HTTP API（挂 `/api/plugins/edu-verify`）

- **公开（匿名）**：`GET /public/methods`、`GET /public/status?email=`、`POST /public/email/send-code`、`POST /public/email/verify`、`POST /public/chsi/begin`、`POST /public/chsi/captcha`、`POST /public/chsi/verify`、`POST /public/manual/submit`。
- **无用户端**：1.1.0 起不再提供 `/me`、`plugin:edu-verify:view` 与个人页；平台普通用户不进入本插件菜单。
- **管理端 /admin/**（`plugin:edu-verify:manage`）：`GET /admin/verifications`（分页）、`GET /admin/verifications/{id}`（含签名预览 URL）、`POST .../approve|reject|revoke`（驳回/撤销必填原因）、`GET/PUT /admin/domains`、`DELETE /admin/domains/{domain}`、`GET/PUT /admin/settings`、`GET /admin/audit-logs`。
- 公开页人工上传走插件 `/public/manual/upload`（JSON + Base64，魔数/大小/限流）；提交时把暂存材料迁入审核记录。

### 5.5 前端（@yudream/plugin-edu-verify）

- 注册页主路径：宿主 `register.vue` 点「去核验」加载插件 `RegisterForm` 到 `FaModal`，PASSED 关闭弹窗并回填邮箱；人工提交保持弹窗打开。
- 公开页 `/edu-verify`：仅作加载失败或直链兜底；「返回注册」走 `/login?form=register` 并保留邮箱。
- 无用户页：认证只发生在注册前。
- 管理审核 `/platform/plugins/edu-verify/admin`：FaTable（accessorKey、`table-root-class` 含 `rounded-lg overflow-hidden`）+ 分页 `class="mt-3"` + FaDrawer 详情 + 通过确认；驳回/撤销 FaModal 用 `:before-close`，空原因、请求中、接口失败都不关窗。人员管理用户表展示插件写入的学校/状态标签。
- 域名白名单、认证设置：独立管理路由，不塞巨型 tabs。新增域名同样 `:before-close`。
- 样式一律 `styles.css` 语义类（宿主 UnoCSS 不扫插件包）；中文 UTF-8。

### 5.6 已拍板的决策

- **D1 门禁**：一个方法码 `edu-verify`；EMAIL / CHSI / MANUAL 任一未过期 PASSED 即可注册。人工未审完不能注册。
- **D2 插件 code**：独立 `edu-verify`，不并入 student-info。
- **D3 有效期**：默认 365 天，管理端可改；0 表示不过期。
- **D4 学信网**：16 位官方报告页做成独立渠道，带日配额、杀开关与可配置 CSS 选择器；不做打码、不逆向 zwfw。
- **D5 CARSI**：只展示未开通，不实现接入代码。

## 6. 合规与安全清单

1. 注册/提交页展示认证专项单独同意条款（处理目的、范围、保存期限、删除方式）。
2. 凭证图片审核完成后由定时任务定期删除（如通过后 30 天），只留核验结论与哈希。
3. 审核、撤销、白名单变更全量审计日志。
4. 验证码只存哈希 + 限频 + 试错锁定；公开端点加限流防刷。
5. 不接第三方 OCR/背调 API、不逆向 zwfw、不接 CARSI。
6. 发信域名配 SPF+DKIM+DMARC，国内发信通道。

## 7. 实施分期

- **Phase 1（1.0.0 已交付，未进 `release/plugins.txt`）**：教育邮箱 + 学信网渠道（可关/配额/降级）+ 人工审核 + 注册门禁 `edu-verify` + 邮件/QQ 通知 + 凭证 24h 清理任务 + 公开/管理页面。
- **Phase 1.1.0（已交付，未进清单）**：去掉用户页与 `/me`；PENDING 注册拒绝文案；SPI 2.20.0 用户标签写入人员管理。
- **Phase 1.1.1（已交付，未进清单）**：注册页弹层核验，独立公开页降为兜底。
- **Phase 1.1.2（已交付，未进清单）**：教育邮箱改域名识别即通过；与宿主注册邮箱验证合并，通过门禁的账号不再发验证信。
- **Phase 1.1.3（已交付，未进清单）**：人工审核/学信网改为联系邮箱；注册页核验查询 400ms 防抖。
- **Phase 1.1.4（已交付，未进清单）**：匿名注册人工审核材料走插件公开上传；魔数/大小/扩展名/声明类型校验 + IP/邮箱限流。
- **Phase 1.2.0（已交付，未进清单）**：学信网改为 16 位官方报告页；宿主 SPI 2.21.0 `htmlFromUrl` + render-server `/v1/render/url-html`；管理端可配 CSS 选择器。
- **Phase 2（可选）**：站内信 SPI、跨插件认证标识、到期复审提醒、OCR 辅助。
- **不做**：CARSI iframe / JS 注入 / 反向代理、打码平台、zwfw 逆向。

## 8. 主要调研来源

- CARSI 准入与费用：carsi.edu.cn/aboutCARSI.html、/joinCARSI.html、/docs/fee_policy_zh.pdf；carsi.atlassian.net（SP 申请流程/FAQ）；mgmt.carsi.edu.cn/reg
- 学信网：chsi.com.cn/xlcx/bg.do（16 位报告页，浏览器可打开完整 SSR HTML；数据中心直连 412，须经 render-server）
- 域名数据集：github.com/JetBrains/swot（MIT，cn/edu 约 480 条目）、github.com/Hipo/university-domains-list
- 业界组合：GitHub Education（swot+人工）、Apple 国行（UNiDAYS→支付宝学生认证）、京东（学信验证码+校园邮箱）
