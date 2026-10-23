<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { AccessControl, SsoSettings } from '../types'
import { YdAlert, YdButton, YdIcon, YdInput, YdLabel, YdLoadingOverlay, YdPageHeader, YdSelect, YdSettingsLayout, YdSettingsSection, YdSwitch, useYdToast } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { createCasApi } from '../api/cas-api'
import { errorMessage } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createCasApi(props.sdk)
const toast = useYdToast()

/** 设置页分区：左导航按此切换，窄屏折叠成分区选择器 */
const activeSection = ref('base')
const sectionList: { icon?: string, label: string, value: string }[] = [
  { label: '基础', value: 'base', icon: 'morph:toggle' },
  { label: '协议与回调', value: 'protocol', icon: 'morph:link' },
  { label: '认证参数', value: 'params', icon: 'morph:settings' },
  { label: '连通性', value: 'test', icon: 'morph:radar' },
  { label: '访问控制', value: 'access', icon: 'morph:shield' },
]

const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const registering = ref(false)
const accessLoading = ref(false)
const accessSaving = ref(false)
const error = ref('')
const testMessage = ref('')
const testOk = ref<boolean | null>(null)
const clientSecret = ref('')

const access = reactive<AccessControl>({
  requireBinding: false,
})

const form = reactive<SsoSettings>({
  enabled: false,
  protocol: 'CAS',
  displayName: '统一身份认证',
  icon: 'i-ri:graduation-cap-line',
  casBaseUrl: '',
  loginPath: '/authserver/login',
  validatePath: '/cas/p3/serviceValidate',
  oidcIssuer: '',
  oidcAuthorizePath: '/authserver/oidc/authorize',
  oidcTokenPath: '/authserver/oidc/accessToken',
  oidcUserinfoPath: '/authserver/oidc/profile',
  oidcJwksPath: '/authserver/oidc/jwks',
  oidcRegisterPath: '/authserver/oidc/register',
  clientId: '',
  clientSecretConfigured: false,
  scopes: 'openid profile email',
  callbackUrl: '',
  loginWarmup: true,
  atrustWakeUrl: '',
  atrustGuideUrl: '',
  ready: false,
})

const protocolOptions = [
  { label: 'SSO 中转（学院统一身份认证中转服务）', value: 'BRIDGE' },
  { label: 'CAS 3.0（认证中心 service ticket）', value: 'CAS' },
  { label: 'OIDC 授权码', value: 'OIDC' },
]

const bridgeMode = computed(() => form.protocol === 'BRIDGE')
const oidcMode = computed(() => form.protocol === 'OIDC')

/**
 * 回调地址填写提示（仅提示，不阻止保存）。
 * 两个真实踩过的坑：填成后端接口 /api/external-login/callback（浏览器只会显示 JSON）；
 * 地址里带 user@（浏览器会把 @ 前当用户名，实际访问 @ 后面的域名，容易报 404 / 跳到不存在的域名）。
 */
const callbackUrlHint = computed(() => {
  const value = form.callbackUrl.trim()
  if (!value) {
    return ''
  }
  if (value.includes('/api/external-login')) {
    return '这里填的是后端接口：浏览器回跳到这里只会看到 JSON，不会完成登录。请改成前端路由 /external-login/callback。'
  }
  let parsed: URL
  try {
    parsed = new URL(value)
  }
  catch {
    return '不是合法 URL，需要形如 https://站点域名/external-login/callback'
  }
  if (parsed.username || parsed.password) {
    return `地址里带了 user@：浏览器会把 @ 前的内容当成用户名，实际访问的是 ${parsed.host}。请只填站点自己的域名。`
  }
  if (!parsed.pathname.endsWith('/external-login/callback')) {
    return '路径应为 /external-login/callback（宿主的第三方登录回调路由）。'
  }
  return ''
})

function apply(data: SsoSettings) {
  Object.assign(form, data)
  clientSecret.value = ''
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    apply(await api.settings())
  }
  catch (caught) {
    error.value = errorMessage(caught, '加载设置失败')
  }
  finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  error.value = ''
  try {
    apply(await api.saveSettings(form, clientSecret.value.trim() || undefined))
    toast.success('设置已保存')
  }
  catch (caught) {
    error.value = errorMessage(caught, '保存失败')
  }
  finally {
    saving.value = false
  }
}

async function test() {
  testing.value = true
  error.value = ''
  testMessage.value = ''
  testOk.value = null
  try {
    const result = await api.test()
    testOk.value = result.ok
    testMessage.value = result.message
    if (result.ok) {
      toast.success('连通性检查通过')
    }
    else {
      toast.error(result.message || '连通性检查未通过')
    }
  }
  catch (caught) {
    error.value = errorMessage(caught, '连通性检查失败')
  }
  finally {
    testing.value = false
  }
}

async function registerOidc() {
  registering.value = true
  error.value = ''
  try {
    const result = await api.registerOidc(form.displayName)
    if (result.clientId) {
      form.clientId = result.clientId
    }
    form.clientSecretConfigured = form.clientSecretConfigured || result.clientSecretIssued
    toast.success(result.clientSecretIssued ? '动态注册成功，客户端密钥已写入密钥库' : '动态注册完成')
    await load()
  }
  catch (caught) {
    error.value = errorMessage(caught, '动态注册失败')
  }
  finally {
    registering.value = false
  }
}

async function loadAccess() {
  accessLoading.value = true
  try {
    Object.assign(access, await api.accessControl())
  }
  catch (caught) {
    toast.error(errorMessage(caught, '加载访问控制配置失败'))
  }
  finally {
    accessLoading.value = false
  }
}

async function saveAccess() {
  accessSaving.value = true
  try {
    Object.assign(access, await api.saveAccessControl({ ...access }))
    toast.success(access.requireBinding ? '已开启：未绑定统一身份认证的成员将无法使用其他功能' : '已关闭绑定门禁')
  }
  catch (caught) {
    toast.error(errorMessage(caught, '保存访问控制配置失败'))
  }
  finally {
    accessSaving.value = false
  }
}

onMounted(() => {
  load()
  loadAccess()
})
</script>

<template>
  <div class="settings-page-shell">
    <YdPageHeader title="统一身份认证" class="mb-0">
      <YdButton variant="outline" :loading="loading" @click="load">
        <YdIcon name="i-ri:refresh-line" />
        刷新
      </YdButton>
      <YdButton :loading="saving" :disabled="loading" @click="save">
        <YdIcon name="i-ri:save-3-line" />
        保存设置
      </YdButton>
    </YdPageHeader>

    <YdLoadingOverlay :loading="loading">
      <YdSettingsLayout
        v-model="activeSection"
        :list="sectionList"
        surface
        scroll
        width-class="max-w-[880px]"
      >
        <div class="cas-stack">
          <YdAlert v-if="error" variant="destructive" title="操作未完成" :description="error" />
          <YdAlert
            v-if="form.ready"
            title="登录入口已就绪"
            description="启用后会在登录页「登录方式」中出现本提供方。未绑定本站账号的用户会被引导登录或注册后再绑定。"
          />
          <YdAlert
            v-else
            title="尚未对登录页开放"
            :description="bridgeMode
              ? 'SSO 中转需要本站回调地址、中转服务地址、clientId 与中转密钥。'
              : oidcMode ? 'OIDC 需要本站回调地址、client_id 与 client_secret。' : 'CAS 需要填写认证服务根地址与本站回调地址，并把该域名登记到学校的 service 白名单。'"
          />

          <YdSettingsSection title="基础">
            <div class="cas-switch-row">
              <YdSwitch v-model="form.enabled" />
              <div class="cas-switch-copy">
                <strong>启用登录入口</strong>
                <small>配置不完整时即使打开也不会出现在登录页。</small>
              </div>
            </div>
            <div class="cas-grid-2">
              <YdLabel label="显示名称" class="cas-field">
                <YdInput v-model="form.displayName" class="w-full" maxlength="40" />
              </YdLabel>
              <YdLabel label="图标" class="cas-field">
                <YdInput v-model="form.icon" class="w-full" maxlength="80" />
                <span class="cas-hint">Remix Icon 类名，例如 i-ri:graduation-cap-line。</span>
              </YdLabel>
            </div>
          </YdSettingsSection>

          <YdSettingsSection title="协议与回调" description="回调地址统一填宿主前端路由 /external-login/callback；换票与验签始终在本站后端完成，密钥不会出现在浏览器。">
            <YdLabel label="认证协议" class="cas-field">
              <YdSelect v-model="form.protocol" :options="protocolOptions" />
            </YdLabel>
            <YdLabel label="认证服务根地址" class="cas-field">
              <YdInput v-model="form.casBaseUrl" class="w-full" maxlength="200" :placeholder="bridgeMode ? 'https://jkxb.swust.edu.cn' : 'https://认证服务域名'" />
              <span v-if="bridgeMode" class="cas-hint">学院 SSO 中转服务地址；换票 /sso/exchange 与探活 /sso/health 都走这个根地址。</span>
            </YdLabel>
            <YdLabel label="本站回调地址" class="cas-field">
              <YdInput v-model="form.callbackUrl" class="w-full" maxlength="400" placeholder="https://你的站点/external-login/callback" />
              <span class="cas-hint">
                必须填**前端**回调路由 <code>https://站点域名/external-login/callback</code>（认证完成后浏览器回跳到这里，页面再调后端完成登录）。
                不要填 <code>/api/external-login/callback</code>：那是后端接口，浏览器直接落上去只会显示一段 JSON。SSO 中转的回调域名与路径还须在中转服务白名单内。
              </span>
              <span v-if="callbackUrlHint" class="cas-hint cas-hint--danger">{{ callbackUrlHint }}</span>
            </YdLabel>
          </YdSettingsSection>

          <YdSettingsSection
            v-if="!oidcMode"
            title="登录预热"
            description="部分网关对首次不带会话 cookie 的请求直接回 404：先落到本站预热页发一次跨站请求把 cookie 种下来，再跳真正的认证地址。"
          >
            <div class="cas-switch-row">
              <YdSwitch v-model="form.loginWarmup" />
              <div class="cas-switch-copy">
                <strong>先经本站预热页再跳转认证</strong>
                <small>点登录后先落到 <code>/api/plugins/cas/public/warmup</code>，再自动跳转认证服务。</small>
              </div>
            </div>
            <p class="cas-hint">
              适用场景：认证地址直接访问报 404、但在地址栏回车再来一次就正常。浏览器若拦截第三方 cookie，预热会自动失效并照常跳转（等同于关闭本开关）。
              该开关不影响 OIDC：OIDC 的 redirect_uri 必须与登记值精确一致，不会改跳本站页面。
            </p>
          </YdSettingsSection>

          <YdSettingsSection v-if="!oidcMode && !bridgeMode" title="CAS 路径">
            <div class="cas-grid-2">
              <YdLabel label="登录路径" class="cas-field">
                <YdInput v-model="form.loginPath" class="w-full" maxlength="120" />
              </YdLabel>
              <YdLabel label="票据校验路径" class="cas-field">
                <YdInput v-model="form.validatePath" class="w-full" maxlength="120" />
              </YdLabel>
            </div>
            <p class="cas-hint">
              CAS 没有独立 state 参数，插件会把宿主签发的 state 编码进 service URL。校验时必须用完全相同的 service 回放。
            </p>
          </YdSettingsSection>

          <YdSettingsSection
            v-if="bridgeMode"
            title="SSO 中转"
            description="学院中转服务登记的 clientId 与密钥。回调携带的一次性 code（60 秒有效）由本站后端以 HMAC-SHA256 签名换取学工号身份，并对响应验签；密钥只写入插件密钥库，不会回显。"
          >
            <div class="cas-grid-2">
              <YdLabel label="clientId" class="cas-field">
                <YdInput v-model="form.clientId" class="w-full" maxlength="120" placeholder="中转服务分配的 clientId" />
              </YdLabel>
              <YdLabel label="中转密钥（secret）" class="cas-field">
                <YdInput v-model="clientSecret" type="password" class="w-full" maxlength="240" :placeholder="form.clientSecretConfigured ? '已保存，留空则不修改' : '写入插件密钥库，不会回显'" />
                <span class="cas-hint">{{ form.clientSecretConfigured ? '密钥库中已有中转密钥。' : '尚未配置中转密钥。' }}</span>
              </YdLabel>
            </div>
            <YdLabel label="aTrust 唤醒链接" class="cas-field">
              <YdInput v-model="form.atrustWakeUrl" class="w-full" maxlength="300" placeholder="如 atrustclient://…（部署方自定义协议，留空不展示引导）" />
              <span class="cas-hint">
                配置后，登录预热页会先显示「aTrust 引导」：校外用户可一键唤起客户端、再手动继续登录；继续一次后本机不再提示。
                各学校 aTrust 的唤起协议不同，请以本校 aTrust 门户/网信中心提供的协议地址为准。
              </span>
            </YdLabel>
            <YdLabel label="aTrust 下载 / 帮助页" class="cas-field">
              <YdInput v-model="form.atrustGuideUrl" class="w-full" maxlength="300" placeholder="https://…（引导页新窗口打开，留空不展示）" />
            </YdLabel>
            <p class="cas-hint">
              code 一次性且 60 秒有效，后端不会重试同一个 code；中转服务对换票接口有限流（30 次/分钟/IP）。
              学院 / 班级为空串属正常情况（该学生不在中转服务档案内），学生信息页按未知展示。
            </p>
          </YdSettingsSection>

          <YdSettingsSection v-if="oidcMode" title="OIDC 客户端">
            <div class="cas-grid-2">
              <YdLabel label="Issuer" class="cas-field">
                <YdInput v-model="form.oidcIssuer" class="w-full" maxlength="240" />
              </YdLabel>
              <YdLabel label="client_id" class="cas-field">
                <YdInput v-model="form.clientId" class="w-full" maxlength="120" />
              </YdLabel>
              <YdLabel label="client_secret" class="cas-field">
                <YdInput v-model="clientSecret" type="password" class="w-full" maxlength="240" :placeholder="form.clientSecretConfigured ? '已保存，留空则不修改' : '写入插件密钥库，不会回显'" />
                <span class="cas-hint">{{ form.clientSecretConfigured ? '密钥库中已有客户端密钥。' : '尚未配置客户端密钥。' }}</span>
              </YdLabel>
              <YdLabel label="scopes" class="cas-field">
                <YdInput v-model="form.scopes" class="w-full" maxlength="120" />
              </YdLabel>
            </div>
            <div class="cas-grid-2">
              <YdLabel label="授权路径" class="cas-field">
                <YdInput v-model="form.oidcAuthorizePath" class="w-full" maxlength="120" />
              </YdLabel>
              <YdLabel label="Token 路径" class="cas-field">
                <YdInput v-model="form.oidcTokenPath" class="w-full" maxlength="120" />
              </YdLabel>
              <YdLabel label="UserInfo 路径" class="cas-field">
                <YdInput v-model="form.oidcUserinfoPath" class="w-full" maxlength="120" />
              </YdLabel>
              <YdLabel label="JWKS 路径" class="cas-field">
                <YdInput v-model="form.oidcJwksPath" class="w-full" maxlength="120" />
              </YdLabel>
              <YdLabel label="动态注册路径" class="cas-field">
                <YdInput v-model="form.oidcRegisterPath" class="w-full" maxlength="120" />
              </YdLabel>
            </div>
            <div class="cas-actions">
              <YdButton type="button" variant="outline" :loading="registering" :disabled="saving" @click="registerOidc">
                向认证中心动态注册客户端
              </YdButton>
              <span class="cas-hint">注册成功后会回写 client_id，并把 client_secret 存入密钥库。是否允许动态注册由学校网信中心决定。</span>
            </div>
          </YdSettingsSection>

          <YdSettingsSection title="连通性">
            <div class="cas-actions">
              <YdButton type="button" variant="outline" :loading="testing" @click="test">
                探测认证端点
              </YdButton>
              <span v-if="testMessage" class="cas-status">
                {{ testOk ? '通过：' : '未通过：' }}{{ testMessage }}
              </span>
            </div>
            <p class="cas-hint">
              探测不会真正登录。CAS 会访问登录页并用无效票据打校验端点；OIDC 会读取 JWKS 并访问授权端点；SSO 中转会访问 /sso/health 并核对 clientId 已登记。
            </p>
          </YdSettingsSection>

          <YdSettingsSection title="访问控制" description="绑定门禁开启后，未绑定统一身份认证的成员会被引导完成绑定。">
            <div class="cas-switch-row">
              <YdSwitch v-model="access.requireBinding" />
              <div class="cas-switch-copy">
                <strong>未绑定则限制使用其他功能</strong>
                <small>仅在前端界面强制；管理员（持有本插件管理权限）始终豁免，登录入口未就绪时自动失效。</small>
              </div>
            </div>
            <p class="cas-hint">
              本插件<strong>不维护认证属性到学生档案的映射</strong>，也不向学生档案插件（<code>yudream-student-info</code>）写入任何信息：
              「学生信息」页只按学号归档认证身份（姓名 / 邮箱 / 电话），学院与班级一律从学生档案插件只读获取。
            </p>
            <div class="cas-actions">
              <YdButton type="button" :loading="accessSaving" :disabled="accessLoading" @click="saveAccess">
                保存访问控制
              </YdButton>
            </div>
          </YdSettingsSection>
        </div>
      </YdSettingsLayout>
    </YdLoadingOverlay>
  </div>
</template>

<style scoped>
.cas-stack {
  display: grid;
  gap: 14px;
}

.cas-grid-2 {
  display: grid;
  gap: 14px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

@media (max-width: 768px) {
  .cas-grid-2 {
    grid-template-columns: 1fr;
  }
}

.cas-field {
  display: grid;
  gap: 7px;
  min-width: 0;
}

.cas-hint {
  color: var(--color-text-3);
  font-size: 12px;
  line-height: 1.5;
}

.cas-hint--danger {
  color: var(--color-danger, #f53f3f);
}

.cas-switch-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.cas-switch-copy {
  display: grid;
  gap: 2px;
}

.cas-switch-copy small {
  color: var(--color-text-3);
  font-size: 12px;
  line-height: 1.5;
}

.cas-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.cas-status {
  color: var(--color-text-2);
  font-size: 12px;
}
</style>
