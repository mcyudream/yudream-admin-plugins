<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { ProjectProgressModel } from '../composables/useProjectProgress'
import type { ProjectWorkDetail } from '../types'
import { FaButton, FaFileUpload, FaModal, FaPagination, FaTable, FaTag, FaTextarea } from '@yudream/components'
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
</script>

<template>
  <section class="pp-page">
    <section class="pp-toolbar">
      <div>
        <span>个人任务</span>
        <h2>我的任务</h2>
      </div>
      <FaButton variant="outline" :loading="model.loading" @click="model.loadMyTasks">刷新</FaButton>
    </section>

    <section class="pp-panel">
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
    </section>

    <section class="pp-panel">
      <header class="pp-panel-head">
        <div>
          <h3>已分配任务</h3>
          <span>完成任务后提交验收，通过后才计入完成进度</span>
        </div>
      </header>
      <FaTable row-key="id" table-root-class="max-w-full overflow-x-auto rounded-lg" table-class="min-w-[1310px]" border stripe column-visibility :columns="columns" :data="pagedTasks" empty-text="暂无分配给你的任务">
        <template #cell-project="{ row }">{{ model.projectName(row.original.projectId) }}</template>
        <template #cell-task="{ row }"><strong>{{ row.original.title }}</strong><div class="pp-table-sub">{{ row.original.description || '暂无说明' }}</div><div v-if="row.original.acceptanceSummary || row.original.acceptanceFiles.length" class="pp-material-box mt-3"><p>{{ row.original.acceptanceSummary || '暂无验收说明' }}</p><EvidenceFileList :model="model" :files="row.original.acceptanceFiles" compact /></div></template>
        <template #cell-status="{ row }"><div class="pp-chip-list"><FaTag variant="secondary">{{ model.detailStatusLabel(row.original) }}</FaTag><FaTag v-if="row.original.pendingAcceptance">待验收</FaTag></div></template>
        <template #cell-assignment="{ row }">{{ model.assignmentLabel(row.original) }}</template>
        <template #cell-dueAt="{ row }">{{ model.formatTime(row.original.dueAt) }}</template>
        <template #cell-operation="{ row }"><div class="pp-actions"><FaButton size="sm" variant="outline" @click="goCheckIn(row.original.projectId)">项目打卡</FaButton><FaButton v-if="model.canMinecraftCheckIn(row.original)" size="sm" variant="outline" @click="model.minecraftCheckIn(row.original.projectId)">MC 打卡</FaButton><FaButton size="sm" :disabled="!model.canSubmitAcceptance(row.original)" :loading="model.saving" @click="openSubmitAcceptance(row.original)">提交验收</FaButton></div></template>
      </FaTable>
      <FaPagination v-model:page="pagination.page" v-model:size="pagination.size" :total="model.myTasks.length" class="mt-3" />
    </section>

    <FaModal
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
          <FaTextarea v-model="model.acceptanceSubmitForm.summary" placeholder="说明完成内容、交付位置或需要验收的重点" class="w-full" />
        </label>
        <label>
          <span>验收附件</span>
          <FaFileUpload
            v-model="model.acceptanceFiles"
            multiple
            :max="6"
            :http-request="localUploadRequest"
            description="拖放或点击上传图片/文件"
          />
        </label>
      </div>
      <template #footer>
        <FaButton variant="outline" @click="submitVisible = false">取消</FaButton>
        <FaButton :loading="model.saving" @click="confirmSubmitAcceptance">提交验收</FaButton>
      </template>
    </FaModal>
  </section>
</template>
