<script setup lang="ts">
import { YdLoadingOverlay, YdSettingsLayout, YdSettingsSection } from '@yudream/components'
import type { YuDreamPluginAiProviderOption, YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { YdButton, YdIcon, YdNumberField, YdPageHeader, YdSelect, YdSwitch, useYdToast } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { createForumApi } from '../api/forum-api'
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createForumApi(props.sdk)
const toast = useYdToast()
const saving = ref(false)
const loading = ref(false)
const aiProviders = ref<YuDreamPluginAiProviderOption[]>([])
const aiUnavailable = ref(false)
const form = reactive({ moderation: 'manual', aiTagging: false, aiProviderCode: '', aiModelCode: '', aiTimeoutSeconds: 20 })
const providerOptions = computed(() => [{ label: '使用宿主默认提供方', value: '' }, ...aiProviders.value.map(item => ({ label: `${item.name}（${item.code}）`, value: item.code }))])
const selectedProvider = computed(() => aiProviders.value.find(item => item.code === form.aiProviderCode))
const modelOptions = computed(() => {
  const current = selectedProvider.value
  const options = current ? (current.models ?? []).map(item => ({ label: `${item.name}（${item.code}）`, value: item.code })) : []
  if (form.aiModelCode && !options.some(item => item.value === form.aiModelCode)) options.unshift({ label: `${form.aiModelCode}（平台已下线）`, value: form.aiModelCode })
  return [{ label: '使用宿主默认模型', value: '' }, ...options]
})
function onProviderChange(value?: unknown) { form.aiProviderCode = typeof value === 'string' ? value : ''; if (form.aiModelCode && selectedProvider.value && !(selectedProvider.value.models ?? []).some(item => item.code === form.aiModelCode)) form.aiModelCode = '' }
async function load() {
  loading.value = true
  try {
    const [settings, providers] = await Promise.all([api.settings(), props.sdk.ai.providers().catch(() => { aiUnavailable.value = true; return [] as YuDreamPluginAiProviderOption[] })])
    Object.assign(form, settings)
    aiProviders.value = providers
  } catch (error) { toast.error(error instanceof Error ? error.message : '加载设置失败') } finally { loading.value = false }
}
async function save() { saving.value = true; try { await api.saveSettings({ ...form }); toast.success('论坛设置已保存') } catch (error) { toast.error(error instanceof Error ? error.message : '保存失败') } finally { saving.value = false } }
onMounted(load)
/** 设置页分区：单分区页保持统一骨架 */
const activeSection = ref('main')
const sectionList: { icon?: string, label: string, value: string }[] = [
  { label: "全局策略", value: 'main' },
]
</script>
<template>
  <div class="settings-page-shell">
    <YdPageHeader title="论坛设置" class="mb-0"><YdButton :loading="saving" @click="save"><YdIcon name="i-ri:save-3-line" />保存设置</YdButton></YdPageHeader>

  <YdSettingsLayout
    v-model="activeSection"
    :list="sectionList"
    surface
    scroll
    width-class="max-w-[880px]"
  >
    <template #main>
      <YdSettingsSection
        title="全局策略"
        description="审核策略与 AI 标签配置；分类可覆盖全局审核策略。"
      >
<YdLoadingOverlay :loading="loading"><form  class="forum-editor forum-settings-grid" @submit.prevent="save"><label>全局审核策略<YdSelect v-model="form.moderation" class="forum-select" :options="[{label:'关闭审核，直接发布',value:'off'},{label:'人工审核',value:'manual'},{label:'AI 审核',value:'ai'}]" /></label><label class="forum-switch"><YdSwitch v-model="form.aiTagging" />启用 AI 自动打标签</label><label>AI 提供方<YdSelect :model-value="form.aiProviderCode" class="forum-select" :options="providerOptions" @update:model-value="onProviderChange" /></label><label>AI 模型<YdSelect v-model="form.aiModelCode" class="forum-select" :options="modelOptions" :disabled="!form.aiProviderCode && !modelOptions.length" /></label><label>AI 超时（秒）<YdNumberField v-model="form.aiTimeoutSeconds" :min="5" :max="120" /></label><p v-if="aiUnavailable" class="forum-form-warning"><YdIcon name="i-ri:error-warning-line" />宿主 AI 选项暂时不可用，已保留当前配置；请检查宿主 AI 能力后重试。</p><p class="forum-form-hint">提供方和模型来自宿主 AI 配置，不需要手工填写编码。留空表示使用宿主默认值。分类可以覆盖全局审核策略；AI 失败时自动回退人工审核。</p></form></YdLoadingOverlay>
      </YdSettingsSection>
    </template>
  </YdSettingsLayout>
</div>
</template>
