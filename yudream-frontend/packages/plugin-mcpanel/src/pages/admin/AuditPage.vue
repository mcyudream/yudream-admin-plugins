<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaAlert, FaButton, FaCard, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaSearchBar, FaSelect, FaTag, useFaModal, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { createMcPanelExtra } from '../../api/api-extra'
import { MCPANEL_PERMISSION, accountHasPermission } from '../../composables/permissions'
import { errorMessage, formatDateTime } from '../../composables/utils'

/**
 * 操作审计：变更类操作（启停、命令、文件写、备份、SFTP）的查询、CSV 导出与单条删除。
 * 纯读（列表/查看/编辑器分块读取）不入审计，避免淹没真正的操作。
 * logId/tenantId 不进表格（内部标识，仍在 CSV 导出中保留）。
 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const extra = createMcPanelExtra(props.sdk)
const toast = useFaToast()
const modal = useFaModal()

const canDelete = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.delete))

const loading = ref(false)
const exporting = ref(false)
const deleting = ref('')
const clearing = ref(false)
const error = ref('')
const rows = ref<Record<string, unknown>[]>([])
const pager = reactive({ page: 1, size: 20, total: 0 })
const filters = reactive({
  action: '',
  actor: '',
  targetType: 'all',
  targetId: '',
})

const TARGET_TYPES = [
  { label: '全部对象类型', value: 'all' },
  { label: '实例', value: 'instance' },
  { label: '节点', value: 'node' },
  { label: '其他', value: 'other' },
]

/** 动作码 → 中文标签；未知动作原样展示 code。 */
const ACTION_LABELS: Record<string, string> = {
  'instance.create': '创建实例',
  'instance.update': '修改实例配置',
  'instance.start': '启动实例',
  'instance.stop': '停止实例',
  'instance.restart': '重启实例',
  'instance.kill': '强制终止实例',
  'instance.delete': '删除实例',
  'instance.purge': '彻底删除实例',
  'instance.command': '控制台命令',
  'instance.install.failed': '实例安装失败',
  'instance.node-cascade': '节点删除级联清理',
  'instance.node-cascade.failed': '节点级联清理失败',
  'file.write': '写入文件',
  'file.mkdir': '新建目录',
  'file.rename': '重命名文件',
  'file.delete': '删除文件',
  'file.zip': '压缩文件',
  'file.unzip': '解压文件',
  'file.upload.begin': '开始上传',
  'file.upload.chunk': '上传分块',
  'file.upload.commit': '完成上传',
  'file.download.chunk': '下载文件',
  'backup.create': '创建备份',
  'backup.restore': '恢复备份',
  'backup.delete': '删除备份',
  'ftp.open': '开启临时 SFTP',
  'ftp.close': '关闭临时 SFTP',
  'node.ftp.open': '开启节点 SFTP',
  'node.ftp.close': '关闭节点 SFTP',
}

const TARGET_TYPE_LABELS: Record<string, string> = {
  instance: '实例',
  node: '节点',
  other: '其他',
}

function actionLabel(action: unknown): string {
  return ACTION_LABELS[String(action ?? '')] ?? ''
}

function targetTypeLabel(targetType: unknown): string {
  return TARGET_TYPE_LABELS[String(targetType ?? '')] ?? String(targetType || '其他')
}

const columns: TableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'at', header: '时间', width: 165 },
  { accessorKey: 'actor', header: '操作者', minWidth: 150 },
  { accessorKey: 'action', header: '动作', minWidth: 150 },
  { accessorKey: 'target', header: '对象', minWidth: 180 },
  { accessorKey: 'detail', header: '详情', minWidth: 160 },
  { id: 'operation', header: '操作', width: 76 },
]

async function load() {
  loading.value = true
  error.value = ''
  try {
    const page = await extra.pageAudit(
      pager.page,
      pager.size,
      filters.action.trim() || undefined,
      filters.actor.trim() || undefined,
      filters.targetType === 'all' ? undefined : filters.targetType,
      filters.targetId.trim() || undefined,
    ) as { records?: Record<string, unknown>[], total?: number }
    rows.value = (page.records ?? []).map(item => ({
      ...item,
      logId: String(item.logId || item.id || ''),
      detail: item.detail || '-',
    }))
    pager.total = Number(page.total ?? 0)
  }
  catch (e) {
    error.value = errorMessage(e, '加载审计日志失败')
  }
  finally {
    loading.value = false
  }
}

async function exportCsv() {
  if (exporting.value) {
    return
  }
  exporting.value = true
  try {
    const result = await extra.exportAudit(
      filters.action.trim() || undefined,
      filters.actor.trim() || undefined,
      filters.targetType === 'all' ? undefined : filters.targetType,
      filters.targetId.trim() || undefined,
    ) as { fileName?: string, content?: string, total?: number }
    const blob = new Blob([result?.content ?? ''], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = result?.fileName || 'mcpanel-audit.csv'
    a.click()
    URL.revokeObjectURL(url)
    toast.success(`已导出 ${result?.total ?? 0} 条审计记录`)
  }
  catch (e) {
    toast.error(errorMessage(e, '导出失败'))
  }
  finally {
    exporting.value = false
  }
}

function confirmDelete(row: Record<string, unknown>) {
  const logId = String(row.logId ?? '')
  if (!logId || deleting.value) {
    return
  }
  modal.confirm({
    title: '删除这条审计记录？',
    content: `${formatDateTime(row.at as number)} · ${actionLabel(row.action) || row.action} · ${row.targetId ?? ''}。删除后不可恢复。`,
    confirmButtonText: '删除',
    cancelButtonText: '取消',
    onConfirm: async () => {
      deleting.value = logId
      try {
        await extra.deleteAudit(logId)
        toast.success('已删除')
        // 当前页删空且不是第一页时回退一页，避免停留在空页。
        if (rows.value.length <= 1 && pager.page > 1) {
          pager.page -= 1
        }
        await load()
      }
      catch (e) {
        toast.error(errorMessage(e, '删除失败'))
      }
      finally {
        deleting.value = ''
      }
    },
  })
}

/** 一键清理：删除当前筛选条件下的全部记录；无筛选即清空。确认框如实显示条数。 */
function confirmClear() {
  if (clearing.value || pager.total <= 0) {
    return
  }
  const hasFilter = !!(filters.action.trim() || filters.actor.trim()
    || filters.targetType !== 'all' || filters.targetId.trim())
  modal.confirm({
    title: hasFilter ? '删除当前筛选下的全部审计记录？' : '清空全部审计记录？',
    content: `将永久删除 ${pager.total} 条记录${hasFilter ? '（当前筛选条件命中）' : '（未设置筛选条件）'}，删除后不可恢复。`,
    confirmButtonText: hasFilter ? '删除筛选结果' : '全部清空',
    cancelButtonText: '取消',
    onConfirm: async () => {
      clearing.value = true
      try {
        const result = await extra.clearAudit(
          filters.action.trim() || undefined,
          filters.actor.trim() || undefined,
          filters.targetType === 'all' ? undefined : filters.targetType,
          filters.targetId.trim() || undefined,
        ) as { deleted?: number }
        toast.success(`已清理 ${result?.deleted ?? 0} 条审计记录`)
        pager.page = 1
        await load()
      }
      catch (e) {
        toast.error(errorMessage(e, '清理失败'))
      }
      finally {
        clearing.value = false
      }
    },
  })
}

function reset() {
  filters.action = ''
  filters.actor = ''
  filters.targetType = 'all'
  filters.targetId = ''
  pager.page = 1
  void load()
}

onMounted(() => void load())
</script>

<template>
  <FaPageHeader title="操作审计" description="启停、命令、文件变更、备份、SFTP 等敏感操作；纯浏览不入审计。支持筛选、CSV 导出与单条删除">
    <FaButton variant="outline" @click="load">
      刷新
    </FaButton>
    <FaButton :loading="exporting" @click="exportCsv">
      导出 CSV
    </FaButton>
    <FaButton v-if="canDelete" variant="destructive" :loading="clearing" :disabled="pager.total <= 0" @click="confirmClear">
      <FaIcon name="i-ri:delete-bin-2-line" />
      一键清理
    </FaButton>
  </FaPageHeader>
  <FaPageMain>
    <FaAlert v-if="error" variant="destructive" title="无法加载审计日志" :description="error" class="mb-4" />
    <FaResponsiveTable
      v-loading="loading"
      :columns="columns"
      :data="rows"
      row-key="logId"
      table-root-class="rounded-lg overflow-hidden"
      table-class="min-w-[860px]"
      border
      stripe
      column-visibility
      empty-text="暂无审计记录"
    >
      <template #toolbar>
        <FaSearchBar :show-toggle="false" class="w-full">
          <div class="grid w-full gap-2 sm:grid-cols-2 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_170px_minmax(0,1fr)_auto]">
            <FaInput v-model="filters.action" clearable placeholder="动作，如 instance.start" class="w-full" @keydown.enter="() => { pager.page = 1; load() }" />
            <FaInput v-model="filters.actor" clearable placeholder="操作者" class="w-full" @keydown.enter="() => { pager.page = 1; load() }" />
            <FaSelect v-model="filters.targetType" :options="TARGET_TYPES" class="w-full" @change="() => { pager.page = 1; load() }" />
            <FaInput v-model="filters.targetId" clearable placeholder="对象 ID" class="w-full" @keydown.enter="() => { pager.page = 1; load() }" />
            <div class="flex items-center justify-end gap-2">
              <FaButton variant="outline" @click="reset">
                重置
              </FaButton>
              <FaButton @click="() => { pager.page = 1; load() }">
                <FaIcon name="i-ri:search-line" />
                查询
              </FaButton>
            </div>
          </div>
        </FaSearchBar>
      </template>
      <template #cell-at="{ row }">
        <span class="mcp-status-meta">{{ formatDateTime(row.original.at as number) }}</span>
      </template>
      <template #cell-actor="{ row }">
        <span class="mcp-inline-code">{{ row.original.actor }}</span>
      </template>
      <template #cell-action="{ row }">
        <div class="mcp-col">
          <span>{{ actionLabel(row.original.action) || row.original.action }}</span>
          <span v-if="actionLabel(row.original.action)" class="mcp-status-meta mcp-mono">{{ row.original.action }}</span>
        </div>
      </template>
      <template #cell-target="{ row }">
        <div class="mcp-row" style="flex-wrap: nowrap;">
          <FaTag variant="secondary">
            {{ targetTypeLabel(row.original.targetType) }}
          </FaTag>
          <span v-if="row.original.targetName" class="mcp-audit-target-name">{{ row.original.targetName }}</span>
          <span class="mcp-inline-code" :title="String(row.original.targetId ?? '')">{{ row.original.targetId }}</span>
        </div>
      </template>
      <template #cell-operation="{ row }">
        <FaButton
          v-if="canDelete"
          size="sm"
          variant="ghost"
          title="删除该记录"
          :loading="deleting === row.original.logId"
          @click="confirmDelete(row.original)"
        >
          <FaIcon name="i-ri:delete-bin-line" class="mcp-audit-del" />
        </FaButton>
      </template>
      <template #card="{ row }">
        <FaCard class="w-full">
          <div class="flex flex-col gap-3">
            <div class="flex items-start justify-between gap-2">
              <span class="min-w-0 break-words text-base font-semibold">{{ actionLabel(row.action) || row.action }}</span>
              <FaTag variant="secondary">
                {{ targetTypeLabel(row.targetType) }}
              </FaTag>
            </div>
            <div class="flex flex-col gap-1 text-sm">
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">时间</span>
                <span class="break-all">{{ formatDateTime(row.at as number) }}</span>
              </div>
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">操作者</span>
                <span class="break-all">{{ row.actor }}</span>
              </div>
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">对象</span>
                <span class="min-w-0 flex-1 break-all">
                  <span v-if="row.targetName">{{ row.targetName }} </span>
                  <span class="mcp-mono">{{ row.targetId }}</span>
                </span>
              </div>
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">详情</span>
                <span class="break-all">{{ row.detail || '-' }}</span>
              </div>
            </div>
            <div v-if="canDelete" class="flex flex-wrap gap-2 border-t pt-3">
              <FaButton
                size="sm"
                variant="ghost"
                :loading="deleting === row.logId"
                @click="confirmDelete(row)"
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
  </FaPageMain>
</template>

