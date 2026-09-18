<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { ServerView } from '../types'
import {
  FaButton,
  FaCard,
  FaIcon,
  FaInput,
  FaModal,
  FaPageHeader,
  FaPageMain,
  FaSelect,
  FaTable,
  FaTag,
  useFaToast,
} from '@yudream/components'
import type { TableColumn } from '@yudream/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useYmclAdapter } from '../composables/useYmclAdapter'
import { formatTime } from '../composables/ymcl-protocol'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useYmclAdapter(props.sdk)
const toast = useFaToast()

const editOpen = ref(false)
const editingServer = ref<ServerView | null>(null)
const bindingForm = reactive({ packId: '', mcVersion: '', channel: 'stable', pinnedVersion: '', updatePolicy: 'prompt' })

const UPDATE_POLICIES = [
  { label: '提示确认（prompt）', value: 'prompt' },
  { label: '后台静默（background）', value: 'background' },
  { label: '启动时应用（launch-only）', value: 'launch-only' },
]
const CHANNELS = ['stable', 'beta', 'nightly']

const serverColumns: TableColumn<ServerView>[] = [
  { id: 'name', header: '服务器', minWidth: 160 },
  { id: 'mcAddress', header: '地址', minWidth: 180 },
  { id: 'status', header: '状态', width: 100 },
  { id: 'binding', header: '当前绑定', minWidth: 200 },
  { id: 'updatedAt', header: '绑定更新时间', width: 170 },
  { id: 'operations', header: '操作', width: 120, fixed: 'right' },
]

function serverName(row: ServerView) {
  return row.name || row.serverId || '（未命名）'
}

function statusLabel(row: ServerView) {
  const status = row.status
  if (!status) {
    return '未知'
  }
  if (typeof status === 'string') {
    return status
  }
  const online = (status as { online?: unknown }).online
  return online === true ? '在线' : online === false ? '离线' : '未知'
}

function statusOnline(row: ServerView) {
  const status = row.status
  if (typeof status === 'string') {
    return status === 'online'
  }
  return (status as { online?: unknown } | undefined)?.online === true
}

function bindingSummary(row: ServerView) {
  const binding = row.binding
  if (!binding) {
    return '未绑定'
  }
  if (!binding.packId) {
    return binding.mcVersion ? `纯服版本要求 ${binding.mcVersion}` : '未绑定'
  }
  const version = binding.pinnedVersion ? ` @ ${binding.pinnedVersion}` : ''
  return `${binding.packId}${version} · ${binding.channel || 'stable'} · ${binding.updatePolicy || 'prompt'}`
}

function serverId(row: ServerView) {
  return row.serverId || ''
}

const packOptions = computed(() => model.packs.map(pack => ({
  label: `${pack.packId}（${pack.versions?.length || 0} 个版本）`,
  value: pack.packId,
})))

const pinnedVersions = computed(() => {
  const pack = model.packs.find(item => item.packId === bindingForm.packId)
  return (pack?.versions || []).map(version => ({
    label: `${version.version}（${version.channel || 'stable'}）`,
    value: version.version,
  }))
})

watch(() => bindingForm.packId, () => {
  bindingForm.pinnedVersion = ''
})

function openEdit(row: ServerView) {
  if (!serverId(row)) {
    toast.error('该服务器缺少 serverId，无法绑定')
    return
  }
  editingServer.value = row
  bindingForm.packId = row.binding?.packId || ''
  bindingForm.mcVersion = row.binding?.mcVersion || ''
  bindingForm.channel = row.binding?.channel || 'stable'
  bindingForm.pinnedVersion = row.binding?.pinnedVersion || ''
  bindingForm.updatePolicy = row.binding?.updatePolicy || 'prompt'
  editOpen.value = true
}

async function submitBinding() {
  if (!editingServer.value) {
    return
  }
  const packId = bindingForm.packId
  const mcVersion = bindingForm.mcVersion.trim()
  if (!packId && !mcVersion) {
    toast.error('请选择整合包，或填写纯服版本要求')
    return
  }
  if (packId && mcVersion) {
    toast.error('整合包与版本要求二选一：整合包服请清空版本要求')
    return
  }
  // 整包绑定走 packId；纯服仅声明 mcVersion（文档整体覆盖，缺席字段即清除）。
  const payload: Record<string, unknown> = packId
    ? {
        packId,
        channel: bindingForm.channel,
        pinnedVersion: bindingForm.pinnedVersion || null,
        updatePolicy: bindingForm.updatePolicy,
      }
    : { mcVersion }
  const saved = await model.saveBinding(serverId(editingServer.value), payload as never)
  if (saved) {
    editOpen.value = false
  }
}

onMounted(() => {
  void model.loadServers()
  void model.loadPacks()
})
</script>

<template>
  <div class="ymcl-page">
    <FaPageHeader title="服务器绑定" description="MIP 附录 B 聚合视图：为启动器成员的服务器指定整合包与更新策略。" />

    <FaPageMain>
      <FaCard content-class="ymcl-card-content">
        <FaTable
          table-root-class="max-w-full overflow-x-auto rounded-lg overflow-hidden"
          v-loading="model.loading"
          :columns="serverColumns"
          :data="model.servers"
          row-key="serverId"
        >
          <template #empty>
            <span class="ymcl-muted">暂无服务器档案。需要已启用的插件（如 minecraft-server）经 YmclContributionProvider 贡献 serverBindings。</span>
          </template>
          <template #cell-name="{ row }">
            <div class="flex min-w-0 flex-col">
              <strong class="truncate">{{ serverName(row.original) }}</strong>
              <span v-if="row.original.serverId" class="ymcl-muted ymcl-break">{{ row.original.serverId }}</span>
            </div>
          </template>
          <template #cell-mcAddress="{ row }">
            <code v-if="row.original.mcAddress" class="ymcl-hash">{{ row.original.mcAddress }}</code>
            <span v-else>-</span>
          </template>
          <template #cell-status="{ row }">
            <FaTag :variant="statusOnline(row.original) ? 'default' : 'outline'">
              {{ statusLabel(row.original) }}
            </FaTag>
          </template>
          <template #cell-binding="{ row }">
            <span :class="row.original.binding?.packId ? '' : 'ymcl-muted'">{{ bindingSummary(row.original) }}</span>
          </template>
          <template #cell-updatedAt="{ row }">
            {{ formatTime(row.original.binding?.updatedAt) }}
          </template>
          <template #cell-operations="{ row }">
            <FaButton size="sm" variant="outline" @click="openEdit(row.original)">
              <FaIcon name="i-ri:link" />
              绑定
            </FaButton>
          </template>
        </FaTable>
      </FaCard>
    </FaPageMain>

    <FaModal v-model="editOpen" :title="`绑定整合包 · ${serverName(editingServer || {})}`">
      <div class="ymcl-binding-form">
        <div class="ymcl-form-row">
          <label class="ymcl-label">整合包</label>
          <FaSelect v-model="bindingForm.packId" :options="[{ label: '不绑定（纯服）', value: '' }, ...packOptions]" />
        </div>
        <div v-if="bindingForm.packId" class="ymcl-form-row">
          <label class="ymcl-label">渠道</label>
          <FaSelect v-model="bindingForm.channel" :options="CHANNELS.map(value => ({ label: value, value }))" />
        </div>
        <div v-if="bindingForm.packId" class="ymcl-form-row">
          <label class="ymcl-label">钉住版本</label>
          <FaSelect
            v-model="bindingForm.pinnedVersion"
            :options="[{ label: '跟随渠道最新', value: '' }, ...pinnedVersions]"
          />
        </div>
        <div v-if="bindingForm.packId" class="ymcl-form-row">
          <label class="ymcl-label">更新策略</label>
          <FaSelect v-model="bindingForm.updatePolicy" :options="UPDATE_POLICIES" />
        </div>
        <div v-if="!bindingForm.packId" class="ymcl-form-row">
          <label class="ymcl-label">版本要求</label>
          <FaInput v-model="bindingForm.mcVersion" placeholder="如 1.21.1（原版/纯净服）" />
        </div>
        <p class="ymcl-muted">
          绑定整合包 = 成员进服自动备齐整合包（版本不可变，钉住后固定该版本）；
          留空整合包并填写版本要求 = 纯服语义，玩家可用同版本本地实例或下载纯净原版进服。
        </p>
      </div>
      <template #footer>
        <div class="ymcl-action-row ymcl-action-row--end">
          <FaButton variant="outline" @click="editOpen = false">
            取消
          </FaButton>
          <FaButton :loading="model.saving" @click="submitBinding">
            保存绑定
          </FaButton>
        </div>
      </template>
    </FaModal>
  </div>
</template>
