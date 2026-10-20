<script setup lang="ts">
import { YdLoadingOverlay, YdTablePageLayout } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn, ActionMenuItem } from '@yudream/components'
import { YdButton, YdIcon, YdInput, YdPagination, YdSelect, YdTag, useYdModal, useYdToast, YdResponsiveEnhancedTable } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { createMcPanelExtra } from '../../api/api-extra'
import { MCPANEL_PERMISSION, accountHasPermission } from '../../composables/permissions'
import { errorMessage, formatDateTime } from '../../composables/utils'
import TemplateFormModal from '../../components/TemplateFormModal.vue'

/**
 * 服务端/镜像模板管理（M4）：模板定义安装源、启动形态与注入开关。
 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const extra = createMcPanelExtra(props.sdk)
const toast = useYdToast()
const modal = useYdModal()

const canManage = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.manage))
const canDelete = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.delete))

const loading = ref(false)
const rows = ref<Record<string, unknown>[]>([])
const pager = reactive({ page: 1, size: 10, total: 0 })
const filters = reactive({ keyword: '', kind: 'all' })

const KIND_OPTIONS = [
  { label: '全部类型', value: 'all' },
  { label: '服务端模板', value: 'server' },
  { label: '镜像模板', value: 'image' },
]

const columns: TableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'key', header: 'Key', width: 170, fixed: 'left' },
  { accessorKey: 'name', header: '名称', minWidth: 160 },
  { id: 'kind', header: '类型', width: 110, align: 'center' },
  { accessorKey: 'mcVersion', header: '默认版本', width: 110, align: 'center' },
  { accessorKey: 'image', header: '镜像', minWidth: 180 },
  { accessorKey: 'updatedAt', header: '更新时间', width: 160 },
]

const formOpen = ref(false)
const editing = ref<Record<string, unknown> | null>(null)

async function load() {
  loading.value = true
  try {
    const page = await extra.pageTemplates(
      pager.page, pager.size,
      filters.kind === 'all' ? undefined : filters.kind,
      filters.keyword.trim() || undefined,
    ) as { records?: Record<string, unknown>[], total?: number }
    rows.value = page.records ?? []
    pager.total = Number(page.total ?? 0)
  }
  catch (error) {
    toast.error(errorMessage(error, '加载模板列表失败'))
  }
  finally {
    loading.value = false
  }
}

function search() {
  pager.page = 1
  void load()
}

function confirmDelete(row: Record<string, unknown>) {
  modal.confirm({
    title: '删除模板',
    content: `确认删除模板「${row.name}」吗？已创建的实例不受影响。`,
    confirmButtonText: '删除',
    onConfirm: async () => {
      try {
        await extra.deleteTemplate(String(row.key))
        toast.success('模板已删除')
        if (rows.value.length === 1 && pager.page > 1) {
          pager.page -= 1
        }
        void load()
      }
      catch (error) {
        toast.error(errorMessage(error, '删除模板失败'))
      }
    },
  })
}

onMounted(() => void load())

/** 模板行操作统一收进操作菜单 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '编辑', icon: 'morph:pencil', visible: () => Boolean(canManage.value), onClick: row => { editing.value = row as never; formOpen.value = true } },
  { label: '删除', icon: 'morph:trash-2', danger: true, visible: () => Boolean(canDelete.value), onClick: row => confirmDelete(row as never) },
]

</script>

<template>
  
  <YdTablePageLayout title="服务端模板">
            <template #actions>
      <YdButton v-if="canManage" @click="editing = null; formOpen = true">
      <YdIcon name="i-ri:add-line" />
      新建模板
    </YdButton>
            </template>
            <template #search>
              <div class="flex flex-wrap items-center gap-2">
                <div class="yd-filter-grid" style="--yd-filter-columns: 2">
            <YdInput v-model="filters.keyword" clearable placeholder="Key / 名称" class="w-full" @keydown.enter="search" @clear="search" />
            <YdSelect v-model="filters.kind" :options="KIND_OPTIONS" class="w-full" @change="search" />
            <div class="flex flex-wrap items-center justify-end gap-2">
              <YdButton variant="outline" @click="() => { filters.keyword = ''; filters.kind = 'all'; search() }">
                重置
              </YdButton>
              <YdButton @click="search">
                <YdIcon name="i-ri:search-line" />
                查询
              </YdButton>
            </div>
          </div>
              </div>
            </template>
            <template #table>
<YdLoadingOverlay :loading="loading"><YdResponsiveEnhancedTable
        :actions="rowActions"

      
      :columns="columns"
      :data="rows"
      row-key="key"
      table-root-class="rounded-lg overflow-hidden"
      table-class="min-w-[960px]"
      border
      stripe
      column-visibility
      empty-text="暂无模板，点击右上角「新建模板」创建"
    >
      
      <template #cell-kind="{ row }">
        <YdTag :variant="row.original.kind === 'image' ? 'secondary' : 'default'">
          {{ row.original.kind === 'image' ? '镜像' : '服务端' }}
        </YdTag>
      </template>
      <template #cell-updatedAt="{ row }">
        {{ formatDateTime(row.original.updatedAt as number | undefined) }}
      </template>
</YdResponsiveEnhancedTable></YdLoadingOverlay>
            </template>
            <template #footer>
      <YdPagination
      v-model:page="pager.page"
      v-model:size="pager.size"
      :total="pager.total"
      @page-change="() => load()"
      @size-change="() => { pager.page = 1; load() }"
    />
            </template>
          </YdTablePageLayout>

    <TemplateFormModal v-model:open="formOpen" :sdk="sdk" :template="editing" @saved="() => { toast.success('模板已保存'); load() }" />
</template>
