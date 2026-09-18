# ymcl-content 插件

YMCL 启动器**自有更新平台**。不依赖 `ymcl-adapter` / YAP，也不绑定 Axolotl 的 `update.axlmc.org`。

2.0.0 起移除了内容分发板块（新手指引、更新公告、在线内容），仅保留更新发包能力。

## 启动器公开端点（匿名，wrapResult=false）

路径前缀：`/api/plugins/ymcl-content/v1/update/**`，与 Axolotl update.axlmc.org 协议对齐，便于启动器切换 env base。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/plugins/ymcl-content/v1/update/capabilities` | 更新协议能力发现 |
| GET | `/api/plugins/ymcl-content/v1/update/latest?channel=` | 渠道最新版本摘要 |
| GET | `/api/plugins/ymcl-content/v1/update/history?channel=&limit=` | 启动器版本历史 / 发布说明 |
| GET | `/api/plugins/ymcl-content/v1/update/manifest` | Tauri updater 兼容清单（Header/Query：`X-YMCL-Channel`/`X-YMCL-Platform`/`X-YMCL-Version`） |
| GET | `/api/plugins/ymcl-content/v1/update/versions` | 制品目录（兼容 apt/deb 校验字段） |
| GET | `/api/plugins/ymcl-content/v1/update/downloads/latest?channel=` | 官网下载元数据 |
| GET | `/api/plugins/ymcl-content/v1/update/files/{version}/{filename}` | 站内托管更新包下载 |

`manifest` 响应示例：

```json
{
  "version": "1.2.0",
  "notes": "…",
  "pub_date": "2026-03-20T00:00:00Z",
  "platforms": {
    "windows-x86_64": {
      "signature": "…minisign…",
      "url": "https://your-host/api/plugins/ymcl-content/v1/update/files/1.2.0/xxx.nsis.zip"
    }
  },
  "published_at": "2026-03-20T00:00:00Z",
  "force_update": false
}
```

制品双模式：

- **外部 URL**：`downloadUrl` 指向 GitHub/CNB/CDN
- **站内文件**：上传到 PluginFileStore，公开经 `/v1/update/files/**` 下载

## 启动器 env（URL base 可改）

在 `apps/app-frontend` 构建环境设置：

```env
# 可选：更新 API 独立 base
# 完整更新 API 前缀，例如 https://your-yda-host/api/plugins/ymcl-content/v1/update
# 或仅 origin https://your-yda-host（客户端自动拼插件路径）
VITE_YMCL_UPDATE_URL=https://your-yda-host

# 兼容：VITE_YMCL_CONTENT_URL 仍可作为站点 origin 推导更新 base
# VITE_YMCL_CONTENT_URL=https://your-yda-host

# 可选：Rust 侧运行时覆盖（本地调试）
# YMCL_UPDATE_BASE_URL=https://your-yda-host/api/plugins/ymcl-content/v1/update
# YMCL_UPDATE_LATEST_URL=https://update.axlmc.org/latest
# YMCL_UPDATE_VERSIONS_URL=https://update.axlmc.org/api/versions
```

未配置时回退 Axolotl `update.axlmc.org`（兼容旧构建）。

Tauri `http` capability 需允许你的站点 origin（见 `apps/app/capabilities/plugins.json`）。

## 管理端

- 路由：`/platform/plugins/ymcl-content/releases`
- 权限：`plugin:ymcl-content:manage`
- 更新发包 API（相对 `/api/plugins/ymcl-content`，宿主 wrapped 信封）：
  - `GET /admin/update/releases`
  - `GET|POST /admin/update/releases`
  - `GET|DELETE /admin/update/releases/{version}`
  - `POST /admin/update/releases/{version}/artifacts`（URL 或 base64 file）
  - `POST /admin/update/releases/{version}/artifacts/upload`（multipart）
  - `DELETE /admin/update/releases/{version}/artifacts/{artifactId}`
  - `POST /admin/update/releases/{version}/yank`

## 构建

```powershell
Set-Location yudream-frontend
pnpm install --frozen-lockfile
pnpm --filter @yudream/plugin-ymcl-content run build
Set-Location ..

mvn -pl yudream-plugins/yudream-plugin-ymcl-content -am package -DskipTests
```
