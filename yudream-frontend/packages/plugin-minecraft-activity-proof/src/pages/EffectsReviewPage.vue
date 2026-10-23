<script setup lang="ts">
import { YdLoadingOverlay, YdTablePageLayout } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { ActionMenuItem, TableColumn } from '@yudream/components'
import type { ActivityEffect } from '../types'
import { YdButton, YdIcon, YdModal, YdPagination, YdResponsiveEnhancedTable, YdSelect, YdTag, YdTextarea, useYdModal, useYdToast } from '@yudream/components'
import { onMounted, reactive, ref } from 'vue'
import { createActivityProofApi } from '../api/activity-proof-api'
import { formatTime } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
}>()

const toast = useYdToast()
const confirm = useYdModal()
const api = createActivityProofApi(props.sdk)

const loading = ref(false)
const records = ref<ActivityEffect[]>([])
const pager = reactive({ page: 1, size: 10, total: 0 })
const filters = reactive({ activityId: '', status: '' })
const reviewVisible = ref(false)
const reviewAccept = ref(true)
const reviewNote = ref('')
const reviewTarget = ref<ActivityEffect | null>(null)
const reviewSaving = ref(false)

const columns: TableColumn<ActivityEffect>[] = [
  { id: 'activity', header: '活动', width: 200, fixed: 'left' },
  { id: 'text', header: '效果说明', minWidth: 220 },
  { id: 'attachments', header: '附件', width: 240 },
  { id: 'submitted', header: '提交时间', width: 160 },
  { id: 'status', header: '状态', width: 100 },
]

const rowActions: ActionMenuItem<unknown>[] = [
  { label: '采纳', icon: 'morph:check', visible: row => (row as ActivityEffect).status === 'submitted', onClick: row => openReview(row as ActivityEffect, true) },
  { label: '驳回', icon: 'morph:circle-x', visible: row => (row as ActivityEffect).status === 'submitted', onClick: row => openReview(row as ActivityEffect, false) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => remove(row as ActivityEffect) },
]

onMounted(load)

async function load() {
  loading.value = true
  try {
    const data = await api.admin.effects({
      activityId: filters.activityId || undefined,
      status: filters.status || undefined,
      page: pager.page,
      size: pager.size,
    })
    records.value = data.records ?? []
    pager.total = data.total ?? 0
    if (records.value.length === 0 && pager.page > 1) {
      pager.page = Math.max(1, Math.ceil(pager.total / pager.size))
      await load()
    }
  }
  catch (cause) {
    toast.error(errorMessage(cause, '效果列表加载失败'))
  }
  finally {
    loading.value = false
  }
}

function openReview(effect: ActivityEffect, accept: boolean) {
  reviewTarget.value = effect
  reviewAccept.value = accept
  reviewNote.value = ''
  reviewVisible.value = true
}

async function submitReview() {
  if (!reviewTarget.value) {
    return
  }
  reviewSaving.value = true
  try {
    await api.admin.reviewEffect(reviewTarget.value.id, {
      accept: reviewAccept.value,
      note: reviewNote.value.trim(),
    })
    toast.success(reviewAccept.value ? '已采纳' : '已驳回（附件同步清理）')
    reviewVisible.value = false
    await load()
  }
  catch (cause) {
    toast.error(errorMessage(cause, '审核操作失败'))
  }
  finally {
    reviewSaving.value = false
  }
}

function remove(effect: ActivityEffect) {
  confirm.confirm({
    title: '删除效果提交',
    content: `确认删除这条效果提交吗？附件文件将一并清理，不可恢复。`,
    onConfirm: async () => {
      try {
        await api.admin.deleteEffect(effect.id)
        toast.success('已删除')
        await load()
      }
      catch (cause) {
        toast.error(errorMessage(cause, '删除失败'))
      }
    },
  })
}

function attachmentUrl(effect: ActivityEffect, index: number) {
  return api.downloadUrl(`/admin/effects/${encodeURIComponent(effect.id)}/attachments/${index}`)
}

function statusText(status: string) {
  switch (status) {
    case 'accepted':
      return '已采纳'
    case 'rejected':
      return '未采纳'
    default:
      return '待审核'
  }
}

function errorMessage(cause: unknown, fallback: string): string {
  const data = cause as { response?: { data?: { message?: unknown } }, message?: unknown } | null
  const bodyMessage = data?.response?.data?.message
  if (typeof bodyMessage === 'string' && bodyMessage.trim()) {
    return bodyMessage
  }
  if (typeof data?.message === 'string' && data.message.trim()) {
    return data.message
  }
  return fallback
}
</script>

<template>
  <YdTablePageLayout title="活动效果审核">
    <template #search>
      <div class="flex w-full flex-wrap items-center gap-2">
        <YdSelect
          v-model="filters.status"
          placeholder="全部状态"
          clearable
          class="w-40"
          :options="[
            { label: '待审核', value: 'submitted' },
            { label: '已采纳', value: 'accepted' },
            { label: '未采纳', value: 'rejected' },
          ]"
          @change="pager.page = 1; load()"
        />
        <YdButton variant="outline" @click="pager.page = 1; load()">
          <YdIcon name="i-ri:search-line" />查询
        </YdButton>
      </div>
    </template>
    <template #table>
      <YdLoadingOverlay :loading="loading">
        <YdResponsiveEnhancedTable
          :actions="rowActions"
          row-key="id"
          table-root-class="proof-table-scroll"
          table-class="proof-table-w1280"
          border
          stripe
          :columns="columns"
          :data="records"
          empty-text="暂无效果提交"
        >
          <template #cell-activity="{ row }">
            <strong>{{ row.original.activityTitle || row.original.activityId }}</strong>
            <div class="text-xs text-muted-foreground">提交人：{{ row.original.submittedBy }}</div>
          </template>
          <template #cell-text="{ row }">
            <span class="line-clamp-3 whitespace-pre-wrap text-sm">{{ row.original.text || '（无文字说明）' }}</span>
            <div v-if="row.original.reviewNote" class="text-xs text-muted-foreground">
              审核意见：{{ row.original.reviewNote }}
            </div>
          </template>
          <template #cell-attachments="{ row }">
            <div class="flex flex-col gap-1">
              <a
                v-for="(attachment, index) in row.original.attachments"
                :key="attachment.fileKey"
                class="text-sm underline decoration-dotted"
                :href="attachmentUrl(row.original, index)"
                target="_blank"
                rel="noopener"
              >
                <YdIcon :name="attachment.kind === 'image' ? 'i-ri:image-line' : attachment.kind === 'video' ? 'i-ri:video-line' : 'i-ri:file-line'" />
                {{ attachment.caption || attachment.filename }}
              </a>
              <span v-if="!row.original.attachments.length" class="text-sm text-muted-foreground">无附件</span>
            </div>
          </template>
          <template #cell-submitted="{ row }">{{ formatTime(row.original.submittedAt) }}</template>
          <template #cell-status="{ row }">
            <YdTag :variant="row.original.status === 'accepted' ? 'default' : 'outline'">
              {{ statusText(row.original.status) }}
            </YdTag>
          </template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </template>
    <template #footer>
      <YdPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        @page-change="load"
        @size-change="load"
      />
    </template>
  </YdTablePageLayout>

  <YdModal
    v-model="reviewVisible"
    :title="reviewAccept ? '采纳效果提交' : '驳回效果提交'"
    :confirm-button-text="reviewAccept ? '采纳' : '驳回'"
    :confirm-button-loading="reviewSaving"
    :show-cancel-button="!reviewSaving"
    cancel-button-text="取消"
    @confirm="submitReview"
  >
    <div class="flex flex-col gap-3">
      <p class="text-sm text-muted-foreground">
        {{ reviewAccept ? '采纳后该效果进入有效效果池，供活动总结与效果展示文档使用。' : '驳回后将清理该提交的附件文件。' }}
      </p>
      <label class="flex flex-col gap-1 text-sm">
        <span class="text-secondary-foreground/80">审核意见（选填）</span>
        <YdTextarea v-model="reviewNote" :rows="3" maxlength="500" />
      </label>
    </div>
  </YdModal>
</template>
