<script setup lang="ts">
import { YdLoadingOverlay, YdTablePageLayout } from '@yudream/components'
import type { TableColumn, ActionMenuItem } from '@yudream/components'
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { CategoryView } from '../types'
import { YdButton, YdCard, YdCheckbox, YdIcon, YdInput, YdModal, YdNumberField, useYdModal, YdAction, YdResponsiveEnhancedTable } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { formatTime } from '../composables/utils'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model
const confirm = useYdModal()

const editOpen = ref(false)
const editTarget = ref<CategoryView | null>(null)
const editName = ref('')
const editSort = ref(0)
const saving = ref(false)

const selectedRows = ref<CategoryView[]>([])
const rowSelection = ref<Record<string, boolean>>({})
const mergeOpen = ref(false)
const mergeTargetId = ref('')
const merging = ref(false)

const columns: TableColumn<CategoryView>[] = [
  { type: 'selection', fixed: 'left', width: 46 },
  { accessorKey: 'name', header: '分类名称', minWidth: 180, fixed: 'left' },
  { accessorKey: 'sort', header: '排序', width: 90 },
  { accessorKey: 'questionCount', header: '题目数', width: 100 },
  { accessorKey: 'createdAt', header: '创建时间', width: 180 },
]

const mergeSources = computed(() => selectedRows.value.filter(row => row.id !== mergeTargetId.value))
const mergeSourceQuestionTotal = computed(() =>
  mergeSources.value.reduce((total, row) => total + (Number(row.questionCount) || 0), 0))

function onSelectionChange(rows: CategoryView[]) {
  selectedRows.value = rows
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
    rowSelection.value = {}
  }
  finally {
    merging.value = false
  }
}

onMounted(() => model.loadAdminCategories())

/** 分类行操作统一收进操作菜单 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '编辑', icon: 'morph:pencil', onClick: row => openEdit(row as never) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => confirmDelete(row as never) },
]

</script>

<template>
  <div class="h-full min-h-0">
    <YdTablePageLayout title="题目分类" :show-search="false">
    <template #actions>
      <YdButton variant="outline" :disabled="selectedRows.length < 2" @click="openMerge">
      <YdIcon name="i-ri:merge-cells-horizontal" />合并所选<template v-if="selectedRows.length >= 2">（{{ selectedRows.length }}）</template>
    </YdButton>
    <YdButton @click="openCreate"><YdIcon name="i-ri:add-line" />新建分类</YdButton>
    </template>
    <template #table>
<YdLoadingOverlay :loading="model.loading"><YdResponsiveEnhancedTable
        :actions="rowActions"
 v-model:row-selection="rowSelection"
        
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
      <template #card="{ row, selected, selectDisabled, toggleSelected }">
            <YdCard class="w-full"><div class="qb-mobile-card"><div class="qb-mobile-card-head"><YdCheckbox :model-value="selected" :disabled="selectDisabled" @change="value => toggleSelected(value === true)" /><div class="qb-mobile-card-title">{{ row.name }}</div></div><div class="qb-mobile-card-meta"><span>排序 {{ row.sort }}</span><span>{{ row.questionCount }} 道题</span><span>{{ formatTime(row.createdAt) }}</span></div><div class="qb-mobile-card-actions"><YdButton size="sm" variant="outline" @click="openEdit(row)">编辑</YdButton><YdButton size="sm" variant="destructive" @click="confirmDelete(row)">删除</YdButton></div></div></YdCard>
          </template>
          </YdResponsiveEnhancedTable></YdLoadingOverlay>
    </template>
  </YdTablePageLayout>

    
    
    <YdModal v-model="editOpen" :title="editTarget ? '编辑分类' : '新建分类'" :confirm-button-loading="saving" @confirm="submit">
      <div class="flex flex-col gap-3">
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">分类名称</span>
          <YdInput v-model="editName" maxlength="30" placeholder="如：计算机网络" />
        </label>
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">排序（越小越靠前）</span>
          <YdNumberField v-model="editSort" :min="0" :max="9999" />
        </label>
      </div>
    </YdModal>
    <YdModal
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
          <YdAction
            v-for="row in selectedRows"
            :key="row.id"
            class="qb-merge-target"
            :class="{ 'qb-merge-target-active': row.id === mergeTargetId }"
            @click="mergeTargetId = row.id"
          >
            <YdIcon :name="row.id === mergeTargetId ? 'i-ri:checkbox-circle-fill' : 'i-ri:checkbox-blank-circle-line'" />
            <span class="font-medium">{{ row.name }}</span>
            <span class="qb-muted text-xs">{{ row.questionCount }} 道题</span>
          </YdAction>
        </div>
      </div>
    </YdModal>
  </div>
</template>
