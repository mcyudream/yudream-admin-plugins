<script setup lang="ts">
import { YdLoadingOverlay } from '@yudream/components'
import type { ActionMenuItem, TableColumn } from '@yudream/components'
import type { ProjectProgressModel } from '../composables/useProjectProgress'
import type { ProjectCheckIn } from '../types'
import { useYdModal, YdButton, YdFileUpload, YdInput, YdPageHeader, YdPageMain, YdPagination, YdRadioGroup, YdResponsiveEnhancedTable, YdSelect, YdTag, YdTextarea } from '@yudream/components'
import { computed, reactive, watch } from 'vue'

const props = defineProps<{ model: ProjectProgressModel }>()
const pagination = reactive({ page: 1, size: 10 })
const modal = useYdModal()
const projectOptions = computed(() => props.model.projects.map(project => ({ label: project.name, value: project.id })))
const manualTypeOptions = computed(() => {
  const allowed = props.model.selectedProject?.allowedCheckInTypes || []
  return [
    { label: '图片', value: 'IMAGE' },
    { label: '文件', value: 'FILE' },
    { label: '定位', value: 'LOCATION' },
  ].filter(item => allowed.includes(item.value))
})
const pagedRows = computed(() => props.model.checkIns.slice((pagination.page - 1) * pagination.size, pagination.page * pagination.size))
const columns: TableColumn<ProjectCheckIn>[] = [
  { id: 'user', header: '打卡人', width: 170, fixed: 'left' },
  { id: 'type', header: '类型', width: 130 },
  { id: 'summary', header: '说明', width: 420 },
  { id: 'createdAt', header: '时间', width: 180 },
]

columns.push(
  { id: 'reviewStatus', header: '审核状态', width: 120 },
)

watch(manualTypeOptions, (options) => {
  if (options.length && !options.some(item => item.value === props.model.checkInForm.type)) props.model.checkInForm.type = options[0].value
}, { immediate: true })
watch(() => props.model.checkIns.length, total => { pagination.page = Math.min(pagination.page, Math.max(1, Math.ceil(total / pagination.size))) })
watch(() => pagination.size, () => { pagination.page = 1 })

async function selectProject(projectId: unknown) {
  pagination.page = 1
  await props.model.selectProjectById(String(projectId || ''))
}

function typeLabel(value: string) {
  return { IMAGE: '图片', FILE: '文件', LOCATION: '定位', MINECRAFT_ONLINE: 'MC 在线时长' }[value] || value
}

function fileNames(files: ProjectCheckIn['files']) { return files.map(file => file.filename).join('、') }
function localUploadRequest() { return Promise.resolve({}) }
function reviewLabel(value: string) { return value === 'REJECTED' ? '已驳回' : '已通过' }
function confirmReject(record: ProjectCheckIn) { modal.confirm({ title: '驳回打卡', content: '确认驳回这条打卡记录吗？', onConfirm: () => props.model.rejectCheckIn(record) }) }
function confirmDelete(record: ProjectCheckIn) { modal.confirm({ title: '删除打卡', content: '删除后无法恢复，确认删除这条打卡记录吗？', onConfirm: () => props.model.deleteCheckIn(record) }) }
/** 行操作统一收进操作菜单，删除保持确认流程 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '驳回', icon: 'morph:ban', danger: true, visible: row => (row as { reviewStatus: string }).reviewStatus !== 'REJECTED', onClick: row => confirmReject(row as never) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => confirmDelete(row as never) },
]
</script>

<template>
  <YdPageHeader title="打卡记录" class="mb-0">
    <YdSelect
      :model-value="model.selectedProjectId"
      :options="projectOptions"
      placeholder="选择项目"
      class="w-[100%] sm:w-[200px]"
      @update:model-value="selectProject"
    />
    <YdButton variant="outline" :disabled="!props.model.checkIns.length" @click="props.model.exportCheckIns()">
      导出
    </YdButton>
  </YdPageHeader>
  <YdPageMain>
    <div class="flex flex-col gap-3">
      <!-- 提交打卡：独立工作流面板（普通页面骨架，非表格布局插槽），提交后新记录出现在下方表格 -->
      <section class="pp-panel flex-none">
        <header class="pp-panel-head"><div><h3>提交打卡</h3><span>{{ model.selectedProject?.name || '请选择项目' }}</span></div></header>
        <div class="pp-form">
          <label><span>打卡类型</span><YdRadioGroup v-model="model.checkInForm.type" :options="manualTypeOptions" :disabled="!model.selectedProjectId" class="pp-radio-grid" /></label>
          <div v-if="model.selectedProjectId && !manualTypeOptions.length" class="pp-empty">该项目未开放手动打卡</div>
          <label><span>说明</span><YdTextarea v-model="model.checkInForm.summary" class="w-full" /></label>
          <label v-if="model.checkInForm.type !== 'LOCATION'"><span>上传证明</span><YdFileUpload v-model="model.evidenceFiles" :max="1" :http-request="localUploadRequest" description="拖放或点击选择打卡证明" /></label>
          <template v-if="model.checkInForm.type === 'LOCATION'">
            <label><span>地址</span><YdInput v-model="model.checkInForm.address" class="w-full" placeholder="定位后可补充地点说明" /></label>
            <div class="pp-actions"><YdButton variant="outline" :loading="model.saving" @click="model.useCurrentLocation">获取当前位置</YdButton><span class="pp-muted">{{ model.checkInForm.latitude && model.checkInForm.longitude ? `${model.checkInForm.latitude}, ${model.checkInForm.longitude}` : '尚未获取定位' }}</span></div>
          </template>
          <div class="pp-actions"><YdButton :loading="model.saving" :disabled="!model.selectedProjectId || !manualTypeOptions.length" @click="model.submitCheckIn">提交打卡</YdButton><YdButton variant="outline" :disabled="!model.canProjectMinecraftCheckIn() || model.saving" @click="model.minecraftCheckIn()">MC 在线时长打卡</YdButton></div>
        </div>
      </section>

      <YdLoadingOverlay :loading="model.loading">
        <YdResponsiveEnhancedTable row-key="id" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[820px]" border stripe column-visibility :actions="rowActions" :columns="columns" :data="pagedRows" empty-text="暂无打卡记录">
          <template #cell-user="{ row }">{{ model.userLabel(model.usersById[row.original.userId]) }}</template>
          <template #cell-type="{ row }"><YdTag variant="secondary">{{ typeLabel(row.original.type) }}</YdTag></template>
          <template #cell-summary="{ row }"><strong>{{ row.original.summary || '无说明' }}</strong><div v-if="row.original.location" class="pp-table-sub">{{ row.original.location.address }}</div><div v-if="row.original.minecraft" class="pp-table-sub">{{ model.serverLabel(row.original.minecraft.serverId) }} · {{ model.minecraftScopeLabel(row.original.minecraft) }} · 有效在线 {{ model.minutes(row.original.minecraft.effectiveOnlineMillis) }}<template v-if="model.minecraftWindowLabel(row.original.minecraft)"> · 统计自 {{ model.minecraftWindowLabel(row.original.minecraft) }}</template></div><div v-if="model.minecraftSubServerSummary(row.original.minecraft)" class="pp-table-sub">子服累计 {{ model.minecraftSubServerSummary(row.original.minecraft) }}</div><div v-if="row.original.files.length" class="pp-table-sub">{{ fileNames(row.original.files) }}</div></template>
          <template #cell-createdAt="{ row }">{{ model.formatTime(row.original.createdAt) }}</template>
          <template #cell-reviewStatus="{ row }"><YdTag :variant="row.original.reviewStatus === 'REJECTED' ? 'destructive' : 'secondary'">{{ reviewLabel(row.original.reviewStatus) }}</YdTag></template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>

      <div class="flex justify-end">
        <YdPagination v-model:page="pagination.page" v-model:size="pagination.size" :total="model.checkIns.length" />
      </div>
    </div>
  </YdPageMain>
</template>
