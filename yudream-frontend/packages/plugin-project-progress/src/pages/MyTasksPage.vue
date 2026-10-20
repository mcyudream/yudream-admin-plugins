<script setup lang="ts">
import { YdLoadingOverlay, YdTablePageLayout } from '@yudream/components'
import type { ActionMenuItem, TableColumn } from '@yudream/components'
import type { ProjectProgressModel } from '../composables/useProjectProgress'
import type { ProjectWorkDetail } from '../types'
import { YdButton, YdFileUpload, YdModal, YdPagination, YdResponsiveEnhancedTable, YdTextarea } from '@yudream/components'
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import EvidenceFileList from '../components/EvidenceFileList.vue'

const props = defineProps<{
  model: ProjectProgressModel
}>()

const router = useRouter()
const submitVisible = ref(false)
const submittingDetail = ref<ProjectWorkDetail | null>(null)
const pagination = reactive({ page: 1, size: 10 })
const pagedTasks = computed(() => props.model.myTasks.slice((pagination.page - 1) * pagination.size, pagination.page * pagination.size))
const columns: TableColumn<ProjectWorkDetail>[] = [
  { id: 'project', header: '项目', width: 180, fixed: 'left' },
  { id: 'task', header: '任务', width: 340 },
  { id: 'status', header: '状态', width: 170 },
  { id: 'assignment', header: '分配方式', width: 150 },
  { id: 'dueAt', header: '截止时间', width: 170 },
  { id: 'operation', header: '操作', width: 300, align: 'center', fixed: 'right' },
]

watch(() => props.model.myTasks.length, total => { pagination.page = Math.min(pagination.page, Math.max(1, Math.ceil(total / pagination.size))) })
watch(() => pagination.size, () => { pagination.page = 1 })

async function goCheckIn(projectId: string) {
  await props.model.selectProjectById(projectId)
  await router.push({ name: 'platform-plugin-project-progress-check-ins' })
}

function openSubmitAcceptance(detail: ProjectWorkDetail) {
  props.model.acceptanceSubmitForm.summary = ''
  props.model.acceptanceFiles = []
  submittingDetail.value = detail
  submitVisible.value = true
}

async function confirmSubmitAcceptance() {
  if (!submittingDetail.value) {
    return
  }
  const ok = await props.model.submitAcceptance(submittingDetail.value)
  if (ok) {
    submitVisible.value = false
  }
}

function localUploadRequest() {
  return Promise.resolve({})
}
/** 行操作统一收进操作菜单 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '项目打卡', icon: 'morph:clipboard-check', onClick: row => goCheckIn((row as { projectId: string }).projectId) },
  { label: 'MC 打卡', icon: 'morph:gamepad', visible: row => props.model.canMinecraftCheckIn(row as never), onClick: row => props.model.minecraftCheckIn((row as { projectId: string }).projectId) },
  { label: '提交验收', icon: 'morph:send', disabled: row => !props.model.canSubmitAcceptance(row as never), onClick: row => openSubmitAcceptance(row as never) },
]
</script>

<template>
  <YdTablePageLayout title="我的任务" :stats-default-expanded="true" :show-search="false">
    <template #stats>
      <p class="pp-muted">完成任务后提交验收，通过后才计入完成进度</p>
      <div class="pp-stat-grid">
        <article class="pp-stat-card">
          <span>分配细分</span>
          <strong>{{ model.personalStats?.assignedDetails || 0 }}</strong>
        </article>
        <article class="pp-stat-card">
          <span>已完成细分</span>
          <strong>{{ model.personalStats?.completedDetails || 0 }}</strong>
        </article>
        <article class="pp-stat-card">
          <span>待验收</span>
          <strong>{{ model.personalStats?.pendingAcceptanceDetails || 0 }}</strong>
        </article>
        <article class="pp-stat-card">
          <span>验收通过</span>
          <strong>{{ model.personalStats?.acceptedReviews || 0 }}</strong>
        </article>
        <article class="pp-stat-card">
          <span>退回返工</span>
          <strong>{{ model.personalStats?.rejectedReviews || 0 }}</strong>
        </article>
        <article class="pp-stat-card">
          <span>项目打卡</span>
          <strong>{{ model.personalStats?.checkIns || 0 }}</strong>
        </article>
      </div>
    </template>

    <template #table>
      <YdLoadingOverlay :loading="model.loading">
        <YdResponsiveEnhancedTable row-key="id" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[1180px]" border stripe column-visibility :actions="rowActions" :columns="columns" :data="pagedTasks" empty-text="暂无分配给你的任务">
          <template #cell-project="{ row }">{{ model.projectName(row.original.projectId) }}</template>
          <template #cell-task="{ row }"><strong>{{ row.original.title }}</strong><div class="pp-table-sub">{{ row.original.description || '暂无说明' }}</div><div v-if="row.original.acceptanceSummary || row.original.acceptanceFiles.length" class="pp-material-box mt-3"><p>{{ row.original.acceptanceSummary || '暂无验收说明' }}</p><EvidenceFileList :model="model" :files="row.original.acceptanceFiles" compact /></div></template>
          <template #cell-status="{ row }"><div class="pp-chip-list"><span class="yd-status-tone" data-tone="neutral">{{ model.detailStatusLabel(row.original) }}</span><span v-if="row.original.pendingAcceptance" class="yd-status-tone" data-tone="warning">待验收</span></div></template>
          <template #cell-assignment="{ row }">{{ model.assignmentLabel(row.original) }}</template>
          <template #cell-dueAt="{ row }">{{ model.formatTime(row.original.dueAt) }}</template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </template>

    <template #footer>
      <YdPagination v-model:page="pagination.page" v-model:size="pagination.size" :total="model.myTasks.length" />
    </template>
  </YdTablePageLayout>

  <YdModal
    v-model="submitVisible"
    title="提交验收"
    class="max-w-[640px]"
    :show-confirm-button="false"
    show-cancel-button
    cancel-button-text="取消"
  >
    <div class="pp-form">
      <label>
        <span>验收说明</span>
        <YdTextarea v-model="model.acceptanceSubmitForm.summary" placeholder="说明完成内容、交付位置或需要验收的重点" class="w-full" />
      </label>
      <label>
        <span>验收附件</span>
        <YdFileUpload
          v-model="model.acceptanceFiles"
          multiple
          :max="6"
          :http-request="localUploadRequest"
          description="拖放或点击上传图片/文件"
        />
      </label>
    </div>
    <template #footer>
      <YdButton variant="outline" @click="submitVisible = false">取消</YdButton>
      <YdButton :loading="model.saving" @click="confirmSubmitAcceptance">提交验收</YdButton>
    </template>
  </YdModal>
</template>
