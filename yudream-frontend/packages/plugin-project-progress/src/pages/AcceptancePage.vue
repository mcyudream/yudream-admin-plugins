<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { ProjectProgressModel } from '../composables/useProjectProgress'
import type { ProjectWorkDetail } from '../types'
import { FaButton, FaModal, FaPagination, FaTable, FaTag, FaTextarea } from '@yudream/components'
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
  { id: 'operation', header: '操作', width: 180, align: 'center', fixed: 'right' },
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

async function submitReview() {
  if (!reviewingDetail.value) return
  await props.model.review(reviewingDetail.value, accepted.value)
  reviewVisible.value = false
  clampPage(props.model.pendingAcceptance.length)
}
</script>

<template>
  <section class="pp-page">
    <section class="pp-toolbar">
      <div><span>验收处理</span><h2>任务验收</h2></div>
      <FaButton variant="outline" :loading="model.loading" @click="model.loadAcceptance">刷新</FaButton>
    </section>

    <section class="pp-panel">
      <header class="pp-panel-head"><div><h3>待验收任务</h3><span>只有已提交验收的任务会出现在这里</span></div></header>
      <FaTable v-loading="model.loading" row-key="id" table-root-class="max-w-full overflow-x-auto rounded-lg" table-class="min-w-[1490px]" border stripe column-visibility :columns="columns" :data="pagedRows" empty-text="暂无待验收任务">
        <template #cell-project="{ row }">{{ model.projectName(row.original.projectId) }}</template>
        <template #cell-task="{ row }"><strong>{{ row.original.title }}</strong><div class="pp-table-sub">{{ row.original.description || '暂无说明' }}</div></template>
        <template #cell-status="{ row }"><div class="pp-chip-list"><FaTag variant="secondary">{{ model.detailStatusLabel(row.original) }}</FaTag><FaTag>待验收</FaTag></div></template>
        <template #cell-assignees="{ row }"><div class="pp-chip-list"><FaTag v-for="user in model.userOptionsForIds(row.original.assigneeUserIds)" :key="user.id" variant="secondary">{{ model.userLabel(user) }}</FaTag></div></template>
        <template #cell-materials="{ row }"><div class="pp-material-box"><p>{{ row.original.acceptanceSummary || '暂无验收说明' }}</p><EvidenceFileList :model="model" :files="row.original.acceptanceFiles" compact /></div></template>
        <template #cell-dueAt="{ row }">{{ model.formatTime(row.original.dueAt) }}</template>
        <template #cell-operation="{ row }"><div class="pp-actions"><FaButton size="sm" @click="openReview(row.original, true)">通过</FaButton><FaButton size="sm" variant="destructive" @click="openReview(row.original, false)">退回</FaButton></div></template>
      </FaTable>
      <FaPagination v-model:page="pagination.page" v-model:size="pagination.size" :total="model.pendingAcceptance.length" class="mt-3" />
    </section>

    <FaModal v-model="reviewVisible" :title="accepted ? '验收通过' : '退回返工'" class="max-w-[560px]" :show-confirm-button="false" :show-cancel-button="false">
      <div class="pp-form"><label><span>处理说明</span><FaTextarea v-model="model.acceptanceForm.reason" class="w-full" :placeholder="accepted ? '填写通过说明' : '填写返工原因'" /></label></div>
      <template #footer><FaButton variant="outline" @click="reviewVisible = false">取消</FaButton><FaButton :variant="accepted ? 'default' : 'destructive'" :loading="model.saving" @click="submitReview">{{ accepted ? '确认通过' : '确认退回' }}</FaButton></template>
    </FaModal>
  </section>
</template>
