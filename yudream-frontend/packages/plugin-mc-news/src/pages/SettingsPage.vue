<script setup lang="ts">
import type { YuDreamPluginAiProviderOption, YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { McNewsSettingsView, PollStatusView, TemplateMeta, TemplateVariable } from '../types'
import { MdPreview } from 'md-editor-v3'
import { YdButton, YdCombobox, YdForm, YdFormItem, YdIcon, YdLoadingOverlay, YdNumberField, YdPageHeader, YdSettingsLayout, YdSettingsSection, YdSwitch, YdTag, YdTextarea, YdAction, useYdToast } from '@yudream/components'
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { createMcNewsApi } from '../api/mc-news-api'
import { errorMessage, nextPollText } from '../composables/utils'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createMcNewsApi(props.sdk)
const toast = useYdToast()

const loading = ref(false)
const saving = ref(false)
const meta = ref<TemplateMeta | null>(null)
const preview = ref('')
const aiProviders = ref<YuDreamPluginAiProviderOption[]>([])
const pollStatus = ref<PollStatusView | null>(null)
const remainSeconds = ref(0)
let statusTimer: number | undefined

/** 设置页分区：左导航按此切换，窄屏折叠成分区选择器 */
const activeSection = ref('polling')
const sectionList: { icon?: string, label: string, value: string }[] = [
  { label: '轮询', value: 'polling', icon: 'morph:refresh' },
  { label: 'AI 整合', value: 'ai', icon: 'morph:sparkles' },
  { label: '消息模板', value: 'template', icon: 'morph:message' },
]

const form = reactive<McNewsSettingsView>({
  enabled: true,
  pollIntervalMinutes: 30,
  cacheSize: 50,
  pushOnFirstPoll: false,
  aiEnabled: true,
  aiProviderCode: '',
  aiModelCode: '',
  aiMaxItems: 10,
  aiContentMaxChars: 6000,
  aiSystemPrompt: '',
  messageTemplate: '',
  lastPollAt: 0,
  lastPollAtLabel: '',
  lastPollSummary: '',
  defaultTemplate: '',
  defaultAiPrompt: '',
  polling: false,
})

function variableList(): TemplateVariable[] {
  return meta.value?.variables ?? []
}

const providerOptions = computed(() =>
  aiProviders.value.map(provider => ({ label: provider.name, value: provider.code })))

function modelLabel(provider: YuDreamPluginAiProviderOption, code: string, name: string): string {
  return name === code ? code : `${name}（${code}）`
}

/** 已选供应商时只列该供应商的模型；未选时列全部（名称带供应商前缀）。SDK 未返回模型列表时留空，可手输。 */
const modelOptions = computed(() => {
  if (!aiProviders.value.length)
    return []
  const selected = aiProviders.value.find(provider => provider.code === form.aiProviderCode)
  if (selected) {
    return (selected.models ?? []).map(model => ({ label: modelLabel(selected, model.code, model.name), value: model.code }))
  }
  return aiProviders.value.flatMap(provider =>
    (provider.models ?? []).map(model => ({ label: `${provider.name} / ${modelLabel(provider, model.code, model.name)}`, value: model.code })))
})

/** 切换供应商后，模型不属于新供应商时清空，避免提交无效组合。 */
function onProviderChange(code?: string) {
  form.aiProviderCode = code ?? ''
  if (!form.aiProviderCode)
    return
  const provider = aiProviders.value.find(item => item.code === form.aiProviderCode)
  if (provider && form.aiModelCode && !(provider.models ?? []).some(model => model.code === form.aiModelCode)) {
    form.aiModelCode = ''
  }
}

/** 与内置默认完全一致视为"未自定义"，保存空串让后端继续跟随内置默认。 */
function normalizeForSave(value: string, defaultValue: string | undefined): string {
  const trimmed = value.trim()
  const fallback = (defaultValue ?? '').trim()
  if (!trimmed || trimmed === fallback) {
    return ''
  }
  return value
}

async function load() {
  loading.value = true
  try {
    const [settings, templateMeta, providers, status] = await Promise.all([
      api.settings(),
      api.templateVariables(),
      // SDK 直接暴露宿主 AI 供应商与模型选项；AI 能力不可用时降级为空（仍可手输）
      props.sdk.ai.providers().catch(() => [] as YuDreamPluginAiProviderOption[]),
      api.pollStatus().catch(() => null),
    ])
    Object.assign(form, settings)
    // 未自定义时直接把内置默认填入输入框，所见即生效文案
    if (!form.messageTemplate.trim()) {
      form.messageTemplate = settings.defaultTemplate || templateMeta.defaultTemplate || ''
    }
    if (!form.aiSystemPrompt.trim()) {
      form.aiSystemPrompt = settings.defaultAiPrompt || templateMeta.defaultAiPrompt || ''
    }
    meta.value = templateMeta
    preview.value = templateMeta.preview
    aiProviders.value = providers
    pollStatus.value = status
    remainSeconds.value = status && status.nextPollInSeconds > 0 ? status.nextPollInSeconds : 0
  }
  catch (error) {
    toast.error(errorMessage(error, '加载设置失败'))
  }
  finally {
    loading.value = false
  }
}

/** 每 30 秒本地递减倒计时；到点后重取状态。 */
function startStatusTimer() {
  statusTimer = window.setInterval(() => {
    if (remainSeconds.value > 0) {
      remainSeconds.value = Math.max(0, remainSeconds.value - 30)
      return
    }
    api.pollStatus().then((status) => {
      pollStatus.value = status
      remainSeconds.value = status.nextPollInSeconds > 0 ? status.nextPollInSeconds : 0
    }).catch(() => {})
  }, 30000)
}

async function save() {
  saving.value = true
  try {
    const saved = await api.saveSettings({
      enabled: form.enabled,
      pollIntervalMinutes: form.pollIntervalMinutes,
      cacheSize: form.cacheSize,
      pushOnFirstPoll: form.pushOnFirstPoll,
      aiEnabled: form.aiEnabled,
      aiProviderCode: form.aiProviderCode,
      aiModelCode: form.aiModelCode,
      aiMaxItems: form.aiMaxItems,
      aiContentMaxChars: form.aiContentMaxChars,
      aiSystemPrompt: normalizeForSave(form.aiSystemPrompt, meta.value?.defaultAiPrompt),
      messageTemplate: normalizeForSave(form.messageTemplate, meta.value?.defaultTemplate),
    })
    Object.assign(form, saved)
    if (!form.messageTemplate.trim()) {
      form.messageTemplate = saved.defaultTemplate || meta.value?.defaultTemplate || ''
    }
    if (!form.aiSystemPrompt.trim()) {
      form.aiSystemPrompt = saved.defaultAiPrompt || meta.value?.defaultAiPrompt || ''
    }
    toast.success('设置已保存')
  }
  catch (error) {
    toast.error(errorMessage(error, '保存设置失败'))
  }
  finally {
    saving.value = false
  }
}

async function refreshPreview() {
  try {
    const result = await api.previewTemplate(form.messageTemplate)
    preview.value = result.preview
  }
  catch (error) {
    toast.error(errorMessage(error, '预览失败'))
  }
}

function resetTemplate() {
  form.messageTemplate = meta.value?.defaultTemplate || ''
  void refreshPreview()
}

function resetPrompt() {
  form.aiSystemPrompt = meta.value?.defaultAiPrompt || ''
}

function insertVariable(name: string) {
  form.messageTemplate = `${form.messageTemplate}{{${name}}}`
}

// 模板编辑后自动刷新 Markdown 预览（防抖）
let previewTimer: number | undefined
watch(() => form.messageTemplate, () => {
  if (previewTimer !== undefined) {
    window.clearTimeout(previewTimer)
  }
  previewTimer = window.setTimeout(() => void refreshPreview(), 800)
})

onMounted(() => {
  void load()
  startStatusTimer()
})

onUnmounted(() => {
  if (statusTimer !== undefined) {
    window.clearInterval(statusTimer)
  }
})
</script>

<template>
  <div class="settings-page-shell">
    <YdPageHeader title="推送设置" class="mb-0">
      <YdButton :loading="saving" @click="save">
        <YdIcon name="morph:save" />保存设置
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
        <template #polling>
          <YdSettingsSection
            title="轮询"
            description="定时抓取 Minecraft 官网新闻与反馈隧道文章；关闭后仍可手动轮询。"
          >
            <div class="mc-news-poll-meta">
              <span>上次轮询：{{ pollStatus?.lastPollAtLabel || '尚未轮询' }}</span>
              <span v-if="pollStatus?.lastPollSummary">{{ pollStatus.lastPollSummary }}</span>
              <span>下次轮询：{{ nextPollText(pollStatus, remainSeconds) || '加载中' }}</span>
              <YdTag v-if="pollStatus?.polling" variant="default">轮询进行中</YdTag>
              <YdTag v-else-if="pollStatus && !pollStatus.enabled" variant="outline">已暂停</YdTag>
            </div>

            <YdForm :model="form" layout="vertical">
              <div class="flex flex-col gap-4 max-w-xl">
                <YdFormItem label="定时轮询" extra="关闭后暂停自动轮询，手动轮询仍可用。">
                  <YdSwitch v-model="form.enabled" />
                </YdFormItem>
                <YdFormItem label="轮询间隔（分钟）" extra="最小 5 分钟，修改后下一轮生效。">
                  <YdNumberField v-model="form.pollIntervalMinutes" :min="5" :max="1440" />
                </YdFormItem>
                <YdFormItem label="新闻缓存条数" extra="缓存最近 N 条已见新闻用于对比，超出自动淘汰。">
                  <YdNumberField v-model="form.cacheSize" :min="5" :max="200" />
                </YdFormItem>
                <YdFormItem label="首次运行即推送" extra="默认首次运行只建立缓存基线不推送，避免刚启用即轰炸群聊。">
                  <YdSwitch v-model="form.pushOnFirstPoll" />
                </YdFormItem>
              </div>
            </YdForm>
          </YdSettingsSection>
        </template>

        <template #ai>
          <YdSettingsSection
            title="AI 整合"
            description="抓取文章正文，让推送保留修复项、变更点等具体信息；AI 不可用时回退官方摘要。"
          >
            <YdForm :model="form" layout="vertical">
              <div class="flex flex-col gap-4 max-w-xl">
                <YdFormItem label="AI 整合">
                  <YdSwitch v-model="form.aiEnabled" />
                </YdFormItem>
                <YdFormItem label="AI 供应商" extra="选项来自宿主 AI 配置，也可手动输入编码。">
                  <YdCombobox
                    :model-value="form.aiProviderCode || undefined"
                    :options="providerOptions"
                    placeholder="留空走宿主默认"
                    clearable
                    allow-create
                    @change="value => onProviderChange(typeof value === 'string' ? value : '')"
                    @create="onProviderChange"
                  />
                </YdFormItem>
                <YdFormItem label="AI 模型" extra="按所选供应商联动；未选供应商时列出全部模型。">
                  <YdCombobox
                    :model-value="form.aiModelCode || undefined"
                    :options="modelOptions"
                    placeholder="留空走宿主默认"
                    clearable
                    allow-create
                    @change="value => (form.aiModelCode = String(value ?? ''))"
                    @create="value => (form.aiModelCode = value)"
                  />
                </YdFormItem>
                <YdFormItem label="单轮 AI 整合条数上限" extra="超出部分直接使用官方摘要，控制 AI 消耗。">
                  <YdNumberField v-model="form.aiMaxItems" :min="0" :max="50" />
                </YdFormItem>
                <YdFormItem label="正文截取长度（字符）" extra="AI 整合时抓取的文章正文上限，越长保留的细节越多，默认 6000。">
                  <YdNumberField v-model="form.aiContentMaxChars" :min="500" :max="20000" />
                </YdFormItem>
                <YdFormItem label="AI 系统提示词" extra="当前显示内置默认提示词，可直接修改；与默认完全一致时保存后仍跟随内置默认。">
                  <div class="flex flex-col gap-2">
                    <YdButton size="sm" variant="outline" class="self-start" @click="resetPrompt">
                      恢复默认
                    </YdButton>
                    <YdTextarea v-model="form.aiSystemPrompt" :auto-size="{ minRows: 3, maxRows: 8 }" placeholder="内置默认提示词" />
                  </div>
                </YdFormItem>
              </div>
            </YdForm>
          </YdSettingsSection>
        </template>

        <template #template>
          <YdSettingsSection
            title="消息模板"
            description="推送消息的正文模板与渲染效果；点击参量插入占位符。"
          >
            <YdForm :model="form" layout="vertical">
              <div class="flex flex-col gap-4 max-w-xl">
                <YdFormItem label="推送消息模板" extra="当前显示内置默认模板，可直接修改；与默认完全一致时保存后仍跟随内置默认。">
                  <div class="flex flex-col gap-2">
                    <div class="flex gap-2">
                      <YdButton size="sm" variant="outline" @click="refreshPreview">
                        预览
                      </YdButton>
                      <YdButton size="sm" variant="outline" @click="resetTemplate">
                        恢复默认模板
                      </YdButton>
                    </div>
                    <YdTextarea v-model="form.messageTemplate" :auto-size="{ minRows: 5, maxRows: 12 }" placeholder="内置默认模板" />
                    <div class="mc-news-variables">
                      <YdAction
                        v-for="variable in variableList()"
                        :key="variable.name"
                        class="mc-news-variable-chip"
                        :title="variable.description"
                        @click="insertVariable(variable.name)"
                      >{{ variable.name }}</YdAction>
                    </div>
                  </div>
                </YdFormItem>
                <YdFormItem label="推送效果预览（支持 Markdown）" extra="群聊推送使用 Markdown 渲染时，QQ 官方机器人将呈现与预览一致的标题、引用与链接效果；纯文本协议则按原文发送。">
                  <div class="mc-news-preview-box">
                    <MdPreview
                      class="mc-news-markdown"
                      language="zh-CN"
                      preview-theme="github"
                      code-theme="github"
                      :model-value="preview"
                    />
                  </div>
                </YdFormItem>
              </div>
            </YdForm>
          </YdSettingsSection>
        </template>
      </YdSettingsLayout>
    </YdLoadingOverlay>
  </div>
</template>
