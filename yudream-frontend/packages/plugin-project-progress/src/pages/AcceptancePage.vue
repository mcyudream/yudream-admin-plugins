<script setup lang="ts">
// yudream-self-chrome 页面自管页头与卡片
import { YdLoadingOverlay, YdTablePageLayout } from '@yudream/components'
import type { ActionMenuItem, TableColumn } from '@yudream/components'
import type { ProjectProgressModel } from '../composables/useProjectProgress'
import type { ProjectWorkDetail } from '../types'
import { YdButton, YdModal, YdPagination, YdResponsiveEnhancedTable, YdTag, YdTextarea } from '@yudream/components'
import { computed, reactive, ref, watch } from 'vue'
import EvidenceFileList from '../components/EvidenceFileList.vue'

const props = defineProps<{ model: ProjectProgressModel }>()
const reviewVisible = ref(false)
const reviewingDetail = ref<ProjectWorkDetail | null>(null)
const accepted = ref(true)
const pagination = reactive({ page: 1, size: 10 })
const pagedRows = computed(() => props.model.pendingAcceptance.slice((pagination.page - 1) * pagination.size, pagination.page * pagination.size))
const columns: TableColumn<ProjectWorkDetail>[] = [
  { id: 'project', header: '项目', width: 180, fixed: 'left' },
  { id: 'task', header: '任务', width: 280 },
  { id: 'status', header: '状态', width: 160 },
  { id: 'assignees', header: '负责人', width: 240 },
  { id: 'materials', header: '验收材料', width: 280 },
  { id: 'dueAt', header: '截止时间', width: 170 },
]

watch(() => props.model.pendingAcceptance.length, total => clampPage(total))
watch(() => pagination.size, () => { pagination.page = 1 })

function clampPage(total: number) {
  pagination.page = Math.min(pagination.page, Math.max(1, Math.ceil(total / pagination.size)))
}

function openReview(detail: ProjectWorkDetail, nextAccepted: boolean) {
  reviewingDetail.value = detail
  accepted.value = nextAccepted
  props.model.acceptanceForm.reason = ''
  reviewVisible.value = true
}

/** 行操作：验收通过 / 退回返工 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '通过', icon: 'morph:check', onClick: row => openReview(row as never, true) },
  { label: '退回', icon: 'morph:undo', danger: true, onClick: row => openReview(row as never, false) },
]

async function submitReview() {
  if (!reviewingDetail.value) return
  await props.model.review(reviewingDetail.value, accepted.value)
  reviewVisible.value = false
  clampPage(props.model.pendingAcceptance.length)
}
</script>

<template>

  <YdTablePageLayout title="任务验收" :show-search="false">
    <template #table>
      <YdLoadingOverlay :loading="model.loading"><YdResponsiveEnhancedTable row-key="id" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[1240px]" border stripe column-visibility :actions="rowActions" :columns="columns" :data="pagedRows" empty-text="暂无待验收任务">
        <template #cell-project="{ row }">{{ model.projectName(row.original.projectId) }}</template>
        <template #cell-task="{ row }"><strong>{{ row.original.title }}</strong><div class="pp-table-sub">{{ row.original.description || '暂无说明' }}</div></template>
        <template #cell-status="{ row }"><div class="pp-chip-list"><span class="yd-status-tone" data-tone="info">待验收</span><span class="yd-status-tone" data-tone="neutral">{{ model.detailStatusLabel(row.original) }}</span></div></template>
        <template #cell-assignees="{ row }"><div class="pp-chip-list"><YdTag v-for="user in model.userOptionsForIds(row.original.assigneeUserIds)" :key="user.id" variant="secondary">{{ model.userLabel(user) }}</YdTag></div></template>
        <template #cell-materials="{ row }"><div class="pp-material-box"><p>{{ row.original.acceptanceSummary || '暂无验收说明' }}</p><EvidenceFileList :model="model" :files="row.original.acceptanceFiles" compact /></div></template>
        <template #cell-dueAt="{ row }">{{ model.formatTime(row.original.dueAt) }}</template>
      </YdResponsiveEnhancedTable></YdLoadingOverlay>
    </template>
    <template #footer>
      <YdPagination v-model:page="pagination.page" v-model:size="pagination.size" :total="model.pendingAcceptance.length" />
    </template>
  </YdTablePageLayout>

    <YdModal v-model="reviewVisible" :title="accepted ? '验收通过' : '退回返工'" class="max-w-[560px]" :show-confirm-button="false" :show-cancel-button="false">
      <div class="pp-form"><label><span>处理说明</span><YdTextarea v-model="model.acceptanceForm.reason" class="w-full" :placeholder="accepted ? '填写通过说明' : '填写返工原因'" /></label></div>
      <template #footer><YdButton variant="outline" @click="reviewVisible = false">取消</YdButton><YdButton :variant="accepted ? 'default' : 'destructive'" :loading="model.saving" @click="submitReview">{{ accepted ? '确认通过' : '确认退回' }}</YdButton></template>
    </YdModal>
</template>
