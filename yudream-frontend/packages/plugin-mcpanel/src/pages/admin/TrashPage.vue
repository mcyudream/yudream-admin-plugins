<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import { FaButton, FaCard, FaIcon, FaInput, FaModal, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaSearchBar, FaTag, FaTooltip, useFaModal, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { createMcPanelExtra } from '../../api/api-extra'
import { createNodeOpsApi } from '../../api/node-ops-api'
import { MCPANEL_PERMISSION, accountHasPermission } from '../../composables/permissions'
import { errorMessage, formatDateTime } from '../../composables/utils'
import { useChunkedDownload } from '../../composables/useChunkedDownload.ts'

/**
 * 回收站：实例删除后数据目录的软保护。保留期内可 找回（以快照规格重建实例）、
 * 打包下载、立即永久删除；超过节点保留期（trash.retentionDays）后节点自动清除。
 * 目录在位状态来自节点 trash.list 装饰：节点离线/版本过低时显示未知并禁用操作。
 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const extra = createMcPanelExtra(props.sdk)
const nodeOps = createNodeOpsApi(props.sdk)
const toast = useFaToast()
const modal = useFaModal()
const download = useChunkedDownload()

const canManage = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.manage))
const canDelete = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.delete))

const KIND_LABEL: Record<string, string> = {
  vanilla: '原版',
  paper: 'Paper',
  purpur: 'Purpur',
  folia: 'Folia',
  fabric: 'Fabric',
  forge: 'Forge',
  neoforge: 'NeoForge',
  quilt: 'Quilt',
  velocity: 'Velocity',
  bungee: 'Bungee',
  bedrock: '基岩版',
  generic: '通用',
}

interface TrashRow {
  trashId: string
  instanceId: string
  instanceName: string
  kind?: string
  mcVersion?: string
  remark?: string
  nodeId: string
  nodeName?: string
  nodeOnline?: boolean
  nodeMissing?: boolean
  trashCapable?: boolean
  dirPresent?: boolean | null
  /** 节点侧实际回收目录名（主名=实例 id，兼容带毫秒后缀的旧残留）。 */
  nodeTrashId?: string
  retentionDays?: number | null
  deletedAtMs?: number
  deletedBy?: string
}

const loading = ref(false)
const rows = ref<TrashRow[]>([])
const pager = reactive({ page: 1, size: 10, total: 0 })
const keyword = ref('')

const columns: TableColumn<TrashRow>[] = [
  { accessorKey: 'instanceName', header: '实例', minWidth: 200, fixed: 'left' },
  { id: 'kind', header: '类型', width: 140 },
  { id: 'node', header: '节点', minWidth: 150 },
  { id: 'deletedAt', header: '删除时间', width: 170 },
  { id: 'status', header: '数据目录', width: 130, align: 'center' },
  { id: 'retention', header: '自动清除', width: 130, align: 'center' },
  { id: 'operation', header: '操作', width: 170, align: 'center', fixed: 'right' },
]

async function load() {
  loading.value = true
  try {
    const page = await extra.pageTrash(pager.page, pager.size, keyword.value.trim() || undefined) as {
      records?: TrashRow[]
      total?: number
    }
    rows.value = page.records ?? []
    pager.total = Number(page.total ?? 0)
  }
  catch (error) {
    toast.error(errorMessage(error, '加载回收站失败'))
  }
  finally {
    loading.value = false
  }
}

function search() {
  pager.page = 1
  void load()
}

/** 该行是否可执行找回/下载：节点在线且支持回收站能力。 */
function nodeReady(row: TrashRow): boolean {
  return row.nodeOnline === true && row.trashCapable === true
}

// ---------- 找回 ----------

const restoreOpen = ref(false)
const restoreTarget = ref<TrashRow | null>(null)
const restoreName = ref('')
const restoreError = ref('')
const restoring = ref(false)

function openRestore(row: TrashRow) {
  restoreTarget.value = row
  restoreName.value = row.instanceName
  restoreError.value = ''
  restoreOpen.value = true
}

function beforeRestoreClose(action: 'confirm' | 'cancel' | 'close', done: () => void) {
  if (action !== 'confirm') {
    done()
    return
  }
  void submitRestore(done)
}

async function submitRestore(done: () => void) {
  const target = restoreTarget.value
  if (!target || restoring.value) {
    return
  }
  restoring.value = true
  restoreError.value = ''
  try {
    await extra.restoreTrash(target.trashId, restoreName.value.trim())
    done()
    toast.success(`实例「${restoreName.value.trim()}」已从回收站找回，可在实例管理中查看`)
    if (rows.value.length === 1 && pager.page > 1) {
      pager.page -= 1
    }
    void load()
  }
  catch (error) {
    // 名称冲突等业务失败保留记录：改个名字即可重试。
    restoreError.value = errorMessage(error, '找回失败')
  }
  finally {
    restoring.value = false
  }
}

// ---------- 下载 ----------

async function downloadRow(row: TrashRow) {
  if (download.active.value) {
    return
  }
  const dirName = row.nodeTrashId || row.trashId
  const zipPath = `trash/${dirName}.zip`
  try {
    await nodeOps.nodeFileZip(row.nodeId, 'data', `trash/${dirName}`, zipPath)
    await download.start(`${row.instanceName || row.trashId}.zip`, (offset, length) =>
      nodeOps.nodeFileReadChunk(row.nodeId, 'data', zipPath, offset, length))
    toast.success('已打包下载')
  }
  catch (error) {
    toast.error(errorMessage(error, '打包下载失败'))
  }
  finally {
    // 清理节点上的临时 zip（尽力执行）。
    try {
      await nodeOps.nodeFileDelete(row.nodeId, 'data', zipPath)
    }
    catch {
      // 清理失败不影响下载结果。
    }
  }
}

// ---------- 立即永久删除 ----------

function confirmRemove(row: TrashRow) {
  modal.confirm({
    title: '永久删除回收数据',
    content: `确认永久删除实例「${row.instanceName}」的数据目录？此操作不可恢复。`,
    confirmButtonText: '永久删除',
    onConfirm: async () => {
      try {
        const result = await extra.removeTrash(row.trashId) as { deleted?: boolean, error?: string }
        if (result?.deleted) {
          toast.success('回收数据已永久删除')
        }
        else {
          toast.warning(`节点暂不可达，数据未删除：${result?.error ?? '请稍后重试'}`)
        }
        void load()
      }
      catch (error) {
        toast.error(errorMessage(error, '删除失败'))
      }
    },
  })
}

function dirStatusText(row: TrashRow): string {
  if (row.dirPresent === true) {
    return '在回收站'
  }
  if (row.dirPresent === false) {
    return '已不在（可能到期清除）'
  }
  return '未知（节点不可达）'
}

function statusTagVariant(row: TrashRow): 'default' | 'secondary' | 'destructive' {
  if (row.dirPresent === true) {
    return 'default'
  }
  if (row.dirPresent === false) {
    return 'destructive'
  }
  return 'secondary'
}

onMounted(() => {
  void load()
})
</script>

<template>
  <FaPageHeader title="回收站" description="已删除实例的数据目录：保留期内可找回或下载，超过节点保留期后自动清除">
    <FaButton variant="outline" @click="() => load()">
      <FaIcon name="i-ri:refresh-line" />
      刷新
    </FaButton>
  </FaPageHeader>

  <FaPageMain>
    <FaSearchBar class="mcp-page-gap">
      <div class="flex flex-wrap items-center gap-2">
        <FaInput v-model="keyword" clearable placeholder="搜索实例名 / 节点 / ID" class="w-64" @keyup.enter="search" />
        <FaButton @click="search">
          <FaIcon name="i-ri:search-line" />
          查询
        </FaButton>
        <span class="text-muted-foreground text-xs">
          保留期由节点配置 trash.retentionDays 控制（默认 14 天，0=永久保留），到期后节点自动永久清除。
        </span>
      </div>
    </FaSearchBar>

    <FaResponsiveTable
      class="mcp-page-gap"
      v-loading="loading"
      :columns="columns"
      :data="rows"
      row-key="trashId"
      table-root-class="rounded-lg overflow-hidden"
      table-class="min-w-[1000px]"
      border
      stripe
      empty-text="回收站为空：删除实例（未勾选永久删除）后，其数据目录会出现在这里"
    >
      <template #cell-instanceName="{ row }">
        <div class="flex min-w-0 flex-col">
          <span class="truncate font-medium">{{ row.original.instanceName }}</span>
          <span v-if="row.original.remark" class="truncate text-xs text-muted-foreground" :title="row.original.remark">
            {{ row.original.remark }}
          </span>
        </div>
      </template>
      <template #cell-kind="{ row }">
        <span>{{ (KIND_LABEL[row.original.kind as string] ?? row.original.kind) || '-' }}</span>
        <span v-if="row.original.mcVersion" class="ml-1 text-xs text-muted-foreground">{{ row.original.mcVersion }}</span>
      </template>
      <template #cell-node="{ row }">
        <div class="flex min-w-0 flex-col">
          <span class="truncate">{{ row.original.nodeName || row.original.nodeId }}</span>
          <span v-if="row.original.nodeMissing" class="text-xs text-destructive">节点已删除</span>
          <span v-else-if="!row.original.nodeOnline" class="text-xs text-muted-foreground">离线</span>
        </div>
      </template>
      <template #cell-deletedAt="{ row }">
        <div class="flex min-w-0 flex-col">
          <span>{{ formatDateTime(row.original.deletedAtMs) }}</span>
          <span v-if="row.original.deletedBy" class="truncate text-xs text-muted-foreground">操作人 {{ row.original.deletedBy }}</span>
        </div>
      </template>
      <template #cell-status="{ row }">
        <FaTag :variant="statusTagVariant(row.original)">
          {{ dirStatusText(row.original) }}
        </FaTag>
      </template>
      <template #cell-retention="{ row }">
        <span v-if="typeof row.original.retentionDays === 'number'">
          {{ row.original.retentionDays > 0 ? `${row.original.retentionDays} 天` : '永久保留' }}
        </span>
        <span v-else class="text-muted-foreground">按节点配置</span>
      </template>
      <template #cell-operation="{ row }">
        <div class="mcp-op-cell">
          <FaTooltip text="以原规格重建实例（名称可改）">
            <FaButton
              v-if="canManage"
              size="sm"
              variant="outline"
              :disabled="!nodeReady(row.original) || row.original.dirPresent === false || restoring"
              @click="openRestore(row.original)"
            >
              找回
            </FaButton>
          </FaTooltip>
          <FaTooltip text="打包为 zip 下载（大目录打包可能较慢）">
            <FaButton
              v-if="canManage"
              size="icon-sm"
              variant="outline"
              :disabled="!nodeReady(row.original) || row.original.dirPresent === false || download.active.value"
              @click="downloadRow(row.original)"
            >
              <FaIcon name="i-ri:download-2-line" />
            </FaButton>
          </FaTooltip>
          <FaTooltip text="立即永久删除（不可恢复）">
            <FaButton
              v-if="canDelete"
              size="icon-sm"
              variant="destructive"
              :disabled="!nodeReady(row.original)"
              @click="confirmRemove(row.original)"
            >
              <FaIcon name="i-ri:delete-bin-line" />
            </FaButton>
          </FaTooltip>
        </div>
      </template>
      <template #card="{ row }">
        <FaCard class="w-full">
          <div class="flex flex-col gap-3">
            <div class="flex items-center justify-between gap-2">
              <span class="min-w-0 break-words text-base font-semibold">{{ row.instanceName }}</span>
              <FaTag :variant="statusTagVariant(row)">
                {{ dirStatusText(row) }}
              </FaTag>
            </div>
            <div class="flex flex-col gap-1 text-sm">
              <div v-if="row.remark" class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">备注</span>
                <span class="break-all">{{ row.remark }}</span>
              </div>
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">类型</span>
                <span class="break-all">{{ (KIND_LABEL[row.kind as string] ?? row.kind) || '-' }}{{ row.mcVersion ? ` · ${row.mcVersion}` : '' }}</span>
              </div>
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">节点</span>
                <span class="break-all">{{ row.nodeName || row.nodeId }}<template v-if="row.nodeMissing">（节点已删除）</template><template v-else-if="!row.nodeOnline">（离线）</template></span>
              </div>
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">删除时间</span>
                <span class="break-all">{{ formatDateTime(row.deletedAtMs) }}<template v-if="row.deletedBy"> · 操作人 {{ row.deletedBy }}</template></span>
              </div>
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">自动清除</span>
                <span class="break-all"><template v-if="typeof row.retentionDays === 'number'">{{ row.retentionDays > 0 ? `${row.retentionDays} 天` : '永久保留' }}</template><template v-else>按节点配置</template></span>
              </div>
            </div>
            <div class="flex flex-wrap gap-2 border-t pt-3">
              <FaButton
                v-if="canManage"
                size="sm"
                variant="outline"
                :disabled="!nodeReady(row) || row.dirPresent === false || restoring"
                @click="openRestore(row)"
              >
                找回
              </FaButton>
              <FaButton
                v-if="canManage"
                size="sm"
                variant="outline"
                :disabled="!nodeReady(row) || row.dirPresent === false || download.active.value"
                @click="downloadRow(row)"
              >
                下载
              </FaButton>
              <FaButton
                v-if="canDelete"
                size="sm"
                variant="destructive"
                :disabled="!nodeReady(row)"
                @click="confirmRemove(row)"
              >
                删除
              </FaButton>
            </div>
          </div>
        </FaCard>
      </template>
    </FaResponsiveTable>

    <FaPagination
      v-model:page="pager.page"
      v-model:size="pager.size"
      :total="pager.total"
      class="mt-3"
      @page-change="() => load()"
      @size-change="() => { pager.page = 1; load() }"
    />

    <FaModal
      v-model="restoreOpen"
      title="从回收站找回实例"
      :show-cancel-button="true"
      confirm-button-text="找回并重建"
      :confirm-button-loading="restoring"
      :before-close="beforeRestoreClose"
    >
      <div class="flex flex-col gap-3">
        <p class="text-muted-foreground text-xs leading-5">
          将以删除前的规格（镜像 / 启动命令 / 内存）在原节点重建实例，数据目录原样恢复；
          端口重新分配，MC 服务器绑定与域名需在设置中重新配置。
        </p>
        <label class="flex flex-col gap-1">
          <span class="text-sm">实例名称</span>
          <FaInput v-model="restoreName" clearable class="w-full" />
        </label>
        <p v-if="restoreError" class="text-destructive text-xs">{{ restoreError }}</p>
      </div>
    </FaModal>
  </FaPageMain>
</template>
