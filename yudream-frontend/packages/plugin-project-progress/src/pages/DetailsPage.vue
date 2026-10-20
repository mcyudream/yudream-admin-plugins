<script setup lang="ts">
import { YdLoadingOverlay, YdTablePageLayout } from '@yudream/components'
import type { ActionMenuItem, TableColumn, YdTablePageAction } from '@yudream/components'
import type { ProjectProgressModel } from '../composables/useProjectProgress'
import type { ProjectWorkDetail } from '../types'

import { YdButton, YdInput, YdModal, YdNumberField, YdPagination, YdRadioGroup, YdResponsiveEnhancedTable, YdSelect, YdTag, YdTextarea, useYdModal } from '@yudream/components'
import { computed, reactive, ref, watch } from 'vue'
import EvidenceFileList from '../components/EvidenceFileList.vue'
import PeoplePicker from '../components/PeoplePicker.vue'

const props = defineProps<{ model: ProjectProgressModel }>()
const modalVisible = ref(false)
const confirm = useYdModal()
const pagination = reactive({ page: 1, size: 10 })
const modalTitle = computed(() => props.model.selectedDetailId ? '编辑工作细节' : '新建工作细节')
const projectOptions = computed(() => props.model.projects.map(project => ({ label: project.name, value: project.id })))
const editableStatusOptions = computed(() => props.model.projectStatusOptions.filter((item) => {
  const doneCode = props.model.selectedProject?.doneStatusCode
  return item.code !== doneCode || props.model.selectedDetail?.statusCode === item.code
}).map(item => ({ label: item.label, value: item.code })))
const assignmentOptions = [{ label: '用户认领', value: 'CLAIM' }, { label: '随机分配', value: 'RANDOM' }]
const scopeOptions = computed(() => props.model.detailForm.assignmentMode === 'CLAIM'
  ? [{ label: '所有人可认领', value: 'ALL' }, { label: '仅指定人员可认领', value: 'SELECTED' }]
  : [{ label: '从项目成员中随机', value: 'PROJECT_MEMBERS' }, { label: '从指定人员中随机', value: 'SELECTED' }])
const pagedRows = computed(() => props.model.details.slice((pagination.page - 1) * pagination.size, pagination.page * pagination.size))
const columns: TableColumn<ProjectWorkDetail>[] = [
  { id: 'detail', header: '工作细节', width: 340, fixed: 'left' },
  { id: 'status', header: '状态', width: 160 },
  { id: 'assignment', header: '分配方式', width: 150 },
  { id: 'assignees', header: '负责人', width: 240 },
  { id: 'acceptors', header: '验收人', width: 220 },
  { id: 'published', header: '发布状态', width: 120 },
  { id: 'dueAt', header: '截止时间', width: 170 },
  { id: 'operation', header: '操作', width: 300, align: 'center', fixed: 'right' },
]

/** 页面级操作：导出与新建细节 */
const pageActions = computed<YdTablePageAction[]>(() => [
  { label: '导出', variant: 'outline', disabled: !props.model.details.length, onClick: () => props.model.exportDetails() },
  { label: '新建细节', disabled: !props.model.selectedProjectId, onClick: () => createDetail() },
])

watch(() => props.model.details.length, total => { pagination.page = Math.min(pagination.page, Math.max(1, Math.ceil(total / pagination.size))) })
watch(() => pagination.size, () => { pagination.page = 1 })

async function selectProject(projectId: unknown) { pagination.page = 1; await props.model.selectProjectById(String(projectId || '')) }
function createDetail() { props.model.newDetail(); modalVisible.value = true }
function editDetail(detail: ProjectWorkDetail) { props.model.selectDetail(detail); modalVisible.value = true }
async function saveDetail() { await props.model.saveDetail(); modalVisible.value = false }
function changeAssignmentMode(value: unknown) { props.model.detailForm.candidateScope = value === 'CLAIM' ? 'ALL' : 'PROJECT_MEMBERS' }
function confirmDelete(detail: ProjectWorkDetail) {
  confirm.confirm({ title: '删除工作细节', content: `确认删除“${detail.title}”吗？删除后无法恢复。`, onConfirm: () => props.model.deleteDetail(detail) })
}
/** 行操作统一收进操作菜单，删除保持确认流程 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '编辑', icon: 'morph:pencil', onClick: row => editDetail(row as never) },
  { label: '发布', icon: 'morph:upload', disabled: row => Boolean((row as { published: boolean }).published), onClick: row => props.model.publish(row as never) },
  { label: '随机分配', icon: 'morph:shuffle', visible: row => (row as { assignmentMode: string }).assignmentMode === 'RANDOM', onClick: row => props.model.randomAssign(row as never) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => confirmDelete(row as never) },
]
</script>

<template>
  <YdTablePageLayout title="工作细节" :actions="pageActions">
    <template #search>
      <YdSelect :model-value="model.selectedProjectId" :options="projectOptions" placeholder="选择项目" class="pp-project-select" @update:model-value="selectProject" />
    </template>

    <template #table>
      <YdLoadingOverlay :loading="model.loading">
        <YdResponsiveEnhancedTable row-key="id" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[1480px]" border stripe column-visibility :actions="rowActions" :columns="columns" :data="pagedRows" empty-text="暂无工作细节">
          <template #cell-detail="{ row }"><strong>{{ row.original.title }}</strong><div class="pp-table-sub">{{ row.original.description || '暂无说明' }}</div><div v-if="row.original.acceptanceSummary || row.original.acceptanceFiles.length" class="pp-material-box mt-3"><p>{{ row.original.acceptanceSummary || '暂无验收说明' }}</p><EvidenceFileList :model="model" :files="row.original.acceptanceFiles" compact /></div></template>
          <template #cell-status="{ row }"><div class="pp-chip-list"><YdTag variant="secondary">{{ model.detailStatusLabel(row.original) }}</YdTag><YdTag v-if="row.original.pendingAcceptance">待验收</YdTag></div></template>
          <template #cell-assignment="{ row }">{{ model.assignmentLabel(row.original) }}</template>
          <template #cell-assignees="{ row }"><div class="pp-chip-list"><span v-if="!row.original.assigneeUserIds.length" class="pp-muted">未分配</span><YdTag v-for="user in model.userOptionsForIds(row.original.assigneeUserIds)" :key="user.id" variant="secondary">{{ model.userLabel(user) }}</YdTag></div></template>
          <template #cell-acceptors="{ row }"><div class="pp-chip-list"><YdTag v-if="!row.original.acceptorUserIds.length" variant="secondary">项目负责人</YdTag><YdTag v-for="user in model.userOptionsForIds(row.original.acceptorUserIds)" :key="user.id" variant="secondary">{{ model.userLabel(user) }}</YdTag></div></template>
          <template #cell-published="{ row }"><YdTag :variant="row.original.published ? 'default' : 'secondary'">{{ row.original.published ? '已发布' : '草稿' }}</YdTag></template>
          <template #cell-dueAt="{ row }">{{ model.formatTime(row.original.dueAt) }}</template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </template>

    <template #footer>
      <YdPagination v-model:page="pagination.page" v-model:size="pagination.size" :total="model.details.length" />
    </template>
  </YdTablePageLayout>

  <YdModal v-model="modalVisible" :title="modalTitle" class="max-w-[820px]" :show-confirm-button="false" :show-cancel-button="false">
    <div class="pp-form">
      <label><span>标题</span><YdInput v-model="model.detailForm.title" class="w-full" :disabled="!model.selectedProjectId" /></label>
      <label><span>说明</span><YdTextarea v-model="model.detailForm.description" class="w-full" :disabled="!model.selectedProjectId" /></label>
      <div class="pp-form-grid three">
        <label><span>当前状态</span><YdSelect v-model="model.detailForm.statusCode" :options="editableStatusOptions" class="w-full" :disabled="!model.selectedProjectId" /></label>
        <label><span>需要人数</span><YdNumberField v-model="model.detailForm.requiredAssigneeCount" :min="1" class="w-full" :disabled="!model.selectedProjectId" /></label>
        <label><span>截止时间</span><YdDatePicker v-model="model.detailForm.dueAt" show-time format="YYYY-MM-DD HH:mm:ss" class="pp-date-picker" :disabled="!model.selectedProjectId" /></label>
      </div>
      <label><span>分配方式</span><YdRadioGroup v-model="model.detailForm.assignmentMode" :options="assignmentOptions" class="pp-radio-grid" @change="changeAssignmentMode" /></label>
      <label><span>参与范围</span><YdRadioGroup v-model="model.detailForm.candidateScope" :options="scopeOptions" class="pp-radio-grid" /></label>
      <label v-if="model.detailForm.candidateScope === 'SELECTED'"><span>候选人员</span><PeoplePicker v-model="model.detailForm.candidateUserIds" :model="model" title="选择候选人员" placeholder="选择可参与人员" /></label>
      <label><span>当前负责人</span><div class="pp-chip-list"><span v-if="!model.detailForm.assigneeUserIds.length" class="pp-muted">发布后由认领或随机分配产生</span><YdTag v-for="user in model.userOptionsForIds(model.detailForm.assigneeUserIds)" :key="user.id" variant="secondary">{{ model.userLabel(user) }}</YdTag></div></label>
      <label><span>验收人</span><PeoplePicker v-model="model.detailForm.acceptorUserIds" :model="model" title="选择验收人" placeholder="留空则项目负责人可验收" /></label>
    </div>
    <template #footer><YdButton variant="outline" @click="modalVisible = false">取消</YdButton><YdButton :loading="model.saving" :disabled="!model.selectedProjectId" @click="saveDetail">保存细节</YdButton></template>
  </YdModal>
</template>
