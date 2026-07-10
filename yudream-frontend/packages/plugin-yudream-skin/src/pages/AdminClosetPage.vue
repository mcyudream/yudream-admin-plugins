<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { SkinPluginModel } from '../composables/useSkinPlugin'
import type { SkinClosetItem } from '../types'
import { computed, reactive, ref, watch } from 'vue'
import { FaButton, FaIcon, FaInput, FaModal, FaPageHeader, FaPageMain, FaPagination, FaSearchBar, FaSelect, FaTable, useFaModal } from '@yudream/components'

const props = defineProps<{ model: SkinPluginModel }>()
const modal = useFaModal()
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
    if (userId && !item.userId.includes(userId)) {
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
  { id: 'operation', header: '操作', width: 170, align: 'center', fixed: 'right' },
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
</script>

<template>
  <FaPageHeader title="衣柜管理" class="mb-0">
    <FaButton @click="openCreate"><FaIcon name="i-ri:add-line" />新增衣柜项</FaButton>
  </FaPageHeader>
  <FaPageMain><div class="skin-admin-table-page">
    <FaTable
      row-key="id"
      table-root-class="rounded-lg overflow-hidden"
      table-class="min-w-[920px]"
      border
      stripe
      column-visibility
      :columns="columns"
      :data="pagedRows"
      empty-text="暂无衣柜数据"
    >
      <template #toolbar>
        <FaSearchBar class="w-full">
          <div class="skin-admin-filter-grid">
            <FaInput v-model="search.keyword" clearable placeholder="衣柜名称 / 材质" />
            <FaInput v-model="search.userId" clearable placeholder="用户 ID" />
            <div class="skin-admin-filter-grid__actions">
              <FaButton variant="outline" @click="resetSearch">重置</FaButton>
            </div>
          </div>
        </FaSearchBar>
      </template>
      <template #cell-userId="{ row }">
        {{ model.userName(row.original.userId) }}
        <div class="skin-admin-code">{{ row.original.userId }}</div>
      </template>
      <template #cell-texture="{ row }">
        {{ model.textureName(row.original.textureHash) }}
        <div class="skin-admin-code">{{ row.original.textureHash }}</div>
      </template>
      <template #cell-createdAt="{ row }">{{ model.dateText(row.original.createdAt) }}</template>
      <template #cell-operation="{ row }">
        <div class="flex-center gap-2">
          <FaButton size="sm" variant="outline" @click="openEdit(row.original)">重命名</FaButton>
          <FaButton size="sm" variant="destructive" @click="confirmDelete(row.original)">删除</FaButton>
        </div>
      </template>
    </FaTable>

    <FaPagination
      v-model:page="pagination.page"
      v-model:size="pagination.size"
      :total="filteredRows.length"
      class="mt-3"
      @size-change="pagination.page = 1"
    />

    <FaModal v-model="formVisible" :title="editing ? '重命名衣柜项' : '新增衣柜项'" show-cancel-button @confirm="submit">
      <div class="skin-admin-form">
        <template v-if="!editing">
          <label><span>用户 ID</span><FaInput v-model="model.closetForm.userId" /></label>
          <label><span>材质</span><FaSelect v-model="model.closetForm.textureHash" :options="textureOptions" /></label>
          <label><span>衣柜名称</span><FaInput v-model="model.closetForm.itemName" /></label>
        </template>
        <label v-else><span>衣柜名称</span><FaInput v-model="model.closetRenameForm.itemName" /></label>
      </div>
    </FaModal>
  </div></FaPageMain>
</template>
