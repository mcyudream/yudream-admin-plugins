<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { StudentProfile } from '../types'
import { FaButton, FaIcon, FaInput, FaLabel, FaModal, FaPageHeader, FaPageMain, FaPagination, FaSearchBar, FaTable, useFaModal } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { useAdminStudentProfiles } from '../composables/useAdminStudentProfiles'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useAdminStudentProfiles(props.sdk)
const confirm = useFaModal()
const formOpen = ref(false)

const columns: TableColumn<StudentProfile>[] = [
  { id: 'user', header: '用户', width: 220, fixed: 'left' },
  { accessorKey: 'studentName', header: '姓名', width: 120 },
  { accessorKey: 'studentNo', header: '学号', width: 160 },
  { accessorKey: 'className', header: '班级', width: 160 },
  { accessorKey: 'college', header: '学院', width: 180 },
  { id: 'updatedAt', header: '更新时间', width: 180 },
  { id: 'operation', header: '操作', width: 170, align: 'center', fixed: 'right' },
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
  <section class="student-info-page">
    <FaPageHeader title="学生档案管理" :description="`共 ${model.pagination.total} 条档案`" class="mb-0">
      <FaButton @click="openCreate">
        <FaIcon name="i-ri:add-line" />
        新增档案
      </FaButton>
    </FaPageHeader>

    <FaPageMain>
      <FaTable
        v-loading="model.loading"
        row-key="userId"
        table-root-class="rounded-lg overflow-hidden"
        table-class="min-w-[1170px]"
        border
        stripe
        column-visibility
        :columns="columns"
        :data="model.records"
      >
        <template #toolbar>
          <FaSearchBar class="w-full">
            <form class="student-info-filter-grid" @submit.prevent="model.search">
              <FaInput v-model="model.filters.keyword" clearable class="w-full" placeholder="用户 / 姓名 / 学号" @clear="model.search" />
              <FaInput v-model="model.filters.college" clearable class="w-full" placeholder="学院" @clear="model.search" />
              <FaInput v-model="model.filters.className" clearable class="w-full" placeholder="班级" @clear="model.search" />
              <div class="student-info-filter-actions">
                <FaButton type="button" variant="outline" @click="model.resetFilters">重置</FaButton>
                <FaButton type="submit">
                  <FaIcon name="i-ri:search-line" />
                  查询
                </FaButton>
              </div>
            </form>
          </FaSearchBar>
        </template>
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
        <template #cell-operation="{ row }">
          <div class="student-info-row-actions">
            <FaButton size="sm" variant="outline" @click="openEdit(row.original)">编辑</FaButton>
            <FaButton size="sm" variant="destructive" :loading="model.deletingUserId === row.original.userId" @click="confirmDelete(row.original)">删除</FaButton>
          </div>
        </template>
      </FaTable>

      <FaPagination
        v-model:page="model.pagination.page"
        v-model:size="model.pagination.size"
        :total="model.pagination.total"
        class="mt-3"
        @page-change="model.onPageChange"
        @size-change="model.onSizeChange"
      />
    </FaPageMain>

    <FaModal
      v-model="formOpen"
      :title="model.editingUserId ? '编辑学生档案' : '新增学生档案'"
      class="w-[min(560px,calc(100vw-2rem))]"
      :show-confirm-button="false"
      :show-cancel-button="false"
      @closed="model.resetForm"
    >
      <form class="student-info-form" @submit.prevent="save">
        <FaLabel label="用户 ID" class="student-info-field">
          <FaInput v-model="model.form.userId" class="w-full" :disabled="!!model.editingUserId" />
        </FaLabel>
        <FaLabel label="姓名" class="student-info-field"><FaInput v-model="model.form.studentName" class="w-full" maxlength="40" /></FaLabel>
        <FaLabel label="学号" class="student-info-field"><FaInput v-model="model.form.studentNo" class="w-full" maxlength="64" /></FaLabel>
        <FaLabel label="班级" class="student-info-field"><FaInput v-model="model.form.className" class="w-full" maxlength="80" /></FaLabel>
        <FaLabel label="学院" class="student-info-field"><FaInput v-model="model.form.college" class="w-full" maxlength="80" /></FaLabel>
        <div class="student-info-actions student-info-modal-actions">
          <FaButton type="button" variant="outline" @click="formOpen = false">取消</FaButton>
          <FaButton type="submit" :loading="model.saving">
            <FaIcon name="i-ri:save-3-line" />
            保存
          </FaButton>
        </div>
      </form>
    </FaModal>
  </section>
</template>
