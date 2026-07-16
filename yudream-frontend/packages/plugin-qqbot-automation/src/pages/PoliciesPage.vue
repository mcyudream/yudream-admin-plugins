<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  FaAlert,
  FaButton,
  FaDrawer,
  FaIcon,
  FaPageHeader,
  FaPageMain,
  FaPagination,
  FaSelect,
  FaTable,
  FaTag,
  useFaModal,
  useFaToast,
  type TableColumn,
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
} from '../types'

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
const editorDraft = ref<AutomationPolicyOverride>(emptyOverride())

const connectionOptions = computed(() => connections.value.map(item => ({ label: item.name, value: item.id })))
const groupOptions = computed(() => groups.value.map(item => ({ label: item.name, value: item.id })))
const editorTitle = computed(() => editorChannelId.value ? `群级覆盖：${groupName(editorChannelId.value)}` : '新增群级覆盖')

const columns: TableColumn<AutomationPolicyOverride>[] = [
  { id: 'group', header: '群聊', width: 220, fixed: 'left' },
  { id: 'enabled', header: '策略状态', width: 130, align: 'center' },
  { id: 'overrides', header: '覆盖字段', width: 120, align: 'center' },
  { id: 'media', header: '媒体解析', width: 130, align: 'center' },
  { id: 'operation', header: '操作', width: 180, fixed: 'right', align: 'center' },
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
  editorDraft.value = emptyOverride(connectionId.value)
  editorOpen.value = true
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
    const [connectionOptions, providers] = await Promise.all([api.connections(), api.aiOptions()])
    connections.value = connectionOptions
    aiProviders.value = providers
  }
  catch (cause) {
    showError(cause, '加载 QQ 连接失败')
  }
})
</script>

<template>
  <section>
    <FaPageHeader title="群自动化策略" description="配置连接默认策略，并按需为单独群聊覆盖指定字段。" />

    <FaPageMain class="space-y-4">
      <FaAlert v-if="error" variant="destructive" title="操作未完成" :description="error" />

      <section class="space-y-4 rounded-lg border p-4">
        <div class="flex flex-wrap items-end justify-between gap-3">
          <div class="space-y-2">
            <label class="text-sm font-medium">QQ 连接</label>
            <FaSelect
              class="w-72"
              placeholder="选择连接"
              :model-value="connectionId"
              :options="connectionOptions"
              :disabled="loading"
              @update:model-value="changeConnection"
            />
          </div>
          <FaButton variant="outline" :disabled="!connectionId || loading" @click="loadCurrentConnection">
            <FaIcon name="i-lucide:refresh-cw" />
            刷新
          </FaButton>
        </div>

        <template v-if="connectionId">
          <div class="border-t pt-4">
            <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
              <div>
                <h2 class="text-base font-semibold">连接默认策略</h2>
                <p class="mt-1 text-sm text-muted-foreground">未覆盖的群级字段会使用这里的配置。</p>
              </div>
              <FaButton :loading="savingDefault" :disabled="loading" @click="saveDefault">保存默认策略</FaButton>
            </div>
            <PolicyFieldsForm :base-policy="defaultPolicy" :override="defaultDraft" :ai-providers="aiProviders" :allow-inheritance="false" @update:override="defaultDraft = $event" />
          </div>
        </template>

        <div v-else class="py-8 text-center text-sm text-muted-foreground">选择一个 QQ 连接后配置策略。</div>
      </section>

      <section class="space-y-3">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 class="text-lg font-semibold">群级覆盖</h2>
            <p class="mt-1 text-sm text-muted-foreground">只保存需要差异化的字段；删除覆盖后立即恢复继承。</p>
          </div>
          <FaButton :disabled="!connectionId || loading" @click="openCreate">
            <FaIcon name="i-lucide:plus" />
            新增群级覆盖
          </FaButton>
        </div>

        <FaTable
          v-loading="loading"
          row-key="channelId"
          table-root-class="overflow-hidden rounded-lg"
          table-class="min-w-[800px]"
          border
          stripe
          column-visibility
          :columns="columns"
          :data="overrides"
          empty-text="当前连接还没有群级覆盖"
        >
          <template #cell-group="{ row }"><span class="font-medium">{{ groupName(row.original.channelId) }}</span></template>
          <template #cell-enabled="{ row }"><FaTag :variant="policyState(row.original).variant">{{ policyState(row.original).label }}</FaTag></template>
          <template #cell-overrides="{ row }">{{ countOverrides(row.original) }} 项</template>
          <template #cell-media="{ row }">{{ mediaState(row.original) }}</template>
          <template #cell-operation="{ row }">
            <div class="flex-center gap-2">
              <FaButton size="sm" variant="outline" @click="openEdit(row.original)">编辑</FaButton>
              <FaButton size="sm" variant="destructive" @click="confirmDelete(row.original)">删除</FaButton>
            </div>
          </template>
        </FaTable>

        <FaPagination
          v-if="connectionId"
          v-model:page="page"
          v-model:size="size"
          :total="total"
          class="mt-3"
        />
      </section>
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
      content-class="w-[min(760px,100vw)]"
      @confirm="saveOverride"
      @cancel="closeEditor"
      @close="closeEditor"
    >
      <div class="space-y-6">
        <section v-if="!editorChannelId" class="space-y-2">
          <label class="text-sm font-medium">群聊</label>
          <FaSelect v-model="editorChannelId" class="w-full" placeholder="选择需要单独配置的群聊" :options="groupOptions" />
        </section>
        <section v-else class="border-b pb-4">
          <p class="text-sm text-muted-foreground">当前群聊</p>
          <p class="mt-1 font-medium">{{ groupName(editorChannelId) }}</p>
        </section>
        <PolicyFieldsForm :base-policy="defaultPolicy" :override="editorDraft" :ai-providers="aiProviders" @update:override="editorDraft = $event" />
      </div>
    </FaDrawer>
  </section>
</template>
