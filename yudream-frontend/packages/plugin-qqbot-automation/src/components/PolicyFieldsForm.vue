<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  FaIcon,
  FaInput,
  FaNumberField,
  FaSelect,
  FaSwitch,
  FaTextarea,
  FaTooltip,
  YdTablePicker,
  type TableColumn,
  type YdTablePickerQuery,
  type YdTablePickerResult,
} from '@yudream/components'
import type { AiProviderOption, AutomationPolicy, AutomationPolicyOverride, PolicyOverrideField, UserOption } from '../types'

type UserPickerRow = UserOption & { label: string }

const props = withDefaults(defineProps<{
  basePolicy: AutomationPolicy
  override: AutomationPolicyOverride
  aiProviders?: AiProviderOption[]
  allowInheritance?: boolean
  userFetcher?: (query: YdTablePickerQuery) => Promise<YdTablePickerResult<UserPickerRow>>
  userLabels?: Record<string, string>
  groupOptions?: { label: string, value: string }[]
}>(), {
  aiProviders: () => [],
  allowInheritance: true,
  userFetcher: undefined,
  userLabels: undefined,
  groupOptions: () => [],
})

const emit = defineEmits<{
  'update:override': [value: AutomationPolicyOverride]
}>()

const fallbackDecisionOptions = [
  { label: '无法判断时拒绝', value: 'fail-closed' },
  { label: '无法判断时通过', value: 'fail-open' },
]

const userPickerColumns: TableColumn<UserPickerRow>[] = [
  { id: 'nickname', accessorKey: 'nickname', header: '昵称', width: 160 },
  { id: 'username', accessorKey: 'username', header: '用户名', width: 160 },
  { id: 'deptNames', accessorKey: 'deptNames', header: '部门' },
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

function numberFor(field: PolicyOverrideField) {
  return Number(valueFor(field) ?? 0)
}

function setNumber(field: PolicyOverrideField, value: number | undefined) {
  update(field, Number.isFinite(value) ? Math.round(Number(value)) : 0)
}

const adminUserIds = computed(() => (valueFor('riskAlertAdminUserIds') as string[]) ?? [])

function setAdminUsers(ids: string[]) {
  update('riskAlertAdminUserIds', ids)
}

const extraAlertGroupId = ref('')

function addExtraAlertGroup() {
  const id = extraAlertGroupId.value.trim()
  if (!id) return
  update('riskAlertGroupChannelId', id)
  extraAlertGroupId.value = ''
}

const alertGroupOptions = computed(() => {
  const known = [...props.groupOptions]
  const current = String(valueFor('riskAlertGroupChannelId') ?? '')
  if (current && !known.some(item => item.value === current)) {
    known.push({ label: current, value: current })
  }
  return known
})
</script>

<template>
  <div class="space-y-5">
    <!-- 运行开关 -->
    <section class="rounded-xl border p-5">
      <div class="mb-4 flex items-center gap-2">
        <FaIcon name="i-lucide:toggle-left" class="text-primary" />
        <div>
          <h3 class="text-sm font-semibold">运行开关</h3>
          <p class="mt-0.5 text-xs text-muted-foreground">
            {{ allowInheritance ? '每个字段左侧的「自定义」开关决定该字段覆盖还是继承连接默认策略。' : '控制本连接下各自动化能力的启用状态。' }}
          </p>
        </div>
      </div>
      <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <div class="flex items-center justify-between gap-3 rounded-lg border bg-muted/30 px-4 py-3">
          <div class="min-w-0">
            <p class="text-sm font-medium">自动化策略</p>
            <p class="mt-0.5 text-xs text-muted-foreground">允许群消息触发自动化处理</p>
          </div>
          <div class="flex shrink-0 items-center gap-2">
            <FaTooltip v-if="allowInheritance" text="开启后自定义该字段，关闭则继承默认">
              <FaSwitch :model-value="!isInherited('enabled')" @update:model-value="setOverride('enabled', $event ?? false)" />
            </FaTooltip>
            <FaSwitch :model-value="Boolean(valueFor('enabled'))" :disabled="isInherited('enabled')" @update:model-value="update('enabled', $event ?? false)" />
          </div>
        </div>
        <div class="flex items-center justify-between gap-3 rounded-lg border bg-muted/30 px-4 py-3">
          <div class="min-w-0">
            <p class="text-sm font-medium">媒体解析</p>
            <p class="mt-0.5 text-xs text-muted-foreground">处理抖音和 Bilibili 分享链接</p>
          </div>
          <div class="flex shrink-0 items-center gap-2">
            <FaTooltip v-if="allowInheritance" text="开启后自定义该字段，关闭则继承默认">
              <FaSwitch :model-value="!isInherited('mediaEnabled')" @update:model-value="setOverride('mediaEnabled', $event ?? false)" />
            </FaTooltip>
            <FaSwitch :model-value="Boolean(valueFor('mediaEnabled'))" :disabled="isInherited('mediaEnabled')" @update:model-value="update('mediaEnabled', $event ?? false)" />
          </div>
        </div>
        <div class="flex items-center justify-between gap-3 rounded-lg border bg-muted/30 px-4 py-3">
          <div class="min-w-0">
            <p class="text-sm font-medium">入群验证</p>
            <p class="mt-0.5 text-xs text-muted-foreground">自动审核匹配的入群申请</p>
          </div>
          <div class="flex shrink-0 items-center gap-2">
            <FaTooltip v-if="allowInheritance" text="开启后自定义该字段，关闭则继承默认">
              <FaSwitch :model-value="!isInherited('joinVerificationEnabled')" @update:model-value="setOverride('joinVerificationEnabled', $event ?? false)" />
            </FaTooltip>
            <FaSwitch :model-value="Boolean(valueFor('joinVerificationEnabled'))" :disabled="isInherited('joinVerificationEnabled')" @update:model-value="update('joinVerificationEnabled', $event ?? false)" />
          </div>
        </div>
        <div class="flex items-center justify-between gap-3 rounded-lg border bg-muted/30 px-4 py-3">
          <div class="min-w-0">
            <p class="text-sm font-medium">AI 兜底</p>
            <p class="mt-0.5 text-xs text-muted-foreground">入群验证规则未命中时再请求 AI</p>
          </div>
          <div class="flex shrink-0 items-center gap-2">
            <FaTooltip v-if="allowInheritance" text="开启后自定义该字段，关闭则继承默认">
              <FaSwitch :model-value="!isInherited('aiFallbackEnabled')" @update:model-value="setOverride('aiFallbackEnabled', $event ?? false)" />
            </FaTooltip>
            <FaSwitch :model-value="Boolean(valueFor('aiFallbackEnabled'))" :disabled="isInherited('aiFallbackEnabled')" @update:model-value="update('aiFallbackEnabled', $event ?? false)" />
          </div>
        </div>
        <div class="flex items-center justify-between gap-3 rounded-lg border bg-muted/30 px-4 py-3 sm:col-span-2">
          <div class="min-w-0">
            <p class="text-sm font-medium">风险监测</p>
            <p class="mt-0.5 text-xs text-muted-foreground">累计群消息批量送 AI 审核，按置信度自动禁言并推送告警</p>
          </div>
          <div class="flex shrink-0 items-center gap-2">
            <FaTooltip v-if="allowInheritance" text="开启后自定义该字段，关闭则继承默认">
              <FaSwitch :model-value="!isInherited('riskMonitorEnabled')" @update:model-value="setOverride('riskMonitorEnabled', $event ?? false)" />
            </FaTooltip>
            <FaSwitch :model-value="Boolean(valueFor('riskMonitorEnabled'))" :disabled="isInherited('riskMonitorEnabled')" @update:model-value="update('riskMonitorEnabled', $event ?? false)" />
          </div>
        </div>
      </div>
    </section>

    <!-- 媒体服务 -->
    <section class="rounded-xl border p-5">
      <div class="mb-4 flex items-center justify-between gap-3">
        <div class="flex items-center gap-2">
          <FaIcon name="i-lucide:clapperboard" class="text-primary" />
          <div>
            <h3 class="text-sm font-semibold">媒体服务</h3>
            <p class="mt-0.5 text-xs text-muted-foreground">媒体解析服务的可访问地址，机器人容器也必须能访问。</p>
          </div>
        </div>
        <FaTooltip v-if="allowInheritance" text="开启后自定义该字段，关闭则继承默认">
          <FaSwitch :model-value="!isInherited('mediaProviderEndpoint')" @update:model-value="setOverride('mediaProviderEndpoint', $event ?? false)" />
        </FaTooltip>
      </div>
      <FaInput
        class="w-full"
        type="url"
        placeholder="http://服务器地址:端口"
        :model-value="String(valueFor('mediaProviderEndpoint') ?? '')"
        :disabled="isInherited('mediaProviderEndpoint')"
        @update:model-value="update('mediaProviderEndpoint', String($event ?? ''))"
      />
      <p class="mt-2 text-xs text-muted-foreground">该服务使用 <code>/api/download</code> 解析并返回文件。容器部署时不要填 <code>127.0.0.1</code>，请使用宿主机局域网 IP、域名或 Docker 服务名。官方 QQ 连接自动改用公网直链发送。</p>
    </section>

    <!-- 风险监测 -->
    <section v-if="valueFor('riskMonitorEnabled')" class="rounded-xl border p-5">
      <div class="mb-4 flex items-center gap-2">
        <FaIcon name="i-lucide:shield-alert" class="text-primary" />
        <div>
          <h3 class="text-sm font-semibold">风险监测</h3>
          <p class="mt-0.5 text-xs text-muted-foreground">送检内容包含发送者 QQ、绑定账号昵称与时间，AI 按条判定风险与置信度。</p>
        </div>
      </div>
      <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div class="space-y-2">
          <div class="flex items-center justify-between gap-3">
            <label class="text-sm font-medium">送检条数</label>
            <FaTooltip v-if="allowInheritance" text="开启后自定义该字段，关闭则继承默认">
              <FaSwitch :model-value="!isInherited('riskBatchSize')" @update:model-value="setOverride('riskBatchSize', $event ?? false)" />
            </FaTooltip>
          </div>
          <FaNumberField class="w-full" :min="5" :max="100" :model-value="numberFor('riskBatchSize')" :disabled="isInherited('riskBatchSize')" @update:model-value="setNumber('riskBatchSize', $event)" />
          <p class="text-xs text-muted-foreground">每群累计满该条数即整批送 AI 审核一次（5-100）。</p>
        </div>
        <div class="space-y-2">
          <div class="flex items-center justify-between gap-3">
            <label class="text-sm font-medium">告警置信度（%）</label>
            <FaTooltip v-if="allowInheritance" text="开启后自定义该字段，关闭则继承默认">
              <FaSwitch :model-value="!isInherited('riskAlertConfidence')" @update:model-value="setOverride('riskAlertConfidence', $event ?? false)" />
            </FaTooltip>
          </div>
          <FaNumberField class="w-full" :min="0" :max="100" :model-value="numberFor('riskAlertConfidence')" :disabled="isInherited('riskAlertConfidence')" @update:model-value="setNumber('riskAlertConfidence', $event)" />
          <p class="text-xs text-muted-foreground">达到该置信度的风险消息才会推送告警。</p>
        </div>
      </div>

      <div class="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2">
        <div class="flex items-center justify-between gap-3 rounded-lg border bg-muted/30 px-4 py-3">
          <div class="min-w-0">
            <p class="text-sm font-medium">推送到指定群</p>
            <p class="mt-0.5 text-xs text-muted-foreground">风险告警发到单独指定的群，而不是出事的那个群</p>
          </div>
          <div class="flex shrink-0 items-center gap-2">
            <FaTooltip v-if="allowInheritance" text="开启后自定义该字段，关闭则继承默认">
              <FaSwitch :model-value="!isInherited('riskAlertGroup')" @update:model-value="setOverride('riskAlertGroup', $event ?? false)" />
            </FaTooltip>
            <FaSwitch :model-value="Boolean(valueFor('riskAlertGroup'))" :disabled="isInherited('riskAlertGroup')" @update:model-value="update('riskAlertGroup', $event ?? false)" />
          </div>
        </div>
        <div class="flex items-center justify-between gap-3 rounded-lg border bg-muted/30 px-4 py-3">
          <div class="min-w-0">
            <p class="text-sm font-medium">私信推送管理员</p>
            <p class="mt-0.5 text-xs text-muted-foreground">向所选管理员发送私聊告警</p>
          </div>
          <div class="flex shrink-0 items-center gap-2">
            <FaTooltip v-if="allowInheritance" text="开启后自定义该字段，关闭则继承默认">
              <FaSwitch :model-value="!isInherited('riskAlertAdmin')" @update:model-value="setOverride('riskAlertAdmin', $event ?? false)" />
            </FaTooltip>
            <FaSwitch :model-value="Boolean(valueFor('riskAlertAdmin'))" :disabled="isInherited('riskAlertAdmin')" @update:model-value="update('riskAlertAdmin', $event ?? false)" />
          </div>
        </div>
      </div>

      <div v-if="valueFor('riskAlertGroup')" class="mt-4 space-y-2">
        <div class="flex items-center justify-between gap-3">
          <label class="text-sm font-medium">告警推送群</label>
          <FaTooltip v-if="allowInheritance" text="开启后自定义该字段，关闭则继承默认">
            <FaSwitch :model-value="!isInherited('riskAlertGroupChannelId')" @update:model-value="setOverride('riskAlertGroupChannelId', $event ?? false)" />
          </FaTooltip>
        </div>
        <FaSelect
          class="w-full"
          placeholder="选择接收告警的群"
          :options="alertGroupOptions"
          :model-value="String(valueFor('riskAlertGroupChannelId') ?? '')"
          :disabled="isInherited('riskAlertGroupChannelId')"
          @update:model-value="update('riskAlertGroupChannelId', String($event ?? ''))"
        />
        <FaInput
          v-model="extraAlertGroupId"
          class="w-full"
          placeholder="官方群 openid，回车添加"
          :disabled="isInherited('riskAlertGroupChannelId')"
          @keydown.enter.prevent="addExtraAlertGroup"
        />
        <p class="text-xs text-muted-foreground">告警不会发回出事群。官方 QQ 没有完整群列表时，可粘贴群 openid 后回车添加。</p>
      </div>

      <div v-if="valueFor('riskAlertAdmin') && userFetcher" class="mt-4 space-y-2">
        <div class="flex items-center justify-between gap-3">
          <label class="text-sm font-medium">接收告警的管理员</label>
          <FaTooltip v-if="allowInheritance" text="开启后自定义该字段，关闭则继承默认">
            <FaSwitch :model-value="!isInherited('riskAlertAdminUserIds')" @update:model-value="setOverride('riskAlertAdminUserIds', $event ?? false)" />
          </FaTooltip>
        </div>
        <YdTablePicker
          :model-value="adminUserIds"
          :columns="userPickerColumns"
          :fetcher="userFetcher"
          row-key="id"
          label-key="label"
          title="选择接收告警的管理员"
          placeholder="点击搜索并选择管理员"
          search-placeholder="输入用户名 / 昵称后回车"
          :initial-labels="userLabels"
          :disabled="isInherited('riskAlertAdminUserIds')"
          @update:model-value="setAdminUsers"
        >
          <template #cell-deptNames="{ row }">
            {{ (row.original.deptNames || []).join('、') || '-' }}
          </template>
        </YdTablePicker>
        <p class="text-xs text-muted-foreground">管理员需已绑定 QQ，告警通过绑定的会话送达。</p>
      </div>

      <div class="mt-4 rounded-lg border p-4">
        <div class="flex items-center justify-between gap-3">
          <div class="min-w-0">
            <p class="text-sm font-medium">自动禁言</p>
            <p class="mt-0.5 text-xs text-muted-foreground">按置信度两档设置禁言时长，时长单位为秒（86400 = 1 天）。</p>
          </div>
          <div class="flex shrink-0 items-center gap-2">
            <FaTooltip v-if="allowInheritance" text="开启后自定义该字段，关闭则继承默认">
              <FaSwitch :model-value="!isInherited('riskMuteEnabled')" @update:model-value="setOverride('riskMuteEnabled', $event ?? false)" />
            </FaTooltip>
            <FaSwitch :model-value="Boolean(valueFor('riskMuteEnabled'))" :disabled="isInherited('riskMuteEnabled')" @update:model-value="update('riskMuteEnabled', $event ?? false)" />
          </div>
        </div>
        <div v-if="valueFor('riskMuteEnabled')" class="mt-4 grid grid-cols-2 gap-4 lg:grid-cols-4">
          <div class="space-y-2">
            <div class="flex items-center justify-between gap-2">
              <label class="text-xs font-medium">低档置信度（%）</label>
              <FaTooltip v-if="allowInheritance" text="自定义该字段">
                <FaSwitch :model-value="!isInherited('riskMuteLowConfidence')" @update:model-value="setOverride('riskMuteLowConfidence', $event ?? false)" />
              </FaTooltip>
            </div>
            <FaNumberField class="w-full" :min="0" :max="100" :model-value="numberFor('riskMuteLowConfidence')" :disabled="isInherited('riskMuteLowConfidence')" @update:model-value="setNumber('riskMuteLowConfidence', $event)" />
          </div>
          <div class="space-y-2">
            <div class="flex items-center justify-between gap-2">
              <label class="text-xs font-medium">低档禁言（秒）</label>
              <FaTooltip v-if="allowInheritance" text="自定义该字段">
                <FaSwitch :model-value="!isInherited('riskMuteLowSeconds')" @update:model-value="setOverride('riskMuteLowSeconds', $event ?? false)" />
              </FaTooltip>
            </div>
            <FaNumberField class="w-full" :min="0" :model-value="numberFor('riskMuteLowSeconds')" :disabled="isInherited('riskMuteLowSeconds')" @update:model-value="setNumber('riskMuteLowSeconds', $event)" />
          </div>
          <div class="space-y-2">
            <div class="flex items-center justify-between gap-2">
              <label class="text-xs font-medium">高档置信度（%）</label>
              <FaTooltip v-if="allowInheritance" text="自定义该字段">
                <FaSwitch :model-value="!isInherited('riskMuteHighConfidence')" @update:model-value="setOverride('riskMuteHighConfidence', $event ?? false)" />
              </FaTooltip>
            </div>
            <FaNumberField class="w-full" :min="0" :max="100" :model-value="numberFor('riskMuteHighConfidence')" :disabled="isInherited('riskMuteHighConfidence')" @update:model-value="setNumber('riskMuteHighConfidence', $event)" />
          </div>
          <div class="space-y-2">
            <div class="flex items-center justify-between gap-2">
              <label class="text-xs font-medium">高档禁言（秒）</label>
              <FaTooltip v-if="allowInheritance" text="自定义该字段">
                <FaSwitch :model-value="!isInherited('riskMuteHighSeconds')" @update:model-value="setOverride('riskMuteHighSeconds', $event ?? false)" />
              </FaTooltip>
            </div>
            <FaNumberField class="w-full" :min="0" :model-value="numberFor('riskMuteHighSeconds')" :disabled="isInherited('riskMuteHighSeconds')" @update:model-value="setNumber('riskMuteHighSeconds', $event)" />
          </div>
        </div>
        <p v-if="valueFor('riskMuteEnabled')" class="mt-3 text-xs text-muted-foreground">置信度达到高档阈值按高档时长禁言，达到低档阈值按低档时长禁言；Milky 与官方 QQ 连接均已适配。</p>
      </div>
    </section>

    <!-- AI 决策 -->
    <section class="rounded-xl border p-5">
      <div class="mb-4 flex items-center gap-2">
        <FaIcon name="i-lucide:sparkles" class="text-primary" />
        <div>
          <h3 class="text-sm font-semibold">AI 决策</h3>
          <p class="mt-0.5 text-xs text-muted-foreground">入群验证兜底与风险监测共用的模型配置，模型随提供方联动。</p>
        </div>
      </div>
      <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <div class="space-y-2">
          <div class="flex items-center justify-between gap-3">
            <label class="text-sm font-medium">AI 服务提供方</label>
            <FaTooltip v-if="allowInheritance" text="开启后自定义该字段，关闭则继承默认">
              <FaSwitch :model-value="!isInherited('providerCode')" @update:model-value="setOverride('providerCode', $event ?? false)" />
            </FaTooltip>
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
            <FaTooltip v-if="allowInheritance" text="开启后自定义该字段，关闭则继承默认">
              <FaSwitch :model-value="!isInherited('modelCode')" @update:model-value="setOverride('modelCode', $event ?? false)" />
            </FaTooltip>
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
            <FaTooltip v-if="allowInheritance" text="开启后自定义该字段，关闭则继承默认">
              <FaSwitch :model-value="!isInherited('failClosed')" @update:model-value="setOverride('failClosed', $event ?? false)" />
            </FaTooltip>
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

    <!-- 入群审核规则 -->
    <section class="rounded-xl border p-5">
      <div class="mb-4 flex items-center gap-2">
        <FaIcon name="i-lucide:user-check" class="text-primary" />
        <div>
          <h3 class="text-sm font-semibold">入群审核规则</h3>
          <p class="mt-0.5 text-xs text-muted-foreground">每行一个匹配答案。官方 QQ 只处理入群申请事件（GROUP_JOIN_REQUEST），机器人必须是该群管理员并订阅群成员事件；已入群通知（GROUP_MEMBER_ADD）无法再审批。</p>
        </div>
      </div>
      <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <div class="space-y-2">
          <div class="flex items-center justify-between gap-3">
            <label class="text-sm font-medium">通过答案</label>
            <FaTooltip v-if="allowInheritance" text="开启后自定义该字段，关闭则继承默认">
              <FaSwitch :model-value="!isInherited('approvedAnswers')" @update:model-value="setOverride('approvedAnswers', $event ?? false)" />
            </FaTooltip>
          </div>
          <FaTextarea class="min-h-28" placeholder="每行一个答案" :model-value="textFor('approvedAnswers')" :disabled="isInherited('approvedAnswers')" @update:model-value="setAnswerList('approvedAnswers', $event)" />
        </div>
        <div class="space-y-2">
          <div class="flex items-center justify-between gap-3">
            <label class="text-sm font-medium">拒绝答案</label>
            <FaTooltip v-if="allowInheritance" text="开启后自定义该字段，关闭则继承默认">
              <FaSwitch :model-value="!isInherited('rejectedAnswers')" @update:model-value="setOverride('rejectedAnswers', $event ?? false)" />
            </FaTooltip>
          </div>
          <FaTextarea class="min-h-28" placeholder="每行一个答案" :model-value="textFor('rejectedAnswers')" :disabled="isInherited('rejectedAnswers')" @update:model-value="setAnswerList('rejectedAnswers', $event)" />
        </div>
      </div>
    </section>
  </div>
</template>
