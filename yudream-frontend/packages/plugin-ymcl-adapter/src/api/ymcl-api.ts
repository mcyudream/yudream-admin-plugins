import type {
  BundleDoc,
  Capabilities,
  CasFilePreview,
  CustomPageDoc,
  DomainConfig,
  HomeConfig,
  ManifestPayload,
  NavigationConfig,
  NavigationConfigItem,
  PackDoc,
  PackMetaForm,
  PackVersionDoc,
  PackVersionSummary,
  ServerBinding,
  ServerView,
  ThemeConfigView,
  ThemeProfile,
} from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'

function apiBase(sdk: YuDreamPluginSdk) {
  // 宿主负责 dev 代理（/proxy 前缀）与生产 baseUrl 的地址解析，禁止自行拼 origin
  return sdk.http.url('')
}

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('token') || ''
  return token ? { Authorization: token } : {}
}

async function unwrap(response: Response) {
  const text = await response.text()
  let body: unknown = null
  try {
    body = text ? JSON.parse(text) : null
  }
  catch {
    throw new Error(`响应不是有效 JSON (${response.status})`)
  }
  if (!response.ok) {
    const message = (body as { message?: string } | null)?.message || `请求失败 (${response.status})`
    throw new Error(message)
  }
  if (body === null) {
    throw new Error('响应体为空')
  }
  return body
}

/**
 * 协议端点（capabilities/manifest/MIP）对 YMCL 启动器输出裸 JSON（wrapResult=false），
 * 宿主 axios 拦截器只认 {code:200} 信封——管理页消费这些端点必须用裸 fetch + token。
 */
export function createYmclApi(sdk: YuDreamPluginSdk) {
  const base = apiBase(sdk).replace(/\/$/, '')

  async function rawGet<T>(path: string): Promise<T> {
    const response = await fetch(`${base}${path}`, { headers: { ...authHeaders() } })
    return unwrap(response) as Promise<T>
  }

  async function rawPut<T>(path: string, data: unknown): Promise<T> {
    const response = await fetch(`${base}${path}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', ...authHeaders() },
      body: JSON.stringify(data),
    })
    return unwrap(response) as Promise<T>
  }

  return {
    rawGet,
    rawPut,
    capabilities: () => rawGet<Capabilities>('/v1/capabilities'),
    manifest: () => rawGet<ManifestPayload>('/v1/manifest'),
    domainConfig: () => sdk.http.get<DomainConfig>('/v1/chrome/domain'),
    saveDomainConfig: (data: DomainConfig) =>
      sdk.http.request<{ saved: boolean }>('/v1/chrome/domain', { method: 'PUT', data }),
    homeConfig: () => sdk.http.get<HomeConfig>('/v1/chrome/home'),
    saveHomeConfig: (data: HomeConfig) =>
      sdk.http.request<{ saved: boolean }>('/v1/chrome/home', { method: 'PUT', data }),
    navigationConfig: () => sdk.http.get<NavigationConfig>('/v1/chrome/navigation'),
    saveNavigationConfig: (items: NavigationConfigItem[]) =>
      sdk.http.request<{ saved: boolean }>('/v1/chrome/navigation', { method: 'PUT', data: { items } }),
    resetNavigationConfig: () =>
      sdk.http.request<{ saved: boolean, reset: boolean }>('/v1/chrome/navigation', { method: 'PUT', data: { reset: true } }),
    customPages: () => sdk.http.get<{ pages: CustomPageDoc[] }>('/v1/chrome/pages'),
    saveCustomPage: (data: Partial<CustomPageDoc>) =>
      sdk.http.request<{ saved: boolean, id: string }>('/v1/chrome/pages', { method: 'PUT', data }),
    deleteCustomPage: (id: string) =>
      sdk.http.request<{ deleted: boolean }>(`/v1/chrome/pages/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    themeConfig: () => sdk.http.get<ThemeConfigView>('/v1/chrome/theme'),
    saveThemeConfig: (theme: ThemeProfile | { reset: boolean }) =>
      sdk.http.request<{ saved: boolean, reset?: boolean }>('/v1/chrome/theme', { method: 'PUT', data: theme }),
    packs: () => sdk.http.get<{ packs: PackDoc[] }>('/v1/admin/packs'),
    updatePack: (packId: string, data: PackMetaForm) =>
      sdk.http.request<{ pack: PackDoc }>(`/v1/admin/packs/${encodeURIComponent(packId)}`, {
        method: 'PUT',
        data,
      }),
    deletePack: (packId: string) =>
      sdk.http.request<{ deleted: boolean, packId: string, versions: number, unboundServers: number }>(
        `/v1/admin/packs/${encodeURIComponent(packId)}`,
        { method: 'DELETE' },
      ),
    deletePackVersion: (packId: string, version: string) =>
      sdk.http.request<{ deleted: boolean }>(
        `/v1/admin/packs/${encodeURIComponent(packId)}/versions/${encodeURIComponent(version)}`,
        { method: 'DELETE' },
      ),
    casPreview: (sha512: string, path?: string) =>
      sdk.http.get<CasFilePreview>(
        `/v1/admin/cas/${encodeURIComponent(sha512)}/preview${path ? `?path=${encodeURIComponent(path)}` : ''}`,
      ),
    packVersions: (packId: string) =>
      rawGet<{ versions: PackVersionSummary[] }>(`/mip/api/packs/${encodeURIComponent(packId)}/versions`),
    packVersion: (packId: string, version: string) =>
      rawGet<PackVersionDoc>(`/mip/api/packs/${encodeURIComponent(packId)}/manifest/${encodeURIComponent(version)}`),
    servers: () => rawGet<{ servers: ServerView[] }>('/mip/api/servers'),
    saveBinding: (serverId: string, binding: { packId: string, channel: string, pinnedVersion: string | null, updatePolicy: string }) =>
      rawPut<ServerBinding>(`/mip/api/servers/${encodeURIComponent(serverId)}/binding`, binding),
    bundles: () => sdk.http.get<{ bundles: BundleDoc[] }>('/v1/admin/bundles'),
    uploadBundle: (bundleId: string, version: string, zip: File) =>
      new Promise<{ saved: boolean, sha256: string, size: number }>((resolve, reject) => {
        const reader = new FileReader()
        reader.onerror = () => reject(new Error('读取 zip 文件失败'))
        reader.onload = () => {
          const data = String(reader.result || '').split(',')[1] || ''
          if (!data) {
            reject(new Error('zip 内容为空'))
            return
          }
          sdk.http.request<{ saved: boolean, sha256: string, size: number }>(
            `/v1/bundles/${encodeURIComponent(bundleId)}/${encodeURIComponent(version)}`,
            { method: 'PUT', data: { data } },
          ).then(resolve).catch(reject)
        }
        reader.readAsDataURL(zip)
      }),
  }
}
