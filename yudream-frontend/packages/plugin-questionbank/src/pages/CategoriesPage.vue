<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { CategoryView } from '../types'
import { FaButton, FaIcon, FaInput, FaModal, FaNumberField, FaPageHeader, FaPageMain, FaResponsiveTable, useFaModal } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { formatTime } from '../composables/utils'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model
const confirm = useFaModal()

const editOpen = ref(false)
const editTarget = ref<CategoryView | null>(null)
const editName = ref('')
const editSort = ref(0)
const saving = ref(false)

const columns: TableColumn<CategoryView>[] = [
  { accessorKey: 'name', header: '分类名称', minWidth: 180, fixed: 'left' },
  { accessorKey: 'sort', header: '排序', width: 90 },
  { accessorKey: 'questionCount', header: '题目数', width: 100 },
  { accessorKey: 'createdAt', header: '创建时间', width: 180 },
  { id: 'operation', header: '操作', width: 160 },
]

function openCreate() {
  editTarget.value = null
  editName.value = ''
  editSort.value = 0
  editOpen.value = true
}

function openEdit(row: CategoryView) {
  editTarget.value = row
  editName.value = row.name
  editSort.value = Number(row.sort) || 0
  editOpen.value = true
}

async function submit() {
  if (!editName.value.trim()) {
    return
  }
  saving.value = true
  try {
    await model.saveCategory(editTarget.value?.id ?? null, editName.value.trim(), editSort.value)
    editOpen.value = false
  }
  finally {
    saving.value = false
  }
}

function confirmDelete(row: CategoryView) {
  confirm.confirm({
    title: '删除分类',
    content: `确认删除分类「${row.name}」吗？该分类下还有 ${row.questionCount} 道题时必须先把它们移出。`,
    onConfirm: () => model.removeCategory(row),
  })
}

onMounted(() => model.loadAdminCategories())
</script>

<template>
  <FaPageHeader title="题目分类" description="分类对所有题目共享，删除前需移出其中题目">
    <FaButton @click="openCreate"><FaIcon name="i-ri:add-line" />新建分类</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <FaResponsiveTable
      v-loading="model.loading"
      :columns="columns"
      :data="model.adminCategories"
      row-key="id"
      table-root-class="max-w-full overflow-x-auto rounded-lg"
      table-class="min-w-[720px]"
      border stripe
      empty-text="还没有分类"
    >
      <template #cell-createdAt="{ row }">{{ formatTime(row.original.createdAt) }}</template>
      <template #cell-operation="{ row }">
        <div class="flex-center gap-2">
          <FaButton size="sm" variant="outline" @click="openEdit(row.original)">编辑</FaButton>
          <FaButton size="sm" variant="destructive" @click="confirmDelete(row.original)">删除</FaButton>
        </div>
      </template>
      <template #card="{ row }">
        <div class="flex flex-col gap-2 text-sm">
          <div class="flex items-center justify-between">
            <strong class="text-base">{{ row.name }}</strong>
            <span class="text-secondary-foreground/60">{{ row.questionCount }} 道题</span>
          </div>
          <div class="text-secondary-foreground/60">排序 {{ row.sort }} · 创建于 {{ formatTime(row.createdAt) }}</div>
          <div class="flex gap-2">
            <FaButton size="sm" variant="outline" @click="openEdit(row)">编辑</FaButton>
            <FaButton size="sm" variant="destructive" @click="confirmDelete(row)">删除</FaButton>
          </div>
        </div>
      </template>
    </FaResponsiveTable>
    <FaModal v-model="editOpen" :title="editTarget ? '编辑分类' : '新建分类'" :confirm-button-loading="saving" @confirm="submit">
      <div class="flex flex-col gap-3">
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">分类名称</span>
          <FaInput v-model="editName" maxlength="30" placeholder="如：计算机网络" />
        </label>
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">排序（越小越靠前）</span>
          <FaNumberField v-model="editSort" :min="0" :max="9999" />
        </label>
      </div>
    </FaModal>
  </FaPageMain>
</template>
