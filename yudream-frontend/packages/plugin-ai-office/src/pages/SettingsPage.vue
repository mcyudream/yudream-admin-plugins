<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { AiProviderOption, OfficeSchema, OfficeSettings, PresetInfo } from '../types'
import { YdButton, YdIcon, YdInput, YdPageHeader, YdPageMain, YdSelect, YdSettingsLayout, YdSettingsSection, YdSwitch, YdTextarea, useYdModal, useYdToast } from '@yudream/components'
import { computed, onMounted, ref, watch } from 'vue'
import { createOfficeAdminApi, errorMessage, saveBlob } from '../api/office-api'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const toast = useYdToast()
const confirm = useYdModal()
const api = createOfficeAdminApi(props.sdk)

const activeSection = ref('profile')
const sectionList = [
  { label: '组织档案', value: 'profile' },
  { label: '写作与 AI', value: 'ai' },
  { label: '预设包', value: 'preset' },
]

const settings = ref<OfficeSettings | null>(null)
const schemas = ref<OfficeSchema[]>([])
const providers = ref<AiProviderOption[]>([])
const presets = ref<PresetInfo[]>([])
const loading = ref(false)
const saving = ref(false)
const overwrite = ref(false)
const importing = ref(false)

const profileFields = computed(() =>
  schemas.value.find(schema => schema.code === settings.value?.profileSchemaCode)?.fields ?? [])

const providerOptions = computed(() => [
  { label: '（默认）', value: '' },
  ...providers.value.map(provider => ({ label: provider.name, value: provider.code })),
])

const modelOptions = computed(() => {
  const provider = providers.value.find(item => item.code === settings.value?.aiProviderCode)
  return [
    { label: '（默认）', value: '' },
    ...(provider?.models ?? []).map(model => ({ label: model.name, value: model.code })),
  ]
})

watch(() => settings.value?.profileSchemaCode, async (code) => {
  // 切换档案 schema 后字段面变化，档案值仍保留在 settings.profile 中
  if (code && !schemas.value.some(schema => schema.code === code)) {
    await load()
  }
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    const [settingsData, schemaData, providerData, presetData] = await Promise.all([
      api.settings(),
      api.schemas(),
      api.aiProviders().catch(() => [] as AiProviderOption[]),
      api.presets().catch(() => [] as PresetInfo[]),
    ])
    settings.value = settingsData
    schemas.value = schemaData
    providers.value = providerData
    presets.value = presetData
  }
  catch (cause) {
    toast.error(errorMessage(cause, '设置加载失败'))
  }
  finally {
    loading.value = false
  }
}

async function save() {
  if (!settings.value) {
    return
  }
  saving.value = true
  try {
    settings.value = await api.saveSettings({
      profileSchemaCode: settings.value.profileSchemaCode,
      profile: settings.value.profile,
      financeCycle: settings.value.financeCycle,
      currency: settings.value.currency,
      styleGuide: settings.value.styleGuide,
      aiProviderCode: settings.value.aiProviderCode,
      aiModelCode: settings.value.aiModelCode,
    })
    toast.success('设置已保存')
  }
  catch (cause) {
    toast.error(errorMessage(cause, '保存失败'))
  }
  finally {
    saving.value = false
  }
}

async function importPreset(preset: PresetInfo) {
  importing.value = true
  try {
    const result = await api.importPreset(preset.code, overwrite.value)
    toast.success(`导入完成：新增 ${result.created} 项，跳过 ${result.skipped} 项`)
    await load()
  }
  catch (cause) {
    toast.error(errorMessage(cause, '导入失败'))
  }
  finally {
    importing.value = false
  }
}

async function onPresetFileChosen(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) {
    return
  }
  importing.value = true
  try {
    const result = await api.uploadPreset(file, overwrite.value)
    toast.success(`导入完成：新增 ${result.created} 项，跳过 ${result.skipped} 项`)
    await load()
  }
  catch (cause) {
    toast.error(errorMessage(cause, '导入失败'))
  }
  finally {
    importing.value = false
  }
}

async function exportPreset() {
  try {
    const blob = await api.exportPreset()
    saveBlob(blob.data, 'ai-office-preset.json')
  }
  catch (cause) {
    toast.error(errorMessage(cause, '导出失败'))
  }
}

function resetPreset() {
  confirm.confirm({
    title: '覆盖导入',
    content: '覆盖导入会用预设中的同编码配置覆盖站点定制，确定继续吗？',
    onConfirm: () => {
      overwrite.value = true
    },
  })
}
</script>

<template>
  <section class="office-page">
    <YdPageHeader title="办公设置">
      <YdButton :loading="saving" @click="save">
        <YdIcon name="i-ri:save-line" />保存配置
      </YdButton>
    </YdPageHeader>
    <YdPageMain>
      <YdSettingsLayout v-model="activeSection" :list="sectionList" surface scroll width-class="max-w-[880px]">
        <template #profile>
          <YdSettingsSection title="组织档案" description="组织名称、挂靠单位等；字段面由档案数据模型决定，映射用 org:字段key 引用。">
            <form v-if="settings" class="grid max-w-3xl gap-4" @submit.prevent="save">
              <label class="flex flex-col gap-1 text-sm">
                <span class="text-secondary-foreground/80">档案数据模型</span>
                <YdSelect
                  v-model="settings.profileSchemaCode"
                  :options="schemas.map(schema => ({ label: `${schema.name}（${schema.code}）`, value: schema.code }))"
                />
              </label>
              <label v-for="field in profileFields" :key="field.key" class="flex flex-col gap-1 text-sm">
                <span class="text-secondary-foreground/80">{{ field.label }}（org:{{ field.key }}）</span>
                <YdTextarea v-if="field.type === 'longtext'" v-model="settings!.profile[field.key]" :rows="2" />
                <YdInput v-else v-model="settings!.profile[field.key]" />
              </label>
              <p v-if="!profileFields.length" class="text-sm text-muted-foreground">
                请先在「数据模型」中配置档案结构，或导入预设包。
              </p>
            </form>
          </YdSettingsSection>
        </template>
        <template #ai>
          <YdSettingsSection title="写作与 AI" description="AI 提供商由平台能力配置；这里选择本插件使用的提供商与模型，并维护站点写作规范（自动并入提示词）。">
            <form v-if="settings" class="grid max-w-3xl gap-4" @submit.prevent="save">
              <div class="grid grid-cols-2 gap-4">
                <label class="flex flex-col gap-1 text-sm">
                  <span class="text-secondary-foreground/80">AI 提供商</span>
                  <YdSelect v-model="settings.aiProviderCode" :options="providerOptions" />
                </label>
                <label class="flex flex-col gap-1 text-sm">
                  <span class="text-secondary-foreground/80">模型</span>
                  <YdSelect v-model="settings.aiModelCode" :options="modelOptions" />
                </label>
              </div>
              <label class="flex flex-col gap-1 text-sm">
                <span class="text-secondary-foreground/80">周期粒度</span>
                <YdSelect
                  v-model="settings.financeCycle"
                  :options="[{ label: '按月', value: 'MONTHLY' }, { label: '按季（预留）', value: 'QUARTERLY' }]"
                />
              </label>
              <label class="flex flex-col gap-1 text-sm">
                <span class="text-secondary-foreground/80">站点写作规范（注入所有 AI 提示词）</span>
                <YdTextarea v-model="settings.styleGuide" :rows="5" placeholder="正式公文风；章节用汉字序号；落款用全称…" />
              </label>
              <p v-if="!providers.length" class="text-sm text-muted-foreground">
                未读取到 AI 提供商：请确认平台「AI」能力已启用并配置了提供商。
              </p>
            </form>
          </YdSettingsSection>
        </template>
        <template #preset>
          <YdSettingsSection title="预设包" description="导入即复制为站点数据，之后可自由修改，插件升级不覆盖；也可导出当前配置在站点间复制成例。">
            <div class="flex max-w-3xl flex-col gap-4">
              <div class="flex items-center gap-2 text-sm">
                <span>覆盖同编码配置</span>
                <YdSwitch v-model="overwrite" size="sm" @update:model-value="value => value && resetPreset()" />
              </div>
              <div v-for="preset in presets" :key="preset.code" class="rounded-lg border p-3">
                <div class="flex flex-wrap items-center justify-between gap-2">
                  <div>
                    <strong>{{ preset.name }}</strong>
                    <p class="text-sm text-muted-foreground">{{ preset.description }}</p>
                    <p class="mt-1 text-xs text-muted-foreground">
                      需在平台管理端上传的 Word 模板：{{ (preset.wordTemplates || []).map(item => item.code).join('、') }}
                    </p>
                  </div>
                  <YdButton size="sm" :loading="importing" @click="importPreset(preset)">导入</YdButton>
                </div>
              </div>
              <div class="flex items-center gap-2">
                <label class="text-sm">
                  <input type="file" accept=".json" @change="onPresetFileChosen">
                  上传自定义预设 JSON
                </label>
                <YdButton size="sm" variant="outline" @click="exportPreset">
                  <YdIcon name="i-ri:download-line" />导出当前配置
                </YdButton>
              </div>
            </div>
          </YdSettingsSection>
        </template>
      </YdSettingsLayout>
    </YdPageMain>
  </section>
</template>
