<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { CategoryView } from '../types'
import { FaButton, FaCard, FaCheckbox, FaIcon, FaInput, FaModal, FaNumberField, FaPageHeader, FaPageMain, FaTable, useFaModal } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { formatTime } from '../composables/utils'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model
const confirm = useFaModal()

const editOpen = ref(false)
const editTarget = ref<CategoryView | null>(null)
const editName = ref('')
const editSort = ref(0)
const saving = ref(false)

const selectedRows = ref<CategoryView[]>([])
const mergeOpen = ref(false)
const mergeTargetId = ref('')
const merging = ref(false)

const columns: TableColumn<CategoryView>[] = [
  { type: 'selection', fixed: 'left', width: 46 },
  { accessorKey: 'name', header: '分类名称', minWidth: 180, fixed: 'left' },
  { accessorKey: 'sort', header: '排序', width: 90 },
  { accessorKey: 'questionCount', header: '题目数', width: 100 },
  { accessorKey: 'createdAt', header: '创建时间', width: 180 },
  { id: 'operation', header: '操作', width: 160 },
]

const mergeSources = computed(() => selectedRows.value.filter(row => row.id !== mergeTargetId.value))
const mergeSourceQuestionTotal = computed(() =>
  mergeSources.value.reduce((total, row) => total + (Number(row.questionCount) || 0), 0))

function onSelectionChange(rows: CategoryView[]) {
  selectedRows.value = rows
}

function isSelected(row: CategoryView) {
  return selectedRows.value.some(item => item.id === row.id)
}

/** 移动端卡片没有表格勾选事件，用 FaCheckbox 直接维护同一份 selectedRows。 */
function toggleSelect(row: CategoryView, checked: boolean | 'indeterminate' | null | undefined) {
  if (checked === true) {
    if (!isSelected(row)) {
      selectedRows.value = [...selectedRows.value, row]
    }
  }
  else {
    selectedRows.value = selectedRows.value.filter(item => item.id !== row.id)
  }
}

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

function openMerge() {
  if (selectedRows.value.length < 2) {
    return
  }
  mergeTargetId.value = selectedRows.value[0].id
  mergeOpen.value = true
}

async function submitMerge() {
  if (!mergeTargetId.value || mergeSources.value.length === 0) {
    return
  }
  merging.value = true
  try {
    await model.mergeCategories(mergeTargetId.value, mergeSources.value.map(row => row.id))
    mergeOpen.value = false
    selectedRows.value = []
  }
  finally {
    merging.value = false
  }
}

onMounted(() => model.loadAdminCategories())
</script>

<template>
  <FaPageHeader title="题目分类" description="分类对所有题目共享；勾选多个分类可合并为一个，删除前需移出其中题目">
    <FaButton variant="outline" :disabled="selectedRows.length < 2" @click="openMerge">
      <FaIcon name="i-ri:merge-cells-horizontal" />合并所选<template v-if="selectedRows.length >= 2">（{{ selectedRows.length }}）</template>
    </FaButton>
    <FaButton @click="openCreate"><FaIcon name="i-ri:add-line" />新建分类</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <!-- FaResponsiveTable 的事件会落到包装 div 上无法透传（selectionChange 收不到），需要勾选事件的表格直接用 FaTable -->
    <div class="qb-desktop-only">
      <FaTable
        v-loading="model.loading"
        :columns="columns"
        :data="model.adminCategories"
        row-key="id"
        selectable
        multiple
        table-root-class="qb-table-scroll"
        table-class="qb-table-w720"
        border stripe
        empty-text="还没有分类"
        @selection-change="onSelectionChange"
      >
        <template #cell-createdAt="{ row }">{{ formatTime(row.original.createdAt) }}</template>
        <template #cell-operation="{ row }">
          <div class="flex-center gap-2">
            <FaButton size="sm" variant="outline" @click="openEdit(row.original)">编辑</FaButton>
            <FaButton size="sm" variant="destructive" @click="confirmDelete(row.original)">删除</FaButton>
          </div>
        </template>
      </FaTable>
    </div>
    <div class="qb-mobile-only">
      <div v-loading="model.loading" class="qb-mobile-list">
        <FaCard v-for="row in model.adminCategories" :key="row.id">
          <div class="qb-mobile-card">
            <div class="qb-mobile-card-head">
              <FaCheckbox :model-value="isSelected(row)" @change="toggleSelect(row, $event)" />
              <div class="qb-mobile-card-title">{{ row.name }}</div>
            </div>
            <div class="qb-mobile-card-meta">
              <span>排序 {{ row.sort }}</span>
              <span>{{ row.questionCount }} 道题</span>
              <span>{{ formatTime(row.createdAt) }}</span>
            </div>
            <div class="qb-mobile-card-actions">
              <FaButton size="sm" variant="outline" @click="openEdit(row)">编辑</FaButton>
              <FaButton size="sm" variant="destructive" @click="confirmDelete(row)">删除</FaButton>
            </div>
          </div>
        </FaCard>
        <div v-if="!model.loading && model.adminCategories.length === 0" class="qb-mobile-empty">
          还没有分类
        </div>
      </div>
    </div>
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
    <FaModal
      v-model="mergeOpen"
      title="合并分类"
      :confirm-button-loading="merging"
      confirm-button-text="确认合并"
      @confirm="submitMerge"
    >
      <div class="flex flex-col gap-3">
        <p class="text-sm text-secondary-foreground/80">
          选择一个作为保留的目标分类，其余 {{ mergeSources.length }} 个分类下的
          {{ mergeSourceQuestionTotal }} 道题将整体迁入目标分类，题单的分类引用一并改写，随后删除被合并的分类。
        </p>
        <div class="flex flex-col gap-2">
          <div
            v-for="row in selectedRows"
            :key="row.id"
            class="qb-merge-target"
            :class="{ 'qb-merge-target-active': row.id === mergeTargetId }"
            @click="mergeTargetId = row.id"
          >
            <FaIcon :name="row.id === mergeTargetId ? 'i-ri:checkbox-circle-fill' : 'i-ri:checkbox-blank-circle-line'" />
            <span class="font-medium">{{ row.name }}</span>
            <span class="qb-muted text-xs">{{ row.questionCount }} 道题</span>
          </div>
        </div>
      </div>
    </FaModal>
  </FaPageMain>
</template>
