<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  FaAlert,
  FaButton,
  FaCard,
  FaDrawer,
  FaIcon,
  FaInput,
  FaPageHeader,
  FaPageMain,
  FaPagination,
  FaSelect,
  FaResponsiveTable,
  FaTag,
  useFaModal,
  useFaToast,
  type TableColumn,
  type YdTablePickerQuery,
  type YdTablePickerResult,
} from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { createQqbotAutomationApi } from '../api/qqbot-automation-api'
import PolicyFieldsForm from '../components/PolicyFieldsForm.vue'
import {
  POLICY_OVERRIDE_FIELDS,
  completeOverride,
  emptyOverride,
  emptyPolicy,
  policyFromOverride,
  type AutomationPolicy,
  type AutomationPolicyOverride,
  type AiProviderOption,
  type Option,
  type UserOption,
} from '../types'

type UserPickerRow = UserOption & { label: string }

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createQqbotAutomationApi(props.sdk)
const toast = useFaToast()
const modal = useFaModal()

const connections = ref<Option[]>([])
const groups = ref<Option[]>([])
const aiProviders = ref<AiProviderOption[]>([])
const connectionId = ref('')
const defaultPolicy = ref<AutomationPolicy>(emptyPolicy())
const defaultDraft = ref<AutomationPolicyOverride>(completeOverride(emptyPolicy()))
const overrides = ref<AutomationPolicyOverride[]>([])
const loading = ref(false)
const savingDefault = ref(false)
const savingOverride = ref(false)
const error = ref('')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const editorOpen = ref(false)
const editorChannelId = ref('')
const extraGroupId = ref('')
const editorDraft = ref<AutomationPolicyOverride>(emptyOverride())
const mediaSettingsDraft = ref({ hostDirectory: '', containerDirectory: '/media' })
const savingMediaSettings = ref(false)
const userLabelCache = ref<Record<string, string>>({})

function protocolLabel(item: Option) {
  if (item.protocol === 'official') {
    return '官方 QQ'
  }
  if (item.protocol === 'milky') {
    return 'Milky'
  }
  return item.platform || '未知平台'
}

function protocolVariant(item: Option | undefined) {
  return item?.protocol === 'official' ? 'default' as const : 'secondary' as const
}

const currentConnection = computed(() => connections.value.find(item => item.id === connectionId.value))
const connectionOptions = computed(() => connections.value.map(item => ({ label: `${item.name}（${protocolLabel(item)}）`, value: item.id })))
const groupOptions = computed(() => {
  const known = groups.value.map(item => ({ label: item.name || item.id, value: item.id }))
  const knownIds = new Set(known.map(item => item.value))
  const extras = [editorChannelId.value, ...overrides.value.map(item => item.channelId)]
    .filter(id => id && !knownIds.has(id))
    .map(id => ({ label: id, value: id }))
  return [...known, ...extras]
})
const editorTitle = computed(() => editorChannelId.value ? `群级覆盖：${groupName(editorChannelId.value)}` : '新增群级覆盖')

const columns: TableColumn<AutomationPolicyOverride>[] = [
  { id: 'group', header: '群聊', width: 200, fixed: 'left' },
  { id: 'enabled', header: '策略状态', width: 120, align: 'center' },
  { id: 'media', header: '媒体解析', width: 110, align: 'center' },
  { id: 'risk', header: '风险监测', width: 110, align: 'center' },
  { id: 'overrides', header: '覆盖字段', width: 110, align: 'center' },
  { id: 'operation', header: '操作', width: 170, fixed: 'right', align: 'center' },
]

function groupName(channelId: string) {
  return groups.value.find(item => item.id === channelId)?.name || channelId
}

function countOverrides(override: AutomationPolicyOverride) {
  return POLICY_OVERRIDE_FIELDS.filter(field => override[field] !== null).length
}

function policyState(override: AutomationPolicyOverride) {
  if (override.enabled === null) return { label: '继承默认', variant: 'secondary' as const }
  return override.enabled ? { label: '已启用', variant: 'default' as const } : { label: '已关闭', variant: 'destructive' as const }
}

function mediaState(override: AutomationPolicyOverride) {
  if (override.mediaEnabled === null) return '继承默认'
  return override.mediaEnabled ? '已开启' : '已关闭'
}

function riskState(override: AutomationPolicyOverride) {
  if (override.riskMonitorEnabled === null) return '继承默认'
  return override.riskMonitorEnabled ? '已开启' : '已关闭'
}

async function fetchUserOptions(query: YdTablePickerQuery): Promise<YdTablePickerResult<UserPickerRow>> {
  try {
    const result = await api.userOptions(query.keyword, query.page, query.size)
    const list = result.records.map(user => ({ ...user, label: user.nickname || user.username || user.id }))
    for (const row of list) {
      userLabelCache.value[row.id] = row.label
    }
    return { list, total: Number(result.total) || 0 }
  }
  catch {
    return { list: [], total: 0 }
  }
}

function showError(value: unknown, fallback: string) {
  error.value = value instanceof Error && value.message ? value.message : fallback
  toast.error(error.value)
}

async function loadCurrentConnection() {
  if (!connectionId.value) return
  loading.value = true
  error.value = ''
  try {
    const [policy, connectionGroups] = await Promise.all([
      api.defaultPolicy(connectionId.value),
      api.groups(connectionId.value),
    ])
    defaultPolicy.value = policy
    defaultDraft.value = completeOverride(policy)
    groups.value = connectionGroups
    await loadOverrides()
  }
  catch (cause) {
    showError(cause, '加载连接策略失败')
  }
  finally {
    loading.value = false
  }
}

async function loadOverrides() {
  if (!connectionId.value) {
    overrides.value = []
    total.value = 0
    return
  }
  try {
    const result = await api.groupOverrides(connectionId.value, page.value, size.value)
    overrides.value = result.records
    total.value = result.total
  }
  catch (cause) {
    showError(cause, '加载群级覆盖失败')
  }
}

async function saveDefault() {
  if (!connectionId.value) return
  savingDefault.value = true
  try {
    const saved = await api.saveDefaultPolicy({ ...policyFromOverride(defaultDraft.value), connectionId: connectionId.value, channelId: '' })
    defaultPolicy.value = saved
    defaultDraft.value = completeOverride(saved)
    toast.success('连接默认策略已保存')
  }
  catch (cause) {
    showError(cause, '保存连接默认策略失败')
  }
  finally {
    savingDefault.value = false
  }
}

function openCreate() {
  editorChannelId.value = ''
  extraGroupId.value = ''
  editorDraft.value = emptyOverride(connectionId.value)
  editorOpen.value = true
}

function addExtraGroup() {
  const id = extraGroupId.value.trim()
  if (!id) return
  editorChannelId.value = id
  extraGroupId.value = ''
}

async function openEdit(row: AutomationPolicyOverride) {
  editorChannelId.value = row.channelId
  editorDraft.value = { ...row, approvedAnswers: row.approvedAnswers ? [...row.approvedAnswers] : null, rejectedAnswers: row.rejectedAnswers ? [...row.rejectedAnswers] : null }
  editorOpen.value = true
  try {
    const current = await api.groupOverride(connectionId.value, row.channelId)
    if (current) editorDraft.value = current
  }
  catch (cause) {
    showError(cause, '加载群级覆盖详情失败')
  }
}

function closeEditor() {
  editorOpen.value = false
  editorChannelId.value = ''
  extraGroupId.value = ''
  editorDraft.value = emptyOverride(connectionId.value)
}

async function saveOverride() {
  const channelId = editorChannelId.value
  if (!connectionId.value || !channelId) {
    toast.error('请选择需要配置的群聊')
    return
  }
  savingOverride.value = true
  try {
    await api.saveGroupOverride({ ...editorDraft.value, connectionId: connectionId.value, channelId })
    toast.success('群级覆盖已保存')
    closeEditor()
    await loadOverrides()
  }
  catch (cause) {
    showError(cause, '保存群级覆盖失败')
  }
  finally {
    savingOverride.value = false
  }
}

function confirmDelete(row: AutomationPolicyOverride) {
  modal.confirm({
    title: '删除群级覆盖',
    description: `删除后，“${groupName(row.channelId)}”将恢复使用连接默认策略。`,
    confirmButtonText: '删除覆盖',
    onConfirm: async () => {
      try {
        await api.deleteGroupOverride(connectionId.value, row.channelId)
        toast.success('群级覆盖已删除')
        if (overrides.value.length === 1 && page.value > 1) page.value -= 1
        await loadOverrides()
      }
      catch (cause) {
        showError(cause, '删除群级覆盖失败')
      }
    },
  })
}

function changeConnection(value: unknown) {
  connectionId.value = String(value ?? '')
}

async function saveMediaSettings() {
  savingMediaSettings.value = true
  try {
    const saved = await api.saveMediaSettings({
      hostDirectory: mediaSettingsDraft.value.hostDirectory.trim() || null,
      containerDirectory: mediaSettingsDraft.value.containerDirectory.trim(),
    })
    mediaSettingsDraft.value = { hostDirectory: saved.hostDirectory ?? '', containerDirectory: saved.containerDirectory || '/media' }
    toast.success('媒体存储设置已保存')
  }
  catch (cause) {
    showError(cause, '保存媒体存储设置失败')
  }
  finally {
    savingMediaSettings.value = false
  }
}

watch(connectionId, () => {
  page.value = 1
  groups.value = []
  overrides.value = []
  total.value = 0
  defaultPolicy.value = emptyPolicy(connectionId.value)
  defaultDraft.value = completeOverride(defaultPolicy.value)
  if (connectionId.value) void loadCurrentConnection()
})

watch([page, size], () => {
  if (connectionId.value) void loadOverrides()
})

onMounted(async () => {
  try {
    const [connectionOptions, providers, settings] = await Promise.all([api.connections(), api.aiOptions(), api.mediaSettings()])
    connections.value = connectionOptions
    aiProviders.value = providers
    mediaSettingsDraft.value = { hostDirectory: settings.hostDirectory ?? '', containerDirectory: settings.containerDirectory || '/media' }
  }
  catch (cause) {
    showError(cause, '加载 QQ 连接失败')
  }
})
</script>

<template>
  <section>
    <FaPageHeader title="群自动化策略" description="配置连接默认策略，并按需为单独群聊覆盖指定字段。" />

    <FaPageMain>
      <div class="qqbot-automation-page">
      <FaAlert v-if="error" variant="destructive" title="操作未完成" :description="error" />

      <div class="qa-row">
        <section class="qa-card">
          <div class="qa-card-head">
            <div>
              <h2 class="qa-title">QQ 连接</h2>
              <p class="qa-desc">切换连接后加载该连接的默认策略与群级覆盖。</p>
            </div>
            <FaButton variant="outline" size="sm" :disabled="!connectionId || loading" @click="loadCurrentConnection">
              <FaIcon name="i-lucide:refresh-cw" />
              刷新
            </FaButton>
          </div>
          <div class="qa-card-body">
            <div class="qa-select-row">
              <FaSelect
                placeholder="选择连接"
                :model-value="connectionId"
                :options="connectionOptions"
                :disabled="loading"
                @update:model-value="changeConnection"
              />
            </div>
            <div v-if="currentConnection" class="qa-hint">
              <FaTag :variant="protocolVariant(currentConnection)">{{ protocolLabel(currentConnection) }}</FaTag>
              <span v-if="currentConnection.protocol === 'official'">官方连接：媒体签发公网直链发送；入群审批走官方申请事件（机器人须为群管理员），告警发到单独指定群。</span>
              <span v-else>Milky 连接：媒体经共享目录落盘，支持合并转发与语音。</span>
            </div>
          </div>
        </section>

        <section class="qa-card">
          <div class="qa-card-head">
            <div>
              <h2 class="qa-title">媒体存储</h2>
              <p class="qa-desc">Milky 共享目录，加密保存在插件密钥库；官方连接也依赖此目录签发公网直链。</p>
            </div>
            <FaButton variant="outline" size="sm" :loading="savingMediaSettings" @click="saveMediaSettings">保存</FaButton>
          </div>
          <div class="qa-card-body">
            <div class="qa-fields">
              <div class="qa-field">
                <label class="qa-label">宿主机媒体目录</label>
                <FaInput v-model="mediaSettingsDraft.hostDirectory" class="w-full" placeholder="如 D:/media 或 /data/media" clearable />
              </div>
              <div class="qa-field">
                <label class="qa-label">容器内媒体目录</label>
                <FaInput v-model="mediaSettingsDraft.containerDirectory" class="w-full" placeholder="/media" />
              </div>
            </div>
          </div>
        </section>
      </div>

      <section class="qa-card">
        <div class="qa-card-head">
          <div>
            <h2 class="qa-title">连接默认策略</h2>
            <p class="qa-desc">未覆盖的群级字段会使用这里的配置。</p>
          </div>
          <FaButton size="sm" :loading="savingDefault" :disabled="!connectionId || loading" @click="saveDefault">
            <FaIcon name="i-lucide:save" />
            保存默认策略
          </FaButton>
        </div>
        <div v-if="connectionId" class="qa-card-body">
          <PolicyFieldsForm
            :base-policy="defaultPolicy"
            :override="defaultDraft"
            :ai-providers="aiProviders"
            :allow-inheritance="false"
            :user-fetcher="fetchUserOptions"
            :user-labels="userLabelCache"
            :group-options="groupOptions"
            @update:override="defaultDraft = $event"
          />
        </div>
        <div v-else class="qa-empty">选择一个 QQ 连接后配置策略。</div>
      </section>

      <section class="qa-card">
        <div class="qa-card-head">
          <div>
            <h2 class="qa-title">群级覆盖</h2>
            <p class="qa-desc">只保存需要差异化的字段；删除覆盖后立即恢复继承。</p>
          </div>
          <FaButton size="sm" :disabled="!connectionId || loading" @click="openCreate">
            <FaIcon name="i-lucide:plus" />
            新增群级覆盖
          </FaButton>
        </div>

        <div class="qa-card-body">
          <FaResponsiveTable
            v-loading="loading"
            row-key="channelId"
            table-root-class="overflow-hidden rounded-lg"
            border
            stripe
            column-visibility
            :columns="columns"
            :data="overrides"
            empty-text="当前连接还没有群级覆盖"
          >
            <template #cell-group="{ row }"><span class="font-medium">{{ groupName(row.original.channelId) }}</span></template>
            <template #cell-enabled="{ row }"><FaTag :variant="policyState(row.original).variant">{{ policyState(row.original).label }}</FaTag></template>
            <template #cell-media="{ row }">{{ mediaState(row.original) }}</template>
            <template #cell-risk="{ row }">{{ riskState(row.original) }}</template>
            <template #cell-overrides="{ row }">{{ countOverrides(row.original) }} 项</template>
            <template #cell-operation="{ row }">
              <div class="flex-center gap-2">
                <FaButton size="sm" variant="outline" @click="openEdit(row.original)">编辑</FaButton>
                <FaButton size="sm" variant="destructive" @click="confirmDelete(row.original)">删除</FaButton>
              </div>
            </template>
            <template #card="{ row }">
              <FaCard class="w-full">
                <div class="flex flex-col gap-3">
                  <div class="flex items-center justify-between gap-2">
                    <span class="min-w-0 break-words text-base font-semibold">{{ groupName(row.channelId) }}</span>
                    <div class="flex gap-1">
                      <FaTag :variant="policyState(row).variant">{{ policyState(row).label }}</FaTag>
                    </div>
                  </div>
                  <div class="flex flex-col gap-1 text-sm">
                    <div class="flex gap-2">
                      <span class="shrink-0 text-secondary-foreground/60">群聊 ID</span>
                      <span class="break-all">{{ row.channelId }}</span>
                    </div>
                    <div class="flex gap-2">
                      <span class="shrink-0 text-secondary-foreground/60">媒体解析</span>
                      <span>{{ mediaState(row) }}</span>
                    </div>
                    <div class="flex gap-2">
                      <span class="shrink-0 text-secondary-foreground/60">风险监测</span>
                      <span>{{ riskState(row) }}</span>
                    </div>
                    <div class="flex gap-2">
                      <span class="shrink-0 text-secondary-foreground/60">覆盖字段</span>
                      <span>{{ countOverrides(row) }} 项</span>
                    </div>
                  </div>
                  <div class="flex flex-wrap gap-2 border-t pt-3">
                    <FaButton size="sm" variant="outline" @click="openEdit(row)">编辑</FaButton>
                    <FaButton size="sm" variant="destructive" @click="confirmDelete(row)">删除</FaButton>
                  </div>
                </div>
              </FaCard>
            </template>
          </FaResponsiveTable>

          <FaPagination
            v-if="connectionId"
            v-model:page="page"
            v-model:size="size"
            :total="total"
            class="mt-3"
          />
        </div>
      </section>
      </div>
    </FaPageMain>

    <FaDrawer
      v-model="editorOpen"
      :title="editorTitle"
      description="只启用需要差异化的字段，其余字段继续继承连接默认策略。"
      :show-cancel-button="true"
      cancel-button-text="取消"
      confirm-button-text="保存覆盖"
      :confirm-button-loading="savingOverride"
      :confirm-button-disabled="!editorChannelId"
      content-class="qqbot-automation-drawer"
      @confirm="saveOverride"
      @cancel="closeEditor"
      @close="closeEditor"
    >
      <div class="space-y-5">
        <section v-if="!editorChannelId" class="space-y-3 rounded-xl border p-4">
          <label class="text-sm font-medium">群聊</label>
          <FaSelect v-model="editorChannelId" class="w-full" placeholder="选择需要单独配置的群聊" :options="groupOptions" />
          <FaInput v-model="extraGroupId" class="w-full" placeholder="官方群 openid，回车添加" @keydown.enter.prevent="addExtraGroup" />
          <p class="text-xs text-muted-foreground">官方 QQ 没有历史群列表，选项来自本进程收到过的群消息；已保存的群会保留，也可粘贴群 openid。</p>
        </section>
        <section v-else class="flex items-center gap-3 rounded-xl border bg-muted/30 px-4 py-3">
          <FaIcon name="i-lucide:messages-square" class="text-primary" />
          <div>
            <p class="text-xs text-muted-foreground">当前群聊</p>
            <p class="mt-0.5 font-medium">{{ groupName(editorChannelId) }}</p>
          </div>
        </section>
        <PolicyFieldsForm
          :base-policy="defaultPolicy"
          :override="editorDraft"
          :ai-providers="aiProviders"
          :user-fetcher="fetchUserOptions"
          :user-labels="userLabelCache"
          :group-options="groupOptions"
          @update:override="editorDraft = $event"
        />
      </div>
    </FaDrawer>
  </section>
</template>
