<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { YdPageHeader, YdPageMain, YdSettingsSection, YdSwitch, YdAlert, YdButton, YdCopyButton, YdForm, YdFormItem, YdInput, YdLoadingOverlay, useYdModal, useYdToast } from '@yudream/components'
import type { BridgeSettings, ModelSettings } from '../types'
import { adminApi } from '../api/admin'
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = adminApi(props.sdk), toast = useYdToast(), modal = useYdModal()
const settings = ref<ModelSettings>(), loading = ref(false), saving = ref(false), error = ref('')
const savedBridge = ref<BridgeSettings>(), bridgeSaving = ref(false), bridgeError = ref(''), tokenInputKey = ref(0)
const bridge = reactive<BridgeSettings>({ publicBaseUrl: '', bridgeToken: '' })
const fields = reactive({ publicBaseUrl: '', bridgeToken: '' })
const bridgeDirty = computed(() => !!savedBridge.value && (bridge.publicBaseUrl !== savedBridge.value.publicBaseUrl || bridge.bridgeToken !== savedBridge.value.bridgeToken))
const configured = computed(() => !!savedBridge.value?.publicBaseUrl && (savedBridge.value?.bridgeToken.length ?? 0) >= 32)
const serverConfig = computed(() => savedBridge.value ? `website-url: ${JSON.stringify(savedBridge.value.publicBaseUrl)}\nbridge-token: ${JSON.stringify(savedBridge.value.bridgeToken)}\n` : '')
async function load() {
  loading.value = true; error.value = ''; bridgeError.value = ''
  try {
    const [policy, connection] = await Promise.all([api.settings(), api.bridgeSettings()])
    settings.value = policy; savedBridge.value = connection; Object.assign(bridge, connection)
    fields.publicBaseUrl = ''; fields.bridgeToken = ''; tokenInputKey.value++
  }
  catch (e) { error.value = e instanceof Error ? e.message : '设置加载失败' }
  finally { loading.value = false }
}
function generateToken() {
  try {
    bridge.bridgeToken = Array.from(crypto.getRandomValues(new Uint8Array(32)), byte => byte.toString(16).padStart(2, '0')).join('')
    fields.bridgeToken = ''; bridgeError.value = ''; tokenInputKey.value++
    toast.success('已生成随机密钥，保存连接配置后生效')
  } catch { toast.error('浏览器无法生成随机密钥，请换用支持随机数生成的浏览器') }
}
function validateBridge(): BridgeSettings | undefined {
  const data = { publicBaseUrl: bridge.publicBaseUrl.trim().replace(/\/+$/, ''), bridgeToken: bridge.bridgeToken }
  fields.publicBaseUrl = ''; fields.bridgeToken = ''; bridgeError.value = ''
  try {
    const url = new URL(data.publicBaseUrl)
    if (!['http:', 'https:'].includes(url.protocol) || !url.hostname || url.username || url.password || url.search || url.hash || data.publicBaseUrl.length > 1900) throw new Error()
  } catch { fields.publicBaseUrl = '请输入网站的 HTTP(S) 根地址，不要包含账号、查询参数或片段' }
  if (!/^[\x21-\x7e]{32,256}$/.test(data.bridgeToken)) fields.bridgeToken = '密钥需要 32–256 个可打印英文字符，不能包含空格'
  return fields.publicBaseUrl || fields.bridgeToken ? undefined : data
}
async function persistBridge(data: BridgeSettings) {
  if (bridgeSaving.value) return false
  bridgeSaving.value = true; bridgeError.value = ''
  try {
    const result = await api.saveBridgeSettings(data)
    savedBridge.value = result; Object.assign(bridge, result); tokenInputKey.value++
    toast.success('连接配置已生效，请同步游戏服配置并重启游戏服')
    return true
  } catch (e) { bridgeError.value = e instanceof Error ? e.message : '连接配置保存失败'; toast.error(bridgeError.value); return false }
  finally { bridgeSaving.value = false }
}
function saveBridge() {
  if (bridgeSaving.value || loading.value) return
  const data = validateBridge()
  if (!data) return
  if (savedBridge.value?.bridgeToken && data.bridgeToken !== savedBridge.value.bridgeToken) {
    const dialog = modal.confirm({
      title: '更换桥接密钥',
      content: '保存后旧密钥和已签发的下载链接将失效。请将新密钥同步到游戏服配置并重启游戏服，再继续使用模型。',
      confirmButtonText: '更换并保存',
      beforeClose: (action, done) => {
        if (bridgeSaving.value) return
        if (action !== 'confirm') { done(); return }
        dialog.update({ confirmButtonLoading: true, closeOnPressEscape: false })
        void persistBridge(data).then(saved => { if (saved) done() }).finally(() => dialog.update({ confirmButtonLoading: false, closeOnPressEscape: true }))
      },
    })
  } else void persistBridge(data)
}
async function update(key: keyof ModelSettings, value: boolean) {
  if (!settings.value || saving.value) return
  saving.value = true; error.value = ''
  try { settings.value = await api.saveSettings({ ...settings.value, [key]: value }); toast.success('设置已生效') }
  catch (e) { error.value = e instanceof Error ? e.message : '设置保存失败' }
  finally { saving.value = false }
}
onMounted(load)
</script>
<template>
  <section class="ysm-settings">
    <YdPageHeader title="模型设置" description="配置服务器连接、模型上传与审核。上传与审核开关自动保存。">
      <YdButton :loading="bridgeSaving" :disabled="loading || !bridgeDirty" @click="saveBridge">保存连接配置</YdButton>
    </YdPageHeader>
    <YdPageMain>
      <YdLoadingOverlay :loading="loading">
        <div class="ysm-settings-content">
          <YdAlert v-if="error" type="error">{{ error }}<YdButton v-if="!settings" variant="outline" size="sm" @click="load">重新加载</YdButton></YdAlert>
          <YdSettingsSection v-if="savedBridge" title="服务器连接" description="在这里生成和管理网站与游戏服务器共用的桥接密钥，保存后网站端立即生效。">
            <YdAlert v-if="bridgeError" type="error">{{ bridgeError }}</YdAlert>
            <YdForm :model="bridge" layout="vertical" :disabled="bridgeSaving || loading">
              <YdFormItem field="publicBaseUrl" label="网站公网地址" required :validate-status="fields.publicBaseUrl ? 'error' : undefined" :help="fields.publicBaseUrl || undefined" extra="填写玩家和游戏服务器均可访问的网站根地址。">
                <YdInput v-model="bridge.publicBaseUrl" aria-label="网站公网地址" type="url" placeholder="https://skin.example.com" :maxlength="1900" :disabled="bridgeSaving || loading" autocomplete="url" @update:model-value="fields.publicBaseUrl = ''" />
              </YdFormItem>
              <YdFormItem field="bridgeToken" label="桥接密钥" required :validate-status="fields.bridgeToken ? 'error' : undefined" :help="fields.bridgeToken || undefined" extra="与游戏服 config.yml 的 bridge-token 保持一致；仅管理员可查看和管理。">
                <div class="ysm-secret-field">
                  <YdInput :key="tokenInputKey" v-model="bridge.bridgeToken" aria-label="桥接密钥" type="password" placeholder="点击生成随机密钥，或填写已有密钥" :maxlength="256" :disabled="bridgeSaving || loading" autocomplete="off" :spellcheck="false" @update:model-value="fields.bridgeToken = ''" />
                  <div class="ysm-actions">
                    <YdButton variant="outline" size="sm" :disabled="bridgeSaving || loading" @click="generateToken">生成随机密钥</YdButton>
                    <YdCopyButton :text="bridge.bridgeToken" label="复制密钥" :disabled="!bridge.bridgeToken || bridgeSaving || loading" @copied="toast.success('密钥已复制')" @error="toast.error('复制失败，请显示密钥后手动复制')" />
                  </div>
                </div>
              </YdFormItem>
            </YdForm>
            <YdAlert v-if="bridgeDirty" type="info">当前连接配置尚未保存。点击页面上方“保存连接配置”后，网站端才会使用新配置。</YdAlert>
            <div class="ysm-bridge-install">
              <div><strong>配置游戏服务器</strong><p>保存后复制配置，填入游戏服 <code>plugins/YudreamModels/config.yml</code>，然后重启游戏服。玩家客户端无需填写密钥。</p></div>
              <YdCopyButton :text="serverConfig" label="复制游戏服配置" :disabled="!configured || bridgeDirty || bridgeSaving || loading" @copied="toast.success('游戏服配置已复制')" @error="toast.error('复制失败，请手动填写网站地址和密钥')" />
            </div>
          </YdSettingsSection>
          <YdSettingsSection v-if="settings" title="上传与审核" description="策略同时适用于原始 .ysm 和明文 ZIP。">
            <div class="ysm-setting-row">
              <div><strong>允许上传模型</strong><p>关闭后，仅持有受限上传权限的用户仍可上传新模型或新版本，已有可用模型仍可装备。</p><p>受限上传权限：<code>plugin:ysm-models:upload</code>。请在角色权限中授予需要继续上传的管理员或用户，超管 <code>*</code> 自动包含此权限。</p></div>
              <YdSwitch :model-value="settings.uploadEnabled" :disabled="saving || loading" aria-label="允许上传模型" @update:model-value="update('uploadEnabled', !!$event)" />
            </div>
            <div class="ysm-setting-row">
              <div><strong>上传后需要审核</strong><p>开启后，新模型和新版本须经管理员审核通过，才能装备或加入服务器模型库。</p></div>
              <YdSwitch :model-value="settings.reviewRequired" :disabled="saving || loading" aria-label="上传后需要审核" @update:model-value="update('reviewRequired', !!$event)" />
            </div>
            <YdAlert v-if="!settings.uploadEnabled" type="warning">普通上传已关闭，持有受限上传权限的账号仍可提交，并遵守当前审核策略。</YdAlert>
            <p class="ysm-settings-note">开启审核不会追溯审核已有模型；关闭审核也不会自动通过待审核或已驳回的模型。请在「模型管理」中处理待审核提交。已装备模型上传新版本后，审核期间角色将暂时恢复皮肤外观。</p>
          </YdSettingsSection>
        </div>
      </YdLoadingOverlay>
    </YdPageMain>
  </section>
</template>
