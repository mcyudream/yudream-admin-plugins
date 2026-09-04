# 电子物料库（yudream-plugin-material）设计

> 版本 1.0.0 设计基线。插件 code：`material`；包名 `online.yudream.base.plugin.material`；前端包 `@yudream/plugin-material`。

## 1. 目标与范围

用户可上传电子物料（PPT/Excel/PSD/图片/视频/音频/PDF/Office 文档/文本等），在线预览（kkFileView 由宿主平台能力统一提供）、下载、删除、更新（每次更新产生新版本，可回溯/回滚）、生成公开分享外链；管理员可跨用户管理全部物料、维护分类。预览引擎配置已上移为宿主平台能力（平台能力 > 文件预览，部署/运行双闸门），插件自身不含预览设置。

不在本期范围：格式级在线编辑（Office/PSD 协同编辑、文本在线编辑）、缩略图服务、全文检索。

## 2. 关键架构决策

1. **预览走宿主平台能力**：插件侧 `PreviewService` 只是薄适配——把物料/版本转成 SPI `PluginPreviewFile` 交给 `framework().filePreview()`（SPI `PluginFilePreviewService`，2.15.0 引入），由平台决定 KKFILE / DIRECT / NONE。链路：
   `前端 iframe → kkFileView /onlinePreview?url=base64(签名文件URL) → kkFileView 服务端回源 → 宿主 /api/public/preview/file/{token}/{filename} → 插件文件存储`。
   签名公开端点由宿主提供（HMAC-SHA256 短时效 token，默认 30 分钟，凭据从 `YUDREAM_CREDENTIAL_KEY` 派生；token 绑定 pluginCode+objectKey+过期时间），Range 分段流式输出，不再内存缓冲整文件。文件名放在路径最后一段（含扩展名），保证 kkFileView 按扩展名识别类型。
2. **配置在宿主平台能力**：kkFileView 服务地址（baseUrl）、回源基址（callbackBaseUrl）、officePreviewType、token 时效、预览大小上限随「平台能力 > 文件预览」能力模块入库，管理入口即平台能力页（权限 `platform:capability:*`），**不写配置文件**；部署侧另有环境闸门 `PLATFORM_FILE_PREVIEW_ENABLED` 控制能力是否出现。插件不再持有预览设置（§9 演进第 1 条已落地）。
3. **上传走宿主桥**：插件 HTTP 层 body 只有 String（无 multipart），故前端先用 `sdk.files.uploadImage`（通用任意文件）上传到宿主 `/api/files/upload`，拿到 `fileId` 后调用插件 `POST /me/materials {fileId, ...}`，后端用 `framework().platformFile(fileId)` 读流并复制进插件 `files()` 命名空间。不进度条（宿主 SDK 无进度回调，与现有插件一致），上传中态用 spinner。
4. **文件存储**：`context.files()`（S3/RustFS）。objectKey 不可变：`materials/{materialId}/v{n}/file`，原始文件名只存元数据。删除版本/物料时同步删对象。
5. **文档存储**（`context.documents()`，Mongo）：四个集合（§3）。规避宿主两个已知坑：`_id` 字典序升序（物料 id 用**倒置毫秒时间戳**保证「字典序=最新在前」）；`findByField` 有先分页后过滤的回归，**只用 findAll 200/页扫描 + 内存过滤**。宿主 save 会覆写 `id` 字段，文档内不使用 `id` 承载业务字段。
6. **版本并发**：每物料一把 striped lock 内完成「读 currentVersion → 写新 version 文档 → 更新物料指针」，防并发上传拿到相同版本号。回滚只是移动 `currentVersion` 指针，不动对象。
7. **权限模型**：`plugin:material:view`（用户端 /me/** 的读操作）、`plugin:material:manage`（/admin/** 全部）。/me 归属只取 `principal.userId()`，无管理员越权分支；管理员在用户端同样只能看自己的。`/public/share/**` 无权限注解，仅靠存储型分享凭证；签名文件端点由宿主暴露，不在插件内。
8. **分享外链**：token 即文档 id（18 字节 SecureRandom → 24 位 base64url，约 144 bit 熵），点查无需扫表；是**存储型凭证**——撤销=删文档，expiresAt=0 表示永久，过期由服务端在每次解析时判定。分享始终解析物料的**当前版本**（回滚/新版本自动跟随），归档不吊销已发链接（软隐藏非安全边界，与签名 token 一致），删除物料时级联删除全部分享。分享页 `/public/share/{token}` 由插件端点直接输出 HTML（无需宿主前端路由），kkFileView 预览把分享文件流的绝对地址交给平台 `previewExternal` 组装 iframe 地址，回源 `/public/share/{token}/file/{filename}`。

## 3. 数据模型（文档存储）

### materials
| 字段 | 类型 | 说明 |
|---|---|---|
| id | string | `倒置毫秒(13位补零)-随机8hex`，字典序=最新在前 |
| name | string | 显示名（默认原始文件名） |
| ext | string | 小写扩展名，无点 |
| type | string | MaterialType 枚举名 |
| categoryId | string|null | 分类 |
| tags | string[] | ≤8 个，每个 ≤20 字 |
| ownerId | string | 拥有者 userId（String 承载 Long） |
| ownerName | string | 冗余显示名 |
| currentVersion | int | 当前版本号 |
| size / contentType | long / string | 当前版本大小与 MIME |
| status | string | ACTIVE / ARCHIVED（归档：用户端列表默认隐藏） |
| visibility | string | PRIVATE（仅自己）/ DEPT（仅部门）/ PUBLIC（全站成员），缺省 PRIVATE |
| deptIds | string[] | visibility=DEPT 时显式选择的可见部门 id（1.6.0 起由用户/管理员选择；更早版本为保存时属主部门快照） |
| deptNames | string[] | deptIds 对应的部门名冗余，用于展示 |
| createdAt / updatedAt | long | epoch millis |

### material_versions
| 字段 | 类型 | 说明 |
|---|---|---|
| id | string | `{materialId}#{版本号补零6位}` |
| materialId / version | string / int | |
| objectKey | string | `materials/{materialId}/v{n}/file` |
| originalName / size / contentType | | 该版本元数据 |
| note | string | 版本备注（可选） |
| uploaderId / uploaderName / createdAt | | |

### material_categories
`{ id, name, sort, createdAt }`。删除时若有物料引用则拒绝（返回引用数量）。

### material_shares
`{ id(=token), materialId, createdById, createdByName, note, expiresAt, createdAt }`。id 即分享 token（24 位 base64url 随机串）；expiresAt=0 表示永久。物料删除时级联删除。

## 4. HTTP API（挂载 `/api/plugins/material`）

### 用户端 /me/**（permission=view，归属=principal.userId）
| 方法/路径 | 说明 |
|---|---|
| GET /me/materials?keyword&type&categoryId&status&tag&page&size | 分页列表（tag 为精确匹配，忽略大小写；含归档过滤） |
| POST /me/materials `{fileId, filename, name?, categoryId?, tags?, visibility?, deptIds?}` | 从宿主上传落库为物料 v1（visibility=DEPT 时 deptIds 必填且须全部 ∈ 自己加入的部门） |
| POST /me/materials/import-folder `{items[], folderName, categoryId?, tags?, visibility?, deptIds?}` | 文件夹导入批量落库（≤200 个；分类用文件夹名，中间层子文件夹名追加为标签） |
| GET /me/materials/{id} | 详情（含当前版本、分类名） |
| PUT /me/materials/{id} `{name, categoryId, tags, visibility?, deptIds?}` | 改元数据（DEPT 校验同创建） |
| DELETE /me/materials/{id} | 删除物料+全部版本+对象 |
| GET /me/materials/{id}/versions | 版本列表（倒序） |
| POST /me/materials/{id}/versions `{fileId, filename, note?}` | 上传新版本 |
| POST /me/materials/{id}/restore `{version}` | 回滚到指定版本 |
| DELETE /me/materials/{id}/versions/{version} | 删除非当前版本（同时删对象） |
| GET /me/materials/{id}/download?version= | 附件下载（Content-Disposition） |
| GET /me/materials/{id}/preview?version= | 预览信息（§5） |
| POST /me/materials/{id}/shares `{expiresInHours?, note?}` | 创建分享外链（expiresInHours 缺省=永久，1..8760） |
| GET /me/materials/{id}/shares | 该物料的分享列表（按创建时间倒序） |
| DELETE /me/materials/{id}/shares/{shareId} | 撤销分享（删凭证文档，立即失效） |
| GET /me/categories | 分类列表（选择器用） |
| GET /me/departments | 当前用户**自己加入的部门**选项（DEPT 可见范围选择器用；普通用户不暴露全量部门树） |
| GET /me/tags | 标签云（自己未归档物料的标签计数，次数降序，最多 100 个） |
| GET /me/covers?ids=a,b,c | 批量签发图片物料封面（≤60 个 id，返回 `{id, url}` 列表；url 为平台签名公开路径 `/api/public/preview/file/...`，前端用 `sdk.files.assetUrl()` 解析；非图片/越权/已删除的 id 静默跳过） |

### 管理端 /admin/**（permission=manage）
| 方法/路径 | 说明 |
|---|---|
| GET /admin/materials?keyword&type&categoryId&owner&status&page&size | 跨用户分页列表 |
| GET /admin/materials/{id} / GET .../versions / GET .../download / GET .../preview | 详情/版本/下载/预览（同样走签名链） |
| PUT /admin/materials/{id} `{name?, categoryId?, tags?, visibility?, deptIds?}` | 编辑任意物料元数据（DEPT 校验：deptIds 须存在于全量部门树） |
| POST /admin/materials/{id}/versions `{fileId, filename, note?}` | 代传新版本（uploader 记为操作者） |
| POST/GET /admin/materials/{id}/shares；DELETE .../shares/{shareId} | 代管分享外链（createdBy 记为操作者） |
| DELETE /admin/materials/{id} | 删除任意物料 |
| PUT /admin/materials/{id}/status `{status}` | 归档/恢复 |
| PUT /admin/materials/batch/category `{ids, categoryId}` | 批量移动分组（空 categoryId=移出分类；ids ≤200，逐项容错） |
| PUT /admin/materials/batch/tags `{ids, tags, mode}` | 批量打标签（APPEND 合并去重，合并后超 8 个计入失败；REPLACE 直接替换） |
| PUT /admin/materials/batch/status `{ids, status}` | 批量归档/恢复 |
| POST /admin/materials/batch/delete `{ids}` | 批量删除（POST 带 body 避开 DELETE body 兼容性；复用级联删除） |
| GET /admin/departments?keyword= | 全量部门树拍平选项（管理端 DEPT 选择器用，label 带父级路径） |
| GET/POST /admin/categories；PUT/DELETE /admin/categories/{id} | 分类维护 |

批量端点统一返回 `{total, succeeded, failures:[{id, name, message}]}`，单项失败不中断整批。

### 公开 /public/**（无权限注解）
| 方法/路径 | 说明 |
|---|---|
| GET /public/share/{token} | 分享页（服务端渲染 HTML）：按物料类型内嵌预览（kkFileView iframe / 原生 img/video/audio/iframe）+ 下载按钮；无效/过期输出人性化错误页 |
| GET /public/share/{token}/download | 分享下载（附件，始终当前版本） |
| GET /public/share/{token}/file/{filename} | 分享文件流（Range 206，kkFileView 回源与浏览器直读共用） |

错误约定：参数错误抛 `IllegalArgumentException`（宿主统一 400），不存在返回 `PluginHttpResponse.rawJson(404, {message})`，未登录/越权由宿主权限拦截。

## 5. 预览协议（前后端契约）

`GET .../preview` 返回：
```json
{ "mode": "KKFILE" | "DIRECT" | "NONE", "url": "...", "message": "..." }
```
- `KKFILE`：url 为 kkFileView 完整 iframe 地址（绝对地址，前端直接 `<iframe :src>`）。回源 URL 由平台拼：callbackBase + `/api/public/preview/file/{token}/{encodeURIComponent(filename)}`，再 base64 + URL 编码进 `onlinePreview?url=`，并按平台设置追加 `&officePreviewType=`。分享预览走 `previewExternal`：插件自拼分享文件流绝对地址（平台 callbackBaseUrl，缺省时从请求 Host / X-Forwarded-Proto 推导）交给平台组装。
- `DIRECT`：url 为平台签名公开路径 `/api/public/preview/file/...`，前端用 `sdk.files.assetUrl(url)` 解析成绝对地址（图片/视频/音频/PDF/文本用原生标签渲染，不走 iframe）。
- `NONE`：不可预览（平台未启用 kkFileView 且浏览器无法直读 / 超过大小上限），message 为给用户看的提示。

## 6. 前端页面（Vue3 + @yudream/components，route 全路径 + query 传 id）

| 路由 | 组件 | 权限 | 说明 |
|---|---|---|---|
| /platform/plugins/material | material/Library | view | 物料库：头部搜索/类型/上传 + 左侧分类导航/标签云/状态筛选 + 缩略图卡片网格（悬停操作层）+ 分页 + 上传/文件夹导入/编辑/分享弹窗；有 manage 权限者在他人卡片上也有 编辑/分享（走 /admin 通道） |
| /platform/plugins/material/detail | material/Detail | view, hideInMenu | 详情：预览区（PreviewFrame）+ 版本列表 + 元数据编辑 + 分享弹窗 + 下载/回滚/删除；manage 权限者可代传新版本/代管分享，回滚/删版本仍仅属主 |
| /platform/plugins/material/admin | material/Admin | manage | 跨用户物料表格（FaTable 勾选；行操作 预览/编辑/新版本/分享/下载/归档/删除；批量 移动分组/打标签(追加·覆盖)/归档/恢复/删除） |
| /platform/plugins/material/admin/categories | material/Categories | manage | 分类表格维护 |

布局（用户特别要求重视）：库页为「头部工具区 + 左侧栏 + 卡片网格」三段式——FaPageHeader 内嵌搜索框/类型筛选/上传按钮；左侧栏（`material-sidebar`，200px sticky）含分类导航（带物料计数）、标签云（点击精确筛选、再点取消）、状态筛选；主区 `material-grid` 为 `repeat(auto-fill, minmax(180px, 1fr))` 缩略图卡片（正方形缩略区：图片物料显示 /me/covers 签发的公开封面，其余类型显示类型图标 + 扩展名），悬停浮现 预览/下载/编辑/分享/删除 图标操作层，卡片下方显示名称与 类型/大小/当前版本；≤900px 侧栏收为顶部区块。详情页桌面左右分栏（`material-detail-layout` 3fr/2fr——左侧预览卡片含版本切换选择器，右侧版本历史表格含预览/下载/回滚/删除行操作），≤1100px 自动单列堆叠；预览容器最小高 420px、iframe 70vh，图片直读用棋盘格衬底。

## 7. 安全与边界

- 签名 token 由平台签发：HMAC 密钥从宿主统一凭据 `YUDREAM_CREDENTIAL_KEY` 派生（带 `plugin-file-preview:` 前缀隔离用途），token 不含用户信息，仅授权「读某插件某 objectKey」；过期/篡改后回源 404，用户重新点预览即重签。
- 平台端点验签后按 objectKey 直读插件文件存储；物料/版本删除会同步删除文件对象，旧 token 随之失效（对象不存在即 404）。
- 下载/预览/公开端点均校验 materialId 归属的物料未被归档时不影响（归档只影响列表可见性，不吊销已发 token——归档是软隐藏而非安全边界；删除才是）。
- 分享外链（/public/share/**）：token 是高熵随机串且存库可撤销，不签名不派生；每次解析实时判过期与物料存在性，删除物料级联删除全部链接。分享页物料名等文本经 HTML 转义防 XSS；文件流/下载端点对机器调用返回 JSON 错误而非 HTML。
- 上传入口限制：filename 清洗（去路径符/控制符），大小依赖宿主上传上限；intake 流式复制，不落内存。
- 可见范围：DEPT 采用「显式部门集合」——保存时校验 deptIds（用户端须 ⊆ 自己加入的部门，管理端须存在于全量部门树）并冗余 deptNames；读路径按观看者当前部门与物料 deptIds 求交集判定，部门调整即时生效。列表数据范围与权限正交：manage 权限不扩大用户端可见集合，跨用户操作只能走 /admin 端点。

## 8. 已知限制（写入 releaseNotes/文档）

- 下载端点仍采用内存缓冲（占用与文件等量的堆内存）；预览走平台签名端点 Range 分段流式输出，不再整文件入内存，大小受平台 maxPreviewSizeMb 约束。
- 上传无真实进度条（宿主 SDK 限制）。
- kkFileView 需能网络可达宿主（callbackBaseUrl）；容器部署时注意回源地址。

## 9. 平台化演进建议（向宿主 SPI 提交）

1. ~~预览平台化~~ **已落地**（SPI 2.15.0）：`PluginFilePreviewService` 平台服务统一持有 kkFileView 对接与签名公开端点，插件经 `FrameworkServices.filePreview()` 消费；配置与启停由宿主「平台能力 > 文件预览」能力模块双闸门管理（部署闸门 `PLATFORM_FILE_PREVIEW_ENABLED` + 管理后台启用开关）。
2. `PluginFileStore` 增加 `get(objectKey, offset, length)` 或 `presignGet(ttl)`，消除大文件内存缓冲。
3. `sdk.files.upload` 支持进度回调与通用文件语义（当前名为 uploadImage 实为通用上传）。
4. 宿主文件桥支持一次性转移（transfer ownership）而非复制，省一次 S3 往返。

## 10. 部署与配置

### 10.1 kkFileView 服务

插件自身不含 kkFileView；镜像与部署由宿主仓统一管理（宿主仓 `docker/kkfileview/`：官方镜像同步脚本 + 源码自构建 Dockerfile；compose 已内置 `kkfileview` 服务，镜像发布到 `registry.yudream.online/yudream/yudreamadmin/kkfileview`）。运维细节见宿主文档 `docs/platform/file-preview.md`。

要求：
- kkFileView 必须**网络可达宿主**——它按平台下发的签名 URL 回源拉文件。二者同机/同内网时直接填内网地址即可，不必走公网。
- 若 kkFileView 与宿主之间有反向代理/容器网络隔离，在「平台能力 > 文件预览」里把**回源基址**填成 kkFileView 视角下宿主的可达 base（如 `http://backend:8080`），不要依赖自动推导；同时 kkFileView 的 `KK_TRUST_HOST` 必须包含该地址的 host，否则 kkFileView 拒绝回源。

### 10.2 插件安装与授权

1. 按常规流程把 JAR 放入宿主 `plugins/` 并启用（或走商店安装）。
2. **权限不会自动授予角色**（宿主机制）：在宿主角色管理中给目标角色勾选 `plugin:material:view`（用户端物料库）与 `plugin:material:manage`（管理端两页）。菜单按 `*` 或精确权限过滤，未授权用户看不到入口。
3. 进入宿主「平台能力 → 文件预览」：配置 `baseUrl`（kkFileView 浏览器可达地址；推荐 frontend nginx 同源反代地址 `http(s)://<站点>/kkfileview`，本机调试可用 `http://<服务器>:8012` 直连端口）与 `callbackBaseUrl`（回源基址，kkFileView 可达，如 `http://backend:8080`），按需调 officePreviewType/token 时效/大小上限，点**测试**确认连通后再启用。若能力页看不到「文件预览」，检查部署侧环境闸门 `PLATFORM_FILE_PREVIEW_ENABLED`（默认开启）。

### 10.3 排错速查

| 现象 | 排查 |
|---|---|
| 预览 iframe 空白/ kkFileView 报下载失败 | 回源基址不可达或不在白名单：确认 `callbackBaseUrl` 是 kkFileView 容器视角的宿主地址且 `KK_TRUST_HOST` 含其 host；用 `docker exec yudream-kkfileview curl <回源URL>` 验证 |
| 预览提示签名无效/已过期（404） | token 默认 30 分钟，kkFileView 首次拉取慢时可在「平台能力 > 文件预览」调大时效；刷新预览即重签 |
| 预览提示「文件过大」 | 在「平台能力 > 文件预览」调大预览大小上限，或接受仅下载 |
| 未启用时预览显示 NONE | 能力停用时仅图片/视频/音频/PDF/文本可浏览器直读，其余须先在平台能力页启用文件预览 |
| 能力页测试失败 | 检查 `baseUrl` 是否可从**宿主后端**访问（测试请求由宿主后端发出，不是浏览器）；同源反代部署下后端会以 `FILE_PREVIEW_KKFILEVIEW_INTERNAL_URL` 内网地址兜底探测 |
