<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { McpProxyDetectResult, McpProxyGroup, McpProxyServerRow } from '../types'
import { FaAlert, FaButton, FaCard, FaIcon, FaSelect, FaTable, FaTag, useFaModal, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { createMcPanelExtra } from '../api/api-extra'
import { errorMessage } from '../composables/utils'

/**
 * 代理纳管面板（挂在「代理与子服」路由页内）：
 * - 识别：读节点根目录 velocity.toml / config.yml，解析 servers 段并自动匹配面板实例；
 * - 纳管：确认每条子服绑定（可改为外部条目）后保存；
 * - 管理：查看绑定与子服状态、重新识别（手改配置后刷新模型）、解除纳管。
 * 只读写面板侧关系文档，不修改代理配置文件本身。
 */
const props = defineProps<{
  sdk: YuDreamPluginSdk
  instanceId: string
  instanceKind: string
  canSave: boolean
}>()

const extra = createMcPanelExtra(props.sdk)
const toast = useFaToast()
const modal = useFaModal()

const loading = ref(false)
const detecting = ref(false)
const saving = ref(false)
const group = ref<McpProxyGroup | null>(null)
const draftRows = ref<McpProxyServerRow[]>([])
const draftMeta = reactive({ kind: '', forwarding: '', defaultServer: '', sourcePath: '' })
const detectNote = ref('')
const EXTERNAL_VALUE = '__external__'

const isProxyKind = computed(() => props.instanceKind === 'velocity' || props.instanceKind === 'bungee')
const kindLabel = computed(() => (draftMeta.kind || String(group.value?.kind ?? props.instanceKind) || '').toLowerCase() === 'bungee' ? 'BungeeCord' : 'Velocity')

const instanceOptions = ref<Array<{ label: string, value: string }>>([])

const MATCH_LABEL: Record<string, { text: string, variant: 'default' | 'secondary' | 'outline' }> = {
  address: { text: '地址匹配', variant: 'default' },
  name: { text: '同名建议', variant: 'secondary' },
  carried: { text: '已确认', variant: 'default' },
  none: { text: '外部', variant: 'outline' },
}

const columns: TableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'name', header: '子服名', minWidth: 120 },
  { accessorKey: 'address', header: '地址', minWidth: 170 },
  { id: 'binding', header: '绑定面板实例', minWidth: 220 },
  { id: 'match', header: '来源', width: 110, align: 'center' },
  { id: 'operation', header: '操作', width: 110, align: 'center' },
]

/** FaTable 单元格槽的 row.original 是宽松 Record；草稿行在此收紧类型。 */
function asRow(original: Record<string, unknown>): McpProxyServerRow {
  return original as unknown as McpProxyServerRow
}

function bindValue(row: McpProxyServerRow): string {
  return row.boundInstanceId || row.matchedInstanceId || (row.matchType && row.matchType !== 'none' ? '' : EXTERNAL_VALUE)
}

const bindOptions = computed(() => [
  { label: '外部子服（不绑定实例）', value: EXTERNAL_VALUE },
  ...instanceOptions.value,
])

function onBind(row: McpProxyServerRow, value: unknown) {
  const picked = String(value ?? '')
  row.boundInstanceId = picked === EXTERNAL_VALUE ? undefined : picked
  if (!picked || picked === EXTERNAL_VALUE) {
    row.matchedInstanceId = undefined
    row.matchType = 'none'
  }
}

async function loadGroup() {
  loading.value = true
  try {
    const result = await extra.proxyGroup(props.instanceId) as { exists?: boolean } & McpProxyGroup
    group.value = result && result.exists !== false ? result : null
    if (group.value) {
      draftMeta.kind = String(group.value.kind ?? '')
      draftMeta.forwarding = String(group.value.forwarding ?? '')
      draftMeta.defaultServer = String(group.value.defaultServer ?? '')
      draftMeta.sourcePath = String(group.value.sourcePath ?? '')
    }
  }
  catch {
    group.value = null
  }
  finally {
    loading.value = false
  }
}

async function loadInstanceOptions() {
  try {
    const page = await extra.pageInstances(1, 200) as {
      records?: Array<{ id?: string, name?: string, kind?: string }>
    }
    instanceOptions.value = (page.records ?? [])
      .filter(item => item.id && item.id !== props.instanceId)
      .map(item => ({
        label: `${item.name}（${item.kind ?? '-'}）`,
        value: String(item.id),
      }))
  }
  catch {
    instanceOptions.value = []
  }
}

async function detect() {
  if (detecting.value) {
    return
  }
  detecting.value = true
  detectNote.value = ''
  try {
    const result = await extra.detectProxyGroup(props.instanceId) as McpProxyDetectResult
    if (!result?.detected) {
      detectNote.value = result?.reason || '未识别到代理配置'
      toast.error(detectNote.value)
      return
    }
    draftMeta.kind = String(result.kind ?? '')
    draftMeta.forwarding = String(result.forwarding ?? '')
    draftMeta.defaultServer = String(result.defaultServer ?? '')
    draftMeta.sourcePath = String(result.sourcePath ?? '')
    draftRows.value = (result.servers ?? []).map(server => ({ ...server }))
    toast.success(`识别到 ${kindLabel.value} 配置：${draftRows.value.length} 个子服条目`)
  }
  catch (error) {
    toast.error(errorMessage(error, '识别失败（节点可能离线或配置文件不存在）'))
  }
  finally {
    detecting.value = false
  }
}

function buildPayload(servers: McpProxyServerRow[]) {
  return {
    kind: draftMeta.kind,
    forwarding: draftMeta.forwarding,
    defaultServer: draftMeta.defaultServer,
    servers: servers.map(server => ({
      name: server.name,
      address: server.address,
      boundInstanceId: server.boundInstanceId || '',
    })),
  }
}

async function saveDraft() {
  if (saving.value || !draftRows.value.length) {
    return
  }
  saving.value = true
  try {
    await extra.saveProxyGroup(props.instanceId, buildPayload(draftRows.value))
    toast.success('代理纳管已保存')
    draftRows.value = []
    await loadGroup()
  }
  catch (error) {
    toast.error(errorMessage(error, '保存失败'))
  }
  finally {
    saving.value = false
  }
}

function cancelDraft() {
  draftRows.value = []
}

/** 已纳管行的快捷解绑：整组重存（该行转为外部条目）。 */
function unbindRow(row: Record<string, unknown>) {
  const name = String(row.name ?? '')
  modal.confirm({
    title: '解除子服绑定',
    content: `确认把「${name}」改为外部子服（仅保留记录，不再关联面板实例）？`,
    confirmButtonText: '解除绑定',
    onConfirm: async () => {
      if (!group.value || saving.value) {
        return
      }
      saving.value = true
      try {
        const servers = group.value.servers.map(server => ({
          ...server,
          boundInstanceId: server.name === name ? '' : (server.boundInstanceId ?? ''),
        }))
        await extra.saveProxyGroup(props.instanceId, buildPayload(servers))
        toast.success('已解除绑定')
        await loadGroup()
      }
      catch (error) {
        toast.error(errorMessage(error, '解除失败'))
      }
      finally {
        saving.value = false
      }
    },
  })
}

function unmanage() {
  modal.confirm({
    title: '解除代理纳管',
    content: '确认解除纳管？子服绑定关系会被删除（代理配置文件不受影响，可随时重新识别）。',
    confirmButtonText: '解除纳管',
    onConfirm: async () => {
      saving.value = true
      try {
        await extra.deleteProxyGroup(props.instanceId)
        toast.success('已解除纳管')
        group.value = null
        draftRows.value = []
      }
      catch (error) {
        toast.error(errorMessage(error, '解除失败'))
      }
      finally {
        saving.value = false
      }
    },
  })
}

onMounted(() => {
  void loadGroup()
  void loadInstanceOptions()
})
</script>

<template>
  <FaCard
    title="代理与子服"
    :description="group
      ? `${kindLabel} 代理已纳管：${group.servers.length} 个子服条目（默认 ${group.defaultServer || '-'}，转发 ${group.forwarding || '-'}）`
      : '识别实例根目录的代理配置（velocity.toml / config.yml），把注册的子服自动绑定到面板实例'"
  >
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <FaTag v-if="group" variant="default">
        {{ kindLabel }} · 已纳管
      </FaTag>
      <FaTag v-else-if="isProxyKind" variant="secondary">
        {{ kindLabel }} · 未纳管
      </FaTag>
      <span v-if="draftMeta.sourcePath" class="text-xs text-muted-foreground">
        配置文件：<code class="rounded bg-muted px-1 py-0.5 font-mono">{{ draftMeta.sourcePath }}</code>
      </span>
      <span v-if="detectNote" class="text-xs text-amber-600 dark:text-amber-400">
        {{ detectNote }}
      </span>
      <span class="flex-1" />
      <FaButton v-if="canSave" size="sm" variant="outline" :loading="detecting" @click="detect">
        <FaIcon name="i-ri:scan-2-line" />
        {{ group ? '重新识别' : '识别代理配置' }}
      </FaButton>
      <FaButton v-if="group && canSave" size="sm" variant="outline" @click="loadGroup">
        刷新
      </FaButton>
      <FaButton v-if="group && canSave" size="sm" variant="destructive" :disabled="saving" @click="unmanage">
        解除纳管
      </FaButton>
    </div>

    <!-- 已纳管视图 -->
    <FaTable
      v-if="group && !draftRows.length"
      v-loading="loading"
      :columns="columns"
      :data="group.servers as unknown as Record<string, unknown>[]"
      row-key="name"
      table-root-class="rounded-lg overflow-hidden"
      border
      stripe
      empty-text="暂无子服条目"
    >
      <template #cell-binding="{ row }">
        <template v-if="row.original.boundInstanceId">
          <router-link
            :to="`/platform/plugins/mcpanel/admin/instances/${encodeURIComponent(String(row.original.boundInstanceId))}`"
            class="font-medium text-primary hover:underline"
          >
            {{ row.original.boundName || row.original.boundInstanceId }}
          </router-link>
          <FaTag v-if="row.original.boundState" :variant="row.original.boundState === 'running' ? 'default' : 'secondary'" class="ml-2">
            {{ row.original.boundState === 'running' ? '运行中' : String(row.original.boundState) }}
          </FaTag>
        </template>
        <FaTag v-else variant="outline">
          外部子服
        </FaTag>
      </template>
      <template #cell-match="{ row }">
        <FaTag :variant="row.original.boundInstanceId ? 'default' : 'outline'">
          {{ row.original.boundInstanceId ? '已绑定' : '外部' }}
        </FaTag>
      </template>
      <template #cell-operation="{ row }">
        <FaButton
          v-if="canSave && row.original.boundInstanceId"
          size="sm"
          variant="outline"
          :disabled="saving"
          @click="unbindRow(row.original)"
        >
          解绑
        </FaButton>
      </template>
    </FaTable>

    <!-- 识别/编辑草稿视图 -->
    <template v-else-if="draftRows.length">
      <FaAlert variant="default" title="确认子服绑定" class="mb-3">
        <template #description>
          地址匹配与同名建议为自动结果，可逐行调整；「外部子服」仅保留记录。保存不会修改代理配置文件。
        </template>
      </FaAlert>
      <FaTable
        :columns="columns"
        :data="draftRows as unknown as Record<string, unknown>[]"
        row-key="name"
        table-root-class="rounded-lg overflow-hidden"
        border
        stripe
        empty-text="未解析到子服"
      >
        <template #cell-binding="{ row }">
          <FaSelect
            :model-value="bindValue(asRow(row.original))"
            :options="bindOptions"
            :disabled="!canSave"
            size="sm"
            class="w-full min-w-40"
            @update:model-value="(value: unknown) => onBind(asRow(row.original), value)"
          />
        </template>
        <template #cell-match="{ row }">
          <FaTag :variant="MATCH_LABEL[String(row.original.matchType ?? 'none')]?.variant ?? 'outline'">
            {{ MATCH_LABEL[String(row.original.matchType ?? 'none')]?.text ?? '外部' }}
          </FaTag>
        </template>
        <template #cell-operation>
          <span class="text-xs text-muted-foreground">-</span>
        </template>
      </FaTable>
      <div v-if="canSave" class="mt-3 flex items-center justify-end gap-2">
        <FaButton size="sm" variant="outline" :disabled="saving" @click="cancelDraft">
          取消
        </FaButton>
        <FaButton size="sm" :loading="saving" @click="saveDraft">
          <FaIcon name="i-ri:links-line" />
          保存纳管（{{ draftRows.length }} 条）
        </FaButton>
      </div>
    </template>

    <div v-else class="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
      <template v-if="loading">
        加载中…
      </template>
      <template v-else-if="detectNote">
        {{ detectNote }}
      </template>
      <template v-else>
        尚未纳管。点击「识别代理配置」读取实例根目录的 velocity.toml / config.yml。
        <template v-if="!isProxyKind">
          <br>实例类型不是 velocity/bungee 时也可识别（以文件特征为准）。
        </template>
      </template>
    </div>
  </FaCard>
</template>
