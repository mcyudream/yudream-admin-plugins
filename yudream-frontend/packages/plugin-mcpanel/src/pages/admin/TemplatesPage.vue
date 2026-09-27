<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import { FaButton, FaCard, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaSearchBar, FaSelect, FaTag, useFaModal, useFaToast } from '@yudream/components'
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
const toast = useFaToast()
const modal = useFaModal()

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
  { id: 'operation', header: '操作', width: 140, align: 'center', fixed: 'right' },
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
</script>

<template>
  <FaPageHeader title="服务端模板" description="镜像与服务端模板：安装源、启动命令形态、配置重写与注入开关">
    <FaButton v-if="canManage" @click="editing = null; formOpen = true">
      <FaIcon name="i-ri:add-line" />
      新建模板
    </FaButton>
  </FaPageHeader>
  <FaPageMain>
    <FaResponsiveTable
      v-loading="loading"
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
      <template #toolbar>
        <FaSearchBar class="w-full">
          <div class="mcp-filter-grid">
            <FaInput v-model="filters.keyword" clearable placeholder="Key / 名称" class="w-full" @keydown.enter="search" @clear="search" />
            <FaSelect v-model="filters.kind" :options="KIND_OPTIONS" class="w-full" @change="search" />
            <div class="flex flex-wrap items-center justify-end gap-2">
              <FaButton variant="outline" @click="() => { filters.keyword = ''; filters.kind = 'all'; search() }">
                重置
              </FaButton>
              <FaButton @click="search">
                <FaIcon name="i-ri:search-line" />
                查询
              </FaButton>
            </div>
          </div>
        </FaSearchBar>
      </template>
      <template #cell-kind="{ row }">
        <FaTag :variant="row.original.kind === 'image' ? 'secondary' : 'default'">
          {{ row.original.kind === 'image' ? '镜像' : '服务端' }}
        </FaTag>
      </template>
      <template #cell-updatedAt="{ row }">
        {{ formatDateTime(row.original.updatedAt as number | undefined) }}
      </template>
      <template #cell-operation="{ row }">
        <div class="mcp-op-cell">
          <FaButton v-if="canManage" size="sm" variant="outline" @click="editing = row.original; formOpen = true">
            编辑
          </FaButton>
          <FaButton v-if="canDelete" size="sm" variant="destructive" @click="confirmDelete(row.original)">
            删除
          </FaButton>
        </div>
      </template>
      <template #card="{ row }">
        <FaCard class="w-full">
          <div class="flex flex-col gap-3">
            <div class="flex items-center justify-between gap-2">
              <span class="min-w-0 break-words text-base font-semibold">{{ row.name || row.key }}</span>
              <FaTag :variant="row.kind === 'image' ? 'secondary' : 'default'">
                {{ row.kind === 'image' ? '镜像' : '服务端' }}
              </FaTag>
            </div>
            <div class="flex flex-col gap-1 text-sm">
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">Key</span>
                <span class="mcp-mono break-all">{{ row.key }}</span>
              </div>
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">默认版本</span>
                <span class="break-all">{{ row.mcVersion || '-' }}</span>
              </div>
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">镜像</span>
                <span class="break-all">{{ row.image || '-' }}</span>
              </div>
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">更新时间</span>
                <span class="break-all">{{ formatDateTime(row.updatedAt as number | undefined) }}</span>
              </div>
            </div>
            <div class="flex flex-wrap gap-2 border-t pt-3">
              <FaButton v-if="canManage" size="sm" variant="outline" @click="editing = row; formOpen = true">
                编辑
              </FaButton>
              <FaButton v-if="canDelete" size="sm" variant="destructive" @click="confirmDelete(row)">
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
    <TemplateFormModal v-model:open="formOpen" :sdk="sdk" :template="editing" @saved="() => { toast.success('模板已保存'); load() }" />
  </FaPageMain>
</template>
