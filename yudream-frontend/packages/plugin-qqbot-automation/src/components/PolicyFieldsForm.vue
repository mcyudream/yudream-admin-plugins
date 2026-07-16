<script setup lang="ts">
import { computed } from 'vue'
import { FaInput, FaSelect, FaSwitch, FaTextarea } from '@yudream/components'
import type { AiProviderOption, AutomationPolicy, AutomationPolicyOverride, PolicyOverrideField } from '../types'

const props = withDefaults(defineProps<{
  basePolicy: AutomationPolicy
  override: AutomationPolicyOverride
  aiProviders?: AiProviderOption[]
  allowInheritance?: boolean
}>(), {
  aiProviders: () => [],
  allowInheritance: true,
})

const emit = defineEmits<{
  'update:override': [value: AutomationPolicyOverride]
}>()

const fallbackDecisionOptions = [
  { label: '无法判断时拒绝', value: 'fail-closed' },
  { label: '无法判断时通过', value: 'fail-open' },
]

const providerOptions = computed(() => [
  { label: '不使用 AI', value: '' },
  ...props.aiProviders.map(provider => ({ label: provider.name, value: provider.code })),
])

const selectedProvider = computed(() => props.aiProviders.find(provider => provider.code === String(valueFor('providerCode') ?? '')))
const modelOptions = computed(() => [
  { label: '由提供方默认模型决定', value: '' },
  ...(selectedProvider.value?.models ?? []).map(model => ({ label: model.name, value: model.code })),
])

function isInherited(field: PolicyOverrideField) {
  return props.allowInheritance && props.override[field] === null
}

function valueFor(field: PolicyOverrideField) {
  return props.override[field] === null ? props.basePolicy[field] : props.override[field]
}

function update(field: PolicyOverrideField, value: AutomationPolicyOverride[PolicyOverrideField]) {
  emit('update:override', { ...props.override, [field]: value })
}

function setOverride(field: PolicyOverrideField, enabled: boolean) {
  update(field, enabled ? props.basePolicy[field] : null)
}

function textFor(field: 'approvedAnswers' | 'rejectedAnswers') {
  return ((valueFor(field) as string[]) ?? []).join('\n')
}

function setAnswerList(field: 'approvedAnswers' | 'rejectedAnswers', value: string | number | undefined) {
  update(field, String(value ?? '').split('\n').map(item => item.trim()).filter(Boolean))
}

function setProvider(value: unknown) {
  const providerCode = String(value ?? '')
  const providerChanged = providerCode !== String(valueFor('providerCode') ?? '')
  emit('update:override', {
    ...props.override,
    providerCode,
    modelCode: providerChanged ? '' : props.override.modelCode,
  })
}

function setSelectBoolean(value: unknown) {
  update('failClosed', value === 'fail-closed')
}
</script>

<template>
  <div class="space-y-6">
    <section class="space-y-3">
      <div>
        <h3 class="text-sm font-semibold">运行开关</h3>
        <p v-if="allowInheritance" class="mt-1 text-sm text-muted-foreground">关闭“覆盖”后，该项立即继承连接默认策略。</p>
      </div>
      <div class="grid grid-cols-1 gap-x-6 gap-y-3 lg:grid-cols-2">
        <div class="flex items-center justify-between gap-4 border-b py-3">
          <div>
            <p class="font-medium">自动化策略</p>
            <p class="text-sm text-muted-foreground">允许群消息触发自动化处理</p>
          </div>
          <div class="flex shrink-0 items-center gap-3">
            <FaSwitch v-if="allowInheritance" :model-value="!isInherited('enabled')" @update:model-value="setOverride('enabled', $event ?? false)" />
            <FaSwitch :model-value="Boolean(valueFor('enabled'))" :disabled="isInherited('enabled')" @update:model-value="update('enabled', $event ?? false)" />
          </div>
        </div>
        <div class="flex items-center justify-between gap-4 border-b py-3">
          <div>
            <p class="font-medium">媒体解析</p>
            <p class="text-sm text-muted-foreground">处理抖音和 Bilibili 分享链接</p>
          </div>
          <div class="flex shrink-0 items-center gap-3">
            <FaSwitch v-if="allowInheritance" :model-value="!isInherited('mediaEnabled')" @update:model-value="setOverride('mediaEnabled', $event ?? false)" />
            <FaSwitch :model-value="Boolean(valueFor('mediaEnabled'))" :disabled="isInherited('mediaEnabled')" @update:model-value="update('mediaEnabled', $event ?? false)" />
          </div>
        </div>
        <div class="flex items-center justify-between gap-4 border-b py-3">
          <div>
            <p class="font-medium">入群验证</p>
            <p class="text-sm text-muted-foreground">自动审核匹配的入群申请</p>
          </div>
          <div class="flex shrink-0 items-center gap-3">
            <FaSwitch v-if="allowInheritance" :model-value="!isInherited('joinVerificationEnabled')" @update:model-value="setOverride('joinVerificationEnabled', $event ?? false)" />
            <FaSwitch :model-value="Boolean(valueFor('joinVerificationEnabled'))" :disabled="isInherited('joinVerificationEnabled')" @update:model-value="update('joinVerificationEnabled', $event ?? false)" />
          </div>
        </div>
        <div class="flex items-center justify-between gap-4 border-b py-3">
          <div>
            <p class="font-medium">AI 兜底</p>
            <p class="text-sm text-muted-foreground">规则没有命中时再请求 AI</p>
          </div>
          <div class="flex shrink-0 items-center gap-3">
            <FaSwitch v-if="allowInheritance" :model-value="!isInherited('aiFallbackEnabled')" @update:model-value="setOverride('aiFallbackEnabled', $event ?? false)" />
            <FaSwitch :model-value="Boolean(valueFor('aiFallbackEnabled'))" :disabled="isInherited('aiFallbackEnabled')" @update:model-value="update('aiFallbackEnabled', $event ?? false)" />
          </div>
        </div>
      </div>
    </section>

    <section class="space-y-4 border-t pt-5">
      <div class="flex items-center justify-between gap-3">
        <div>
          <h3 class="text-sm font-semibold">媒体服务</h3>
          <p class="mt-1 text-sm text-muted-foreground">填写媒体服务的可访问地址；机器人容器也必须能访问该地址。</p>
        </div>
        <FaSwitch v-if="allowInheritance" :model-value="!isInherited('mediaProviderEndpoint')" @update:model-value="setOverride('mediaProviderEndpoint', $event ?? false)" />
      </div>
      <FaInput
        class="w-full"
        type="url"
        placeholder="http://服务器地址:端口"
        :model-value="String(valueFor('mediaProviderEndpoint') ?? '')"
        :disabled="isInherited('mediaProviderEndpoint')"
        @update:model-value="update('mediaProviderEndpoint', String($event ?? ''))"
      />
      <p class="text-sm text-muted-foreground">该服务使用 <code>/api/download</code> 解析并返回文件。容器部署时不要填 <code>127.0.0.1</code>，请使用宿主机局域网 IP、域名或 Docker 服务名。</p>
    </section>

    <section class="space-y-4 border-t pt-5">
      <div class="flex items-center justify-between gap-3">
        <div>
          <h3 class="text-sm font-semibold">AI 决策</h3>
          <p class="mt-1 text-sm text-muted-foreground">提供方与模型来自系统配置，模型会随提供方联动。</p>
        </div>
      </div>
      <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <div class="space-y-2">
          <div class="flex items-center justify-between gap-3">
            <label class="text-sm font-medium">AI 服务提供方</label>
            <FaSwitch v-if="allowInheritance" :model-value="!isInherited('providerCode')" @update:model-value="setOverride('providerCode', $event ?? false)" />
          </div>
          <FaSelect
            class="w-full"
            :options="providerOptions"
            :model-value="String(valueFor('providerCode') ?? '')"
            :disabled="isInherited('providerCode')"
            @update:model-value="setProvider"
          />
        </div>
        <div class="space-y-2">
          <div class="flex items-center justify-between gap-3">
            <label class="text-sm font-medium">AI 模型</label>
            <FaSwitch v-if="allowInheritance" :model-value="!isInherited('modelCode')" @update:model-value="setOverride('modelCode', $event ?? false)" />
          </div>
          <FaSelect
            class="w-full"
            :options="modelOptions"
            :model-value="String(valueFor('modelCode') ?? '')"
            :disabled="isInherited('modelCode') || !selectedProvider"
            @update:model-value="update('modelCode', String($event ?? ''))"
          />
        </div>
        <div class="space-y-2 lg:col-span-2">
          <div class="flex items-center justify-between gap-3">
            <label class="text-sm font-medium">AI 兜底结果</label>
            <FaSwitch v-if="allowInheritance" :model-value="!isInherited('failClosed')" @update:model-value="setOverride('failClosed', $event ?? false)" />
          </div>
          <FaSelect
            class="w-full"
            :options="fallbackDecisionOptions"
            :model-value="Boolean(valueFor('failClosed')) ? 'fail-closed' : 'fail-open'"
            :disabled="isInherited('failClosed')"
            @update:model-value="setSelectBoolean"
          />
        </div>
      </div>
    </section>

    <section class="space-y-4 border-t pt-5">
      <div>
        <h3 class="text-sm font-semibold">入群审核规则</h3>
        <p class="mt-1 text-sm text-muted-foreground">每行填写一个匹配答案。</p>
      </div>
      <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <div class="space-y-2">
          <div class="flex items-center justify-between gap-3">
            <label class="text-sm font-medium">通过答案</label>
            <FaSwitch v-if="allowInheritance" :model-value="!isInherited('approvedAnswers')" @update:model-value="setOverride('approvedAnswers', $event ?? false)" />
          </div>
          <FaTextarea class="min-h-28" placeholder="每行一个答案" :model-value="textFor('approvedAnswers')" :disabled="isInherited('approvedAnswers')" @update:model-value="setAnswerList('approvedAnswers', $event)" />
        </div>
        <div class="space-y-2">
          <div class="flex items-center justify-between gap-3">
            <label class="text-sm font-medium">拒绝答案</label>
            <FaSwitch v-if="allowInheritance" :model-value="!isInherited('rejectedAnswers')" @update:model-value="setOverride('rejectedAnswers', $event ?? false)" />
          </div>
          <FaTextarea class="min-h-28" placeholder="每行一个答案" :model-value="textFor('rejectedAnswers')" :disabled="isInherited('rejectedAnswers')" @update:model-value="setAnswerList('rejectedAnswers', $event)" />
        </div>
      </div>
    </section>
  </div>
</template>
