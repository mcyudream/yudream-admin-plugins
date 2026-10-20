<script setup lang="ts">
import type { TableColumn, ActionMenuItem } from '@yudream/components'
import type { SkinPluginModel } from '../composables/useSkinPlugin'
import type { SkinClosetItem } from '../types'
import { computed, reactive, ref, watch } from 'vue'
import { YdLabel, YdButton, YdEntityPeek, YdIcon, YdInput, YdModal, YdPagination, YdSelect, useYdModal, YdResponsiveEnhancedTable, YdTablePageLayout } from '@yudream/components'

const props = defineProps<{ model: SkinPluginModel }>()
const modal = useYdModal()
const search = reactive({ keyword: '', userId: '' })
const pagination = reactive({ page: 1, size: 10 })
const formVisible = ref(false)
const editing = ref<SkinClosetItem | null>(null)

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
  const start = (pagination.page - 1) * pagination.size
  return filteredRows.value.slice(start, start + pagination.size)
})

const totalPages = computed(() => Math.max(1, Math.ceil(filteredRows.value.length / pagination.size)))

const columns: TableColumn<SkinClosetItem>[] = [
  { accessorKey: 'itemName', header: '衣柜名称', width: 180, fixed: 'left' },
  { accessorKey: 'userId', header: '用户', width: 220 },
  { id: 'texture', header: '材质', width: 240 },
  { id: 'createdAt', header: '创建时间', width: 170 },
  ]

watch([() => search.keyword, () => search.userId], () => { pagination.page = 1 })
watch(totalPages, pages => { if (pagination.page > pages) pagination.page = pages })

function resetSearch() {
  search.keyword = ''
  search.userId = ''
  pagination.page = 1
}

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
      <template #search>
        <div class="yd-filter-grid" style="--yd-filter-columns: 2">
          <YdInput
            v-model="search.keyword"
            clearable
            placeholder="衣柜名称 / 材质"
            class="w-full"
            @keydown.enter="pagination.page = 1"
            @clear="pagination.page = 1"
          />
          <YdInput v-model="search.userId" clearable placeholder="用户 ID" class="w-full" />
          <div class="flex items-center justify-end gap-2">
            <YdButton variant="outline" @click="resetSearch">重置</YdButton>
          </div>
        </div>
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
          column-visibility
          :columns="columns"
          :data="pagedRows"
          empty-text="暂无衣柜数据"
        >
          <template #cell-userId="{ row }">
            <YdEntityPeek
              :title="model.userName(row.original.userId)"
              :subtitle="row.original.userId ? `ID ${row.original.userId}` : ''"
            />
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
