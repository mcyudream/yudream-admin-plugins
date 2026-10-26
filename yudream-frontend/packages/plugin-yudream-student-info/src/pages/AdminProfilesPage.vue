<script setup lang="ts">
import type { ActionMenuItem, SearchConfig, SearchFieldConfig, TableColumn, YdTablePageAction } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { StudentProfile } from '../types'
import { YdButton, YdDynamicSearchForm, YdForm, YdFormItem, YdIcon, YdInput, YdLoadingOverlay, YdModal, YdPagination, YdResponsiveEnhancedTable, YdTablePageLayout, useYdModal } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { useAdminStudentProfiles } from '../composables/useAdminStudentProfiles'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useAdminStudentProfiles(props.sdk)
const confirm = useYdModal()
const formOpen = ref(false)

/** ControlType 是 components 内部的字符串枚举，桥接层只出类型，这里用字面量收敛一次 */
type SearchFieldInput = Partial<Omit<SearchFieldConfig, 'controlType'>> & Pick<SearchFieldConfig, 'fieldName' | 'label'> & { controlType?: 'TEXT' | 'SELECT' | 'DATE_RANGE' | 'NUMBER_RANGE' | 'CHECKBOX' | 'RADIO' }

function searchField(config: SearchFieldInput): SearchFieldConfig {
  const { controlType = 'TEXT', order = 0, showWhenFolded = false, width = '200px', ...rest } = config
  return { ...rest, fieldName: config.fieldName, label: config.label, controlType: controlType as SearchFieldConfig['controlType'], order, showWhenFolded, width }
}

const searchQuery = ref<Record<string, any>>({
  keyword: model.filters.keyword ?? '',
  college: model.filters.college ?? '',
  className: model.filters.className ?? '',
})

const searchConfig: SearchConfig = {
  fields: [
    searchField({ fieldName: 'keyword', label: '关键词', placeholder: '用户 / 姓名 / 学号', order: 1, showWhenFolded: true, width: '240px' }),
    searchField({ fieldName: 'college', label: '学院', order: 2, width: '200px' }),
    searchField({ fieldName: 'className', label: '班级', order: 3, width: '200px' }),
  ],
}

function applySearch() {
  model.filters.keyword = String(searchQuery.value.keyword ?? '')
  model.filters.college = String(searchQuery.value.college ?? '')
  model.filters.className = String(searchQuery.value.className ?? '')
  model.search()
}

function resetSearch() {
  model.resetFilters()
  searchQuery.value = { keyword: '', college: '', className: '' }
}

const columns: TableColumn<StudentProfile>[] = [
  { id: 'user', header: '用户', width: 220 },
  { accessorKey: 'studentName', header: '姓名', width: 120 },
  { accessorKey: 'studentNo', header: '学号', width: 160 },
  { accessorKey: 'className', header: '班级', width: 160 },
  { accessorKey: 'college', header: '学院', width: 180 },
  { id: 'updatedAt', header: '更新时间', width: 180, defaultHidden: true },
]

/** 行操作统一收进操作菜单，删除保持确认流程 */
const rowActions: ActionMenuItem<StudentProfile>[] = [
  { label: '编辑', icon: 'morph:pencil', onClick: row => openEdit(row) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => confirmDelete(row) },
]

const pageActions: YdTablePageAction[] = [
  { label: '新增档案', icon: 'morph:plus', onClick: openCreate },
]

function openCreate() {
  model.resetForm()
  formOpen.value = true
}

function openEdit(row: StudentProfile) {
  model.edit(row)
  formOpen.value = true
}

async function save() {
  await model.save()
  if (!model.saving && !model.editingUserId && !model.form.userId) {
    formOpen.value = false
  }
}

function confirmDelete(row: StudentProfile) {
  confirm.confirm({
    title: '删除学生档案',
    content: `确认删除“${model.displayName(row)}”的学生档案吗？该操作不可撤销。`,
    onConfirm: () => model.remove(row),
  })
}

onMounted(model.load)
</script>

<template>
  <YdTablePageLayout title="学生档案管理" :actions="pageActions">
    <template #search="{ fold }">
      <YdDynamicSearchForm
        v-model="searchQuery"
        :config="searchConfig"
        :fold="fold"
        @search="applySearch"
        @reset="resetSearch"
      />
    </template>

    <template #table>
      <YdLoadingOverlay :loading="model.loading">
        <YdResponsiveEnhancedTable
          row-key="userId"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[1060px]"
          border
          stripe
          column-visibility
          :actions="rowActions"
          :columns="columns"
          :data="model.records"
          empty-text="暂无学生档案"
        >
          <template #cell-user="{ row }">
            <div class="student-info-user-cell">
              <strong>{{ model.displayName(row.original) }}</strong>
              <span>ID {{ row.original.userId }}{{ row.original.email ? ` / ${row.original.email}` : '' }}</span>
            </div>
          </template>
          <template #cell-studentName="{ row }">{{ row.original.studentName || '-' }}</template>
          <template #cell-studentNo="{ row }">{{ row.original.studentNo || '-' }}</template>
          <template #cell-className="{ row }">{{ row.original.className || '-' }}</template>
          <template #cell-college="{ row }">{{ row.original.college || '-' }}</template>
          <template #cell-updatedAt="{ row }">{{ model.formatTime(row.original.updatedAt) }}</template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </template>

    <template #footer>
      <YdPagination
        v-model:page="model.pagination.page"
        v-model:size="model.pagination.size"
        :total="model.pagination.total"
        @page-change="model.onPageChange"
        @size-change="model.onSizeChange"
      />
    </template>
  </YdTablePageLayout>

  <YdModal
    v-model="formOpen"
    :title="model.editingUserId ? '编辑学生档案' : '新增学生档案'"
    class="w-[min(560px,calc(100vw-2rem))]"
    :show-confirm-button="false"
    :show-cancel-button="false"
    @closed="model.resetForm"
  >
    <YdForm :model="model.form" layout="vertical">
      <div class="flex flex-col gap-4">
        <YdFormItem label="用户 ID" required>
          <YdInput v-model="model.form.userId" :disabled="!!model.editingUserId" />
        </YdFormItem>
        <YdFormItem label="姓名">
          <YdInput v-model="model.form.studentName" maxlength="40" />
        </YdFormItem>
        <YdFormItem label="学号">
          <YdInput v-model="model.form.studentNo" maxlength="64" />
        </YdFormItem>
        <YdFormItem label="班级">
          <YdInput v-model="model.form.className" maxlength="80" />
        </YdFormItem>
        <YdFormItem label="学院">
          <YdInput v-model="model.form.college" maxlength="80" />
        </YdFormItem>
        <div class="flex justify-end gap-2">
          <YdButton variant="outline" @click="formOpen = false">
            取消
          </YdButton>
          <YdButton :loading="model.saving" @click="save">
            <YdIcon name="morph:save" />
            保存
          </YdButton>
        </div>
      </div>
    </YdForm>
  </YdModal>
</template>
