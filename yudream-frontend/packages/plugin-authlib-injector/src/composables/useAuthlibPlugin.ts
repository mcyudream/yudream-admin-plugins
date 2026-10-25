import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { AuthlibDisplaySettings, AuthlibEndpoint, AuthlibStatus, SigningKeyInfo } from '../types'
import { useYdToast } from '@yudream/components'
import { computed, reactive, ref } from 'vue'
import { createAuthlibApi } from '../api/authlib-api'

export function useAuthlibPlugin(sdk: YuDreamPluginSdk) {
  const api = createAuthlibApi(sdk)
  const toast = useYdToast()
  const loading = ref(false)
  const status = ref<AuthlibStatus | null>(null)
  const settings = ref<AuthlibDisplaySettings | null>(null)
  const savingSettings = ref(false)
  const launcherUrl = computed(() => absoluteUrl(api.apiUrl('/')).replace(/\/$/, ''))
  const statusText = computed(() => status.value ? '已连接' : '待刷新')
  const endpoints = computed<AuthlibEndpoint[]>(() => [
    { method: 'GET', path: '/', note: 'ALI 元数据与公钥' },
    { method: 'POST', path: '/authserver/authenticate', note: '账号登录并获取 accessToken' },
    { method: 'POST', path: '/authserver/refresh', note: '刷新访问令牌' },
    { method: 'POST', path: '/authserver/validate', note: '校验访问令牌' },
    { method: 'POST', path: '/authserver/invalidate', note: '吊销访问令牌' },
    { method: 'POST', path: '/authserver/signout', note: '账号登出全部会话' },
    { method: 'POST', path: '/sessionserver/session/minecraft/join', note: '客户端加入服务器' },
    { method: 'GET', path: '/sessionserver/session/minecraft/hasJoined', note: '服务端验证玩家' },
    { method: 'GET', path: '/sessionserver/session/minecraft/profile/{uuid}', note: '查询角色材质属性' },
    { method: 'POST', path: '/api/profiles/minecraft', note: '批量查询角色' },
    { method: 'PUT', path: '/api/user/profile/{uuid}/{textureType}', note: '绑定角色材质' },
    { method: 'DELETE', path: '/api/user/profile/{uuid}/{textureType}', note: '清除角色材质' },
  ])

  async function load() {
    loading.value = true
    try {
      status.value = await api.status()
      settings.value = await api.settings()
    }
    finally {
      loading.value = false
    }
  }

  /** 保存自定义展示名；空串表示清除自定义、回到站点默认。 */
  async function saveServerName(value: string) {
    savingSettings.value = true
    try {
      settings.value = await api.saveSettings({ serverName: value.trim() })
      toast.success(value.trim() ? '展示名称已保存' : '已清除自定义，回到站点默认')
    }
    catch (error) {
      toast.error(errorMessage(error, '保存失败'))
    }
    finally {
      savingSettings.value = false
    }
  }

  const signingKey = ref<SigningKeyInfo | null>(null)
  const importingKey = ref(false)

  async function loadSigningKey() {
    try {
      signingKey.value = await api.signingKey()
    }
    catch (error) {
      toast.error(errorMessage(error, '签名密钥加载失败'))
    }
  }

  /** 导入联盟共享材质签名密钥（PEM 对）；导入后本站下发的材质签名改用该密钥。 */
  async function importSigningKey(publicKeyPem: string, privateKeyPem: string) {
    importingKey.value = true
    try {
      signingKey.value = await api.importSigningKey({ publicKeyPem, privateKeyPem })
      toast.success('联盟共享签名密钥已导入')
    }
    catch (error) {
      toast.error(errorMessage(error, '导入失败'))
    }
    finally {
      importingKey.value = false
    }
  }

  async function resetSigningKey() {
    importingKey.value = true
    try {
      signingKey.value = await api.resetSigningKey()
      signingKey.value = null
      toast.success('已重置为站点自生成密钥')
    }
    catch (error) {
      toast.error(errorMessage(error, '重置失败'))
    }
    finally {
      importingKey.value = false
    }
  }

  function errorMessage(error: unknown, fallback: string) {
    return error instanceof Error && error.message ? error.message : fallback
  }

  async function copy(value: string) {
    await navigator.clipboard.writeText(value)
    toast.success('已复制')
  }

  function absoluteUrl(url: string) {
    if (/^https?:\/\//i.test(url)) return url
    return `${window.location.origin}${url.startsWith('/') ? url : `/${url}`}`
  }

  return reactive({ loading, status, settings, savingSettings, launcherUrl, statusText, endpoints, load, saveServerName, copy, signingKey, importingKey, loadSigningKey, importSigningKey, resetSigningKey })
}

export type AuthlibPluginModel = ReturnType<typeof useAuthlibPlugin>
