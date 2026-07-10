<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { ProjectProgressModel } from '../composables/useProjectProgress'
import type { ProjectWorkDetail } from '../types'
import { DatePicker as ADatePicker } from '@arco-design/web-vue'
import { FaButton, FaInput, FaModal, FaNumberField, FaPagination, FaRadioGroup, FaSelect, FaTable, FaTag, FaTextarea, useFaModal } from '@yudream/components'
import { computed, reactive, ref, watch } from 'vue'
import EvidenceFileList from '../components/EvidenceFileList.vue'
import PeoplePicker from '../components/PeoplePicker.vue'

const props = defineProps<{ model: ProjectProgressModel }>()
const modalVisible = ref(false)
const confirm = useFaModal()
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
</script>

<template>
  <section class="pp-page">
    <section class="pp-toolbar"><div><span>任务拆分</span><h2>工作细节</h2></div><div class="pp-actions"><FaSelect :model-value="model.selectedProjectId" :options="projectOptions" placeholder="选择项目" class="pp-project-select" @update:model-value="selectProject" /><FaButton variant="outline" :loading="model.loading" @click="model.load">刷新</FaButton><FaButton variant="outline" :disabled="!model.details.length" @click="model.exportDetails">导出</FaButton><FaButton :disabled="!model.selectedProjectId" @click="createDetail">新建细节</FaButton></div></section>

    <section class="pp-panel">
      <FaTable v-loading="model.loading" row-key="id" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[1740px]" border stripe column-visibility :columns="columns" :data="pagedRows" empty-text="暂无工作细节">
        <template #cell-detail="{ row }"><strong>{{ row.original.title }}</strong><div class="pp-table-sub">{{ row.original.description || '暂无说明' }}</div><div v-if="row.original.acceptanceSummary || row.original.acceptanceFiles.length" class="pp-material-box mt-3"><p>{{ row.original.acceptanceSummary || '暂无验收说明' }}</p><EvidenceFileList :model="model" :files="row.original.acceptanceFiles" compact /></div></template>
        <template #cell-status="{ row }"><div class="pp-chip-list"><FaTag variant="secondary">{{ model.detailStatusLabel(row.original) }}</FaTag><FaTag v-if="row.original.pendingAcceptance">待验收</FaTag></div></template>
        <template #cell-assignment="{ row }">{{ model.assignmentLabel(row.original) }}</template>
        <template #cell-assignees="{ row }"><div class="pp-chip-list"><span v-if="!row.original.assigneeUserIds.length" class="pp-muted">未分配</span><FaTag v-for="user in model.userOptionsForIds(row.original.assigneeUserIds)" :key="user.id" variant="secondary">{{ model.userLabel(user) }}</FaTag></div></template>
        <template #cell-acceptors="{ row }"><div class="pp-chip-list"><FaTag v-if="!row.original.acceptorUserIds.length" variant="secondary">项目负责人</FaTag><FaTag v-for="user in model.userOptionsForIds(row.original.acceptorUserIds)" :key="user.id" variant="secondary">{{ model.userLabel(user) }}</FaTag></div></template>
        <template #cell-published="{ row }"><FaTag :variant="row.original.published ? 'default' : 'secondary'">{{ row.original.published ? '已发布' : '草稿' }}</FaTag></template>
        <template #cell-dueAt="{ row }">{{ model.formatTime(row.original.dueAt) }}</template>
        <template #cell-operation="{ row }"><div class="pp-actions"><FaButton size="sm" variant="outline" @click="editDetail(row.original)">编辑</FaButton><FaButton size="sm" variant="outline" :disabled="row.original.published" @click="model.publish(row.original)">发布</FaButton><FaButton v-if="row.original.assignmentMode === 'RANDOM'" size="sm" variant="outline" @click="model.randomAssign(row.original)">随机分配</FaButton><FaButton size="sm" variant="destructive" @click="confirmDelete(row.original)">删除</FaButton></div></template>
      </FaTable>
      <FaPagination v-model:page="pagination.page" v-model:size="pagination.size" :total="model.details.length" class="mt-3" />
    </section>

    <FaModal v-model="modalVisible" :title="modalTitle" class="max-w-[820px]" :show-confirm-button="false" :show-cancel-button="false">
      <div class="pp-form">
        <label><span>标题</span><FaInput v-model="model.detailForm.title" class="w-full" :disabled="!model.selectedProjectId" /></label>
        <label><span>说明</span><FaTextarea v-model="model.detailForm.description" class="w-full" :disabled="!model.selectedProjectId" /></label>
        <div class="pp-form-grid three">
          <label><span>当前状态</span><FaSelect v-model="model.detailForm.statusCode" :options="editableStatusOptions" class="w-full" :disabled="!model.selectedProjectId" /></label>
          <label><span>需要人数</span><FaNumberField v-model="model.detailForm.requiredAssigneeCount" :min="1" class="w-full" :disabled="!model.selectedProjectId" /></label>
          <label><span>截止时间</span><ADatePicker v-model="model.detailForm.dueAt" show-time format="YYYY-MM-DD HH:mm:ss" :disabled="!model.selectedProjectId" /></label>
        </div>
        <label><span>分配方式</span><FaRadioGroup v-model="model.detailForm.assignmentMode" :options="assignmentOptions" class="pp-radio-grid" @change="changeAssignmentMode" /></label>
        <label><span>参与范围</span><FaRadioGroup v-model="model.detailForm.candidateScope" :options="scopeOptions" class="pp-radio-grid" /></label>
        <label v-if="model.detailForm.candidateScope === 'SELECTED'"><span>候选人员</span><PeoplePicker v-model="model.detailForm.candidateUserIds" :model="model" title="选择候选人员" placeholder="选择可参与人员" /></label>
        <label><span>当前负责人</span><div class="pp-chip-list"><span v-if="!model.detailForm.assigneeUserIds.length" class="pp-muted">发布后由认领或随机分配产生</span><FaTag v-for="user in model.userOptionsForIds(model.detailForm.assigneeUserIds)" :key="user.id" variant="secondary">{{ model.userLabel(user) }}</FaTag></div></label>
        <label><span>验收人</span><PeoplePicker v-model="model.detailForm.acceptorUserIds" :model="model" title="选择验收人" placeholder="留空则项目负责人可验收" /></label>
      </div>
      <template #footer><FaButton variant="outline" @click="modalVisible = false">取消</FaButton><FaButton :loading="model.saving" :disabled="!model.selectedProjectId" @click="saveDetail">保存细节</FaButton></template>
    </FaModal>
  </section>
</template>
