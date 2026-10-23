<script setup lang="ts">
import type { SearchConfig, SearchFieldConfig, TableColumn, TableSortingState, ActionMenuItem } from '@yudream/components'
import type { SkinPluginModel } from '../composables/useSkinPlugin'
import type { SkinClosetItem } from '../types'
import { computed, reactive, ref, watch } from 'vue'
import { YdLabel, YdButton, YdDynamicSearchForm, YdIcon, YdInput, YdModal, YdPagination, YdRelatedEntity, YdSelect, useYdModal, YdResponsiveEnhancedTable, YdTablePageLayout } from '@yudream/components'

const props = defineProps<{ model: SkinPluginModel }>()
const modal = useYdModal()
const search = reactive({ keyword: '', userId: '' })
const pagination = reactive({ page: 1, size: 10 })
const formVisible = ref(false)
const editing = ref<SkinClosetItem | null>(null)

/** ControlType 是 components 内部的字符串枚举，桥接层只出类型，这里用字面量收敛一次 */
type SearchFieldInput = Partial<Omit<SearchFieldConfig, 'controlType'>> & Pick<SearchFieldConfig, 'fieldName' | 'label'> & { controlType?: 'TEXT' | 'SELECT' | 'DATE_RANGE' | 'NUMBER_RANGE' | 'CHECKBOX' | 'RADIO' }

function searchField(config: SearchFieldInput): SearchFieldConfig {
  const { controlType = 'TEXT', order = 0, showWhenFolded = false, width = '200px', ...rest } = config
  return { ...rest, fieldName: config.fieldName, label: config.label, controlType: controlType as SearchFieldConfig['controlType'], order, showWhenFolded, width }
}

const searchQuery = ref<Record<string, any>>({ keyword: '', userId: '' })

const searchConfig: SearchConfig = {
  fields: [
    searchField({ fieldName: 'keyword', label: '关键词', placeholder: '衣柜名称 / 材质', controlType: 'TEXT', order: 1, showWhenFolded: true, width: '220px' }),
    searchField({ fieldName: 'userId', label: '用户 ID', controlType: 'TEXT', order: 2, showWhenFolded: true, width: '180px' }),
  ],
}

function applySearch() {
  search.keyword = String(searchQuery.value.keyword ?? '')
  search.userId = String(searchQuery.value.userId ?? '')
  pagination.page = 1
}

/** 本地数据的手动排序：先排序再切片，保证排序作用于全部筛选结果 */
const sorting = ref<TableSortingState>([{ id: 'createdAt', desc: true }])

function changeSorting(next: TableSortingState) {
  sorting.value = next
  pagination.page = 1
}

function compareValues(left: unknown, right: unknown) {
  if (left == null && right == null) return 0
  if (left == null) return -1
  if (right == null) return 1
  const leftNumber = Number(left)
  const rightNumber = Number(right)
  if (String(left).trim() !== '' && String(right).trim() !== '' && Number.isFinite(leftNumber) && Number.isFinite(rightNumber)) {
    return leftNumber - rightNumber
  }
  return String(left).localeCompare(String(right), 'zh-CN')
}

function sortRows(rows: SkinClosetItem[]) {
  const first = sorting.value[0]
  if (!first) return rows
  const sorted = [...rows].sort((a, b) => compareValues((a as unknown as Record<string, unknown>)[first.id], (b as unknown as Record<string, unknown>)[first.id]))
  return first.desc ? sorted.reverse() : sorted
}

const textureOptions = computed(() => props.model.textures.map(item => ({
  label: `${item.name} (${item.hash.slice(0, 10)})`,
  value: item.hash,
})))

const filteredRows = computed(() => {
  const keyword = search.keyword.trim().toLowerCase()
  const userId = search.userId.trim()
  return props.model.closetItems.filter((item) => {
    if (userId && !item.userId?.includes(userId)) {
      return false
    }
    if (!keyword) {
      return true
    }
    return [item.itemName, item.textureHash, props.model.textureName(item.textureHash), item.userId]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
      .includes(keyword)
  })
})

const pagedRows = computed(() => {
  const rows = sortRows(filteredRows.value)
  const start = (pagination.page - 1) * pagination.size
  return rows.slice(start, start + pagination.size)
})

const totalPages = computed(() => Math.max(1, Math.ceil(filteredRows.value.length / pagination.size)))

const columns: TableColumn<SkinClosetItem>[] = [
  { accessorKey: 'itemName', header: '衣柜名称', width: 180, fixed: 'left' },
  { accessorKey: 'userId', header: '用户', width: 220 },
  { id: 'texture', header: '材质', width: 240, enableSorting: false },
  { accessorKey: 'createdAt', header: '创建时间', width: 170 },
  ]

watch(totalPages, pages => { if (pagination.page > pages) pagination.page = pages })

function openCreate() {
  editing.value = null
  Object.assign(props.model.closetForm, { userId: '', textureHash: '', itemName: '' })
  formVisible.value = true
}

function openEdit(item: SkinClosetItem) {
  editing.value = item
  props.model.selectClosetItem(item)
  formVisible.value = true
}

async function submit() {
  if (editing.value) {
    await props.model.renameClosetItem(editing.value)
  }
  else {
    await props.model.saveClosetItem()
  }
  formVisible.value = false
}

function confirmDelete(item: SkinClosetItem) {
  modal.confirm({
    title: '删除衣柜项',
    content: `确认删除「${item.itemName || props.model.textureName(item.textureHash)}」吗？`,
    onConfirm: async () => {
      await props.model.deleteClosetItem(item)
      if (!pagedRows.value.length && pagination.page > 1) {
        pagination.page -= 1
      }
    },
  })
}

/** 行操作统一收进操作菜单，删除保持确认流程 */
const rowActions: ActionMenuItem<SkinClosetItem>[] = [
  { label: '重命名', icon: 'morph:pencil', onClick: row => openEdit(row) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => confirmDelete(row) },
]

</script>

<template>
  <YdTablePageLayout title="衣柜管理">
      <template #search="{ fold }">
        <YdDynamicSearchForm
          v-model="searchQuery"
          :config="searchConfig"
          :fold="fold"
          @search="applySearch"
          @reset="applySearch"
        />
      </template>
      <template #actions>
        <YdButton @click="openCreate"><YdIcon name="i-ri:add-line" />新增衣柜项</YdButton>
      </template>
      <template #table>
        <YdResponsiveEnhancedTable
          :actions="rowActions"
          row-key="id"
          table-root-class="max-w-full overflow-x-auto rounded-lg"
          table-class="min-w-[920px]"
          border
          stripe
          sortable
          manual-sorting
          :sorting="sorting"
          @sorting-change="changeSorting"
          column-visibility
          :columns="columns"
          :data="pagedRows"
          empty-text="暂无衣柜数据"
        >
          <template #cell-userId="{ row }">
            <YdRelatedEntity
              v-if="row.original.userId"
              entity-type="user"
              :entity-id="row.original.userId"
              :label="model.userName(row.original.userId)"
            />
            <span v-else>-</span>
          </template>
          <template #cell-texture="{ row }">
            {{ model.textureName(row.original.textureHash) }}
            <div class="skin-admin-code">{{ row.original.textureHash }}</div>
          </template>
          <template #cell-createdAt="{ row }">{{ model.dateText(row.original.createdAt) }}</template>
        </YdResponsiveEnhancedTable>
      </template>
      <template #footer>
        <YdPagination
          v-model:page="pagination.page"
          v-model:size="pagination.size"
          :total="filteredRows.length"
          @size-change="pagination.page = 1"
        />
      </template>
    </YdTablePageLayout>

    <YdModal v-model="formVisible" :title="editing ? '重命名衣柜项' : '新增衣柜项'" show-cancel-button @confirm="submit">
      <div class="skin-admin-form">
        <template v-if="!editing">
          <YdLabel><span>用户 ID</span><YdInput v-model="model.closetForm.userId" /></YdLabel>
          <YdLabel><span>材质</span><YdSelect v-model="model.closetForm.textureHash" :options="textureOptions" /></YdLabel>
          <YdLabel><span>衣柜名称</span><YdInput v-model="model.closetForm.itemName" /></YdLabel>
        </template>
        <YdLabel v-else><span>衣柜名称</span><YdInput v-model="model.closetRenameForm.itemName" /></YdLabel>
      </div>
    </YdModal>
</template>
