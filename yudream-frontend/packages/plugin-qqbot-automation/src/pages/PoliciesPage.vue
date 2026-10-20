<script setup lang="ts">
import {
  YdAlert,
  YdButton,
  YdDrawer,
  YdIcon,
  YdInput,
  YdLoadingOverlay,
  YdPageHeader,
  YdPagination,
  YdResponsiveEnhancedTable,
  YdSelect,
  YdSettingsLayout,
  YdSettingsSection,
  YdTag,
  useYdModal,
  useYdToast,
  type ActionMenuItem,
  type StatusColumn,
  type TableColumn,
  type YdTablePickerQuery,
  type YdTablePickerResult,
} from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed, onMounted, ref, watch } from 'vue'
import { createQqbotAutomationApi } from '../api/qqbot-automation-api'
import PolicyFieldsForm from '../components/PolicyFieldsForm.vue'
import {
  POLICY_OVERRIDE_FIELDS,
  completeOverride,
  emptyOverride,
  emptyPolicy,
  formatGroupLabel,
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
const toast = useYdToast()
const modal = useYdModal()

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
const identifyChannelId = ref('')
const editorDraft = ref<AutomationPolicyOverride>(emptyOverride())
const mediaSettingsDraft = ref({ hostDirectory: '/media', containerDirectory: '/app/download' })
const savingMediaSettings = ref(false)
const userLabelCache = ref<Record<string, string>>({})
const aliasDraft = ref('')
const savingAlias = ref(false)
const identifyingGroupId = ref('')

/** 设置页分区：页头动作随激活分区切换 */
const activeSection = ref('connection')
const sectionList: { icon?: string, label: string, value: string }[] = [
  { icon: 'i-ri:links-line', label: 'QQ 连接', value: 'connection' },
  { icon: 'i-ri:shield-keyhole-line', label: '连接默认策略', value: 'default' },
  { icon: 'i-ri:team-line', label: '群级覆盖', value: 'overrides' },
  { icon: 'i-ri:folder-3-line', label: '媒体存储', value: 'media' },
]

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
  const known = groups.value.map(item => ({ label: formatGroupLabel(item), value: item.id }))
  const knownIds = new Set(known.map(item => item.value))
  const extras = [editorChannelId.value, ...overrides.value.map(item => item.channelId)]
    .filter(id => id && !knownIds.has(id))
    .map(id => ({ label: formatGroupLabel({ id }), value: id }))
  return [...known, ...extras]
})
const editorTitle = computed(() => editorChannelId.value ? `群级覆盖：${groupName(editorChannelId.value)}` : '新增群级覆盖')

const columns: TableColumn<AutomationPolicyOverride>[] = [
  { id: 'group', header: '群聊', width: 200 },
  { id: 'media', header: '媒体解析', width: 110 },
  { id: 'risk', header: '风险监测', width: 110 },
  { id: 'overrides', header: '覆盖字段', width: 110 },
]

/** 策略状态：语义色胶囊 */
const policyStatusColumn: StatusColumn<AutomationPolicyOverride> = {
  statusKey: 'enabled',
  header: '策略状态',
  width: 120,
  statusMap: {
    'true': { label: '已启用', variant: 'default', tone: 'success' },
    'false': { label: '已关闭', variant: 'destructive', tone: 'danger' },
    '': { label: '继承默认', variant: 'secondary', tone: 'neutral' },
  },
}

/** 行操作统一收进操作菜单，删除保持确认流程 */
const rowActions: ActionMenuItem<AutomationPolicyOverride>[] = [
  { label: '编辑', icon: 'morph:pencil', onClick: row => openEdit(row) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => confirmDelete(row) },
]

function groupName(channelId: string) {
  const group = groups.value.find(item => item.id === channelId)
  return group ? formatGroupLabel(group) : formatGroupLabel({ id: channelId })
}

function upsertGroup(row: Option) {
  const next = { ...row, id: row.id, name: row.name || row.alias || row.sourceName || row.id }
  const index = groups.value.findIndex(item => item.id === next.id)
  if (index >= 0) {
    groups.value[index] = { ...groups.value[index], ...next }
  }
  else {
    groups.value = [...groups.value, next]
  }
}

async function saveAlias() {
  const channelId = identifyChannelId.value.trim()
  if (!connectionId.value || !channelId) {
    toast.error('请先选择要备注的群')
    return
  }
  savingAlias.value = true
  try {
    const saved = await api.saveGroupAlias({ connectionId: connectionId.value, channelId, alias: aliasDraft.value.trim() })
    upsertGroup(saved)
    toast.success(saved.alias ? '群备注已保存' : '群备注已清除')
  }
  catch (cause) {
    showError(cause, '保存群备注失败')
  }
  finally {
    savingAlias.value = false
  }
}

async function identifyGroup(channelId: string) {
  if (!connectionId.value || !channelId) return
  identifyingGroupId.value = channelId
  try {
    await api.identifyGroup({ connectionId: connectionId.value, channelId })
    toast.success('已向该群发送识别消息，打开对应 QQ 群即可确认')
  }
  catch (cause) {
    showError(cause, '发送识别消息失败')
  }
  finally {
    identifyingGroupId.value = ''
  }
}

function countOverrides(override: AutomationPolicyOverride) {
  return POLICY_OVERRIDE_FIELDS.filter(field => override[field] !== null).length
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
  if (!groups.value.some(item => item.id === id)) {
    upsertGroup({ id, name: id })
  }
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
    mediaSettingsDraft.value = { hostDirectory: saved.hostDirectory ?? '', containerDirectory: saved.containerDirectory || '/app/download' }
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
  identifyChannelId.value = ''
  aliasDraft.value = ''
  extraGroupId.value = ''
  defaultPolicy.value = emptyPolicy(connectionId.value)
  defaultDraft.value = completeOverride(defaultPolicy.value)
  if (connectionId.value) void loadCurrentConnection()
})

watch(identifyChannelId, (channelId) => {
  const group = groups.value.find(item => item.id === channelId)
  aliasDraft.value = group?.alias || ''
})

watch([page, size], () => {
  if (connectionId.value) void loadOverrides()
})

onMounted(async () => {
  try {
    const [connectionOptions, providers, settings] = await Promise.all([api.connections(), api.aiOptions(), api.mediaSettings()])
    connections.value = connectionOptions
    aiProviders.value = providers
    mediaSettingsDraft.value = { hostDirectory: settings.hostDirectory ?? '', containerDirectory: settings.containerDirectory || '/app/download' }
  }
  catch (cause) {
    showError(cause, '加载 QQ 连接失败')
  }
})
</script>

<template>
  <div class="settings-page-shell">
    <YdPageHeader
      title="群自动化策略"
      description="按分区维护 QQ 连接、连接默认策略、群级覆盖与媒体存储。"
      class="mb-0"
    >
      <YdButton v-if="activeSection === 'connection'" variant="outline" :disabled="!connectionId || loading" @click="loadCurrentConnection">
        <YdIcon name="i-lucide:refresh-cw" />
        刷新
      </YdButton>
      <YdButton v-else-if="activeSection === 'default'" :loading="savingDefault" :disabled="!connectionId || loading" @click="saveDefault">
        <YdIcon name="i-lucide:save" />
        保存默认策略
      </YdButton>
      <YdButton v-else-if="activeSection === 'overrides'" :disabled="!connectionId || loading" @click="openCreate">
        <YdIcon name="i-lucide:plus" />
        新增群级覆盖
      </YdButton>
      <YdButton v-else :loading="savingMediaSettings" @click="saveMediaSettings">保存</YdButton>
    </YdPageHeader>

    <YdAlert v-if="error" variant="destructive" title="操作未完成" :description="error" class="mx-5 mt-4" />

    <YdSettingsLayout
      v-model="activeSection"
      :list="sectionList"
      surface
      scroll
      width-class="max-w-[880px]"
    >
      <template #connection>
        <YdSettingsSection title="QQ 连接" description="切换连接后加载该连接的默认策略与群级覆盖。">
          <div class="qa-select-row">
            <YdSelect
              placeholder="选择连接"
              :model-value="connectionId"
              :options="connectionOptions"
              :disabled="loading"
              @update:model-value="changeConnection"
            />
          </div>
          <div v-if="connectionId && currentConnection?.protocol === 'official'" class="qa-group-identify">
            <p class="qa-label">官方群识别</p>
            <p class="qa-desc">官方接口通常不给群名。点「发识别消息」后去 QQ 里看哪个群收到了，再给它写备注。</p>
            <div class="qa-fields">
              <div class="qa-field">
                <label class="qa-label">群 openid</label>
                <YdSelect
                  class="w-full"
                  placeholder="选择要识别或备注的群"
                  :model-value="identifyChannelId"
                  :options="groupOptions"
                  @update:model-value="identifyChannelId = String($event ?? '')"
                />
              </div>
              <div class="qa-field">
                <label class="qa-label">后台备注</label>
                <YdInput v-model="aliasDraft" class="w-full" placeholder="如 运营群 / 告警群" maxlength="40" />
              </div>
            </div>
            <div class="qa-actions">
              <YdButton size="sm" variant="outline" :disabled="!identifyChannelId" :loading="identifyingGroupId === identifyChannelId" @click="identifyGroup(identifyChannelId)">发识别消息</YdButton>
              <YdButton size="sm" :disabled="!identifyChannelId" :loading="savingAlias" @click="saveAlias">保存备注</YdButton>
            </div>
          </div>
          <div v-if="currentConnection" class="qa-hint">
            <YdTag :variant="protocolVariant(currentConnection)">{{ protocolLabel(currentConnection) }}</YdTag>
            <span v-if="currentConnection.protocol === 'official'">官方连接：媒体签发公网直链发送；入群审批走官方申请事件（机器人须为群管理员），告警发到单独指定群。官方群选择器默认只有 openid，可用上方「识别」或「备注」区分。</span>
            <span v-else>Milky 连接：媒体经共享目录落盘，支持合并转发与语音。</span>
          </div>
        </YdSettingsSection>
      </template>

      <template #default>
        <YdSettingsSection title="连接默认策略" description="未覆盖的群级字段会使用这里的配置。">
          <PolicyFieldsForm
            v-if="connectionId"
            :base-policy="defaultPolicy"
            :override="defaultDraft"
            :ai-providers="aiProviders"
            :allow-inheritance="false"
            :user-fetcher="fetchUserOptions"
            :user-labels="userLabelCache"
            :group-options="groupOptions"
            @update:override="defaultDraft = $event"
          />
          <div v-else class="qa-empty">选择一个 QQ 连接后配置策略。</div>
        </YdSettingsSection>
      </template>

      <template #overrides>
        <YdSettingsSection title="群级覆盖" description="只保存需要差异化的字段；删除覆盖后立即恢复继承。">
          <YdLoadingOverlay :loading="loading">
            <YdResponsiveEnhancedTable
              row-key="channelId"
              table-root-class="rounded-lg overflow-hidden"
              table-class="min-w-[720px]"
              border
              stripe
              column-visibility
              :status-column="policyStatusColumn"
              :actions="rowActions"
              :columns="columns"
              :data="overrides"
              empty-text="当前连接还没有群级覆盖"
            >
              <template #cell-group="{ row }"><span class="font-medium">{{ groupName(row.original.channelId) }}</span></template>
              <template #cell-media="{ row }">
                <span class="yd-status-tone" :data-tone="row.original.mediaEnabled === null ? 'neutral' : row.original.mediaEnabled ? 'success' : 'warning'">{{ mediaState(row.original) }}</span>
              </template>
              <template #cell-risk="{ row }">
                <span class="yd-status-tone" :data-tone="row.original.riskMonitorEnabled === null ? 'neutral' : row.original.riskMonitorEnabled ? 'success' : 'warning'">{{ riskState(row.original) }}</span>
              </template>
              <template #cell-overrides="{ row }">{{ countOverrides(row.original) }} 项</template>
            </YdResponsiveEnhancedTable>
          </YdLoadingOverlay>
          <YdPagination
            v-if="connectionId"
            v-model:page="page"
            v-model:size="size"
            :total="total"
          />
        </YdSettingsSection>
      </template>

      <template #media>
        <YdSettingsSection title="媒体存储" description="这里填的是 YuDream 后端容器能看到的路径，不是宿主机路径。官方连接签发公网直链时，后端进程必须能读到这份目录。">
          <div class="qa-fields">
            <div class="qa-field">
              <label class="qa-label">后端可读目录</label>
              <YdInput v-model="mediaSettingsDraft.hostDirectory" class="w-full" placeholder="/media" clearable />
            </div>
            <div class="qa-field">
              <label class="qa-label">解析服务容器目录</label>
              <YdInput v-model="mediaSettingsDraft.containerDirectory" class="w-full" placeholder="/app/download" />
            </div>
          </div>
          <p class="qa-hint">当前生产 compose 把宿主机 /opt/yudream-douyin-api/download 挂进后端 /media，解析服务自己挂到 /app/download。所以这里填 /media 和 /app/download，不要填宿主机 /opt/... 路径。</p>
        </YdSettingsSection>
      </template>
    </YdSettingsLayout>

    <YdDrawer
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
          <YdSelect v-model="editorChannelId" class="w-full" placeholder="选择需要单独配置的群聊" :options="groupOptions" />
          <YdInput v-model="extraGroupId" class="w-full" placeholder="官方群 openid，回车添加" @keydown.enter.prevent="addExtraGroup" />
          <p class="text-xs text-muted-foreground">官方 QQ 没有历史群列表，选项来自本进程收到过的群消息。无法区分时先发识别消息，再给对应群写备注。</p>
        </section>
        <section v-else class="flex items-center gap-3 rounded-xl border bg-muted/30 px-4 py-3">
          <YdIcon name="i-lucide:messages-square" class="text-primary" />
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
    </YdDrawer>
  </div>
</template>
