<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { ActivityEffect, ActivityEffectAttachment } from '../types'
import { YdButton, YdIcon, YdInput, YdModal, YdTag, YdTextarea, useYdToast, useYdFilePicker } from '@yudream/components'
import { computed, ref, watch } from 'vue'
import { createActivityProofApi } from '../api/activity-proof-api'
import { formatTime } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  activityId: string
  active: boolean
}>()

const toast = useYdToast()
const api = createActivityProofApi(props.sdk)

const records = ref<ActivityEffect[]>([])
const loading = ref(false)
const modalVisible = ref(false)
const submitting = ref(false)
const uploading = ref(false)
const text = ref('')
const pending = ref<ActivityEffectAttachment[]>([])

const statusTag = computed(() => (status: string) => {
  switch (status) {
    case 'accepted':
      return { variant: 'default' as const, text: '已采纳' }
    case 'rejected':
      return { variant: 'outline' as const, text: '未采纳' }
    default:
      return { variant: 'outline' as const, text: '待审核' }
  }
})

watch(() => [props.activityId, props.active] as const, ([id, active]) => {
  if (id && active) {
    void load(id)
  }
}, { immediate: true })

async function load(id: string) {
  loading.value = true
  try {
    const data = await api.me.myActivityEffects(id, 1, 20)
    records.value = data.records ?? []
  }
  catch (cause) {
    records.value = []
  }
  finally {
    loading.value = false
  }
}

function openModal() {
  text.value = ''
  pending.value = []
  modalVisible.value = true
}

const pickEffectFiles = useYdFilePicker({ multiple: true })

function chooseEffectFiles() {
  void pickEffectFiles.open().then((files) => {
    if (files.length) {
      void onFilesChosen(files)
    }
  })
}

async function onFilesChosen(files: File[]) {
  if (!files.length) {
    return
  }
  uploading.value = true
  try {
    for (const file of files) {
      const uploaded = await props.sdk.files.uploadImage(file, { module: 'minecraft-activity-proof' })
      const attachment = await api.me.uploadEffect(props.activityId, {
        fileId: uploaded.id,
        filename: uploaded.originalName || file.name,
      })
      pending.value = [...pending.value, attachment]
    }
    toast.success('附件已上传，填写图注后提交')
  }
  catch (cause) {
    toast.error(errorMessage(cause, '附件上传失败'))
  }
  finally {
    uploading.value = false
  }
}

function removePending(index: number) {
  pending.value = pending.value.filter((_, i) => i !== index)
}

async function submit() {
  if (!text.value.trim() && !pending.value.length) {
    toast.warning('请填写效果说明或至少上传一个附件')
    return
  }
  submitting.value = true
  try {
    await api.me.submitEffect(props.activityId, { text: text.value.trim(), attachments: pending.value })
    toast.success('效果材料已提交，等待审核')
    modalVisible.value = false
    await load(props.activityId)
  }
  catch (cause) {
    toast.error(errorMessage(cause, '效果提交失败'))
  }
  finally {
    submitting.value = false
  }
}

async function withdraw(effect: ActivityEffect) {
  try {
    await api.me.withdrawEffect(effect.id)
    toast.success('已撤回')
    await load(props.activityId)
  }
  catch (cause) {
    toast.error(errorMessage(cause, '撤回失败'))
  }
}

function attachmentUrl(effect: ActivityEffect, index: number) {
  return api.downloadUrl(`/me/effects/${encodeURIComponent(effect.id)}/attachments/${index}`)
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
  <div v-if="active" class="activity-detail-description">
    <div class="flex items-center justify-between">
      <h3>活动效果材料</h3>
      <YdButton size="sm" @click="openModal">
        <YdIcon name="i-ri:upload-2-line" />提交活动效果
      </YdButton>
    </div>
    <p class="text-sm text-muted-foreground">
      活动结束后可提交文字说明与图片、视频等材料，经管理员采纳后用于活动总结与效果展示。
    </p>
    <div v-if="records.length" class="flex flex-col gap-3">
      <div v-for="effect in records" :key="effect.id" class="rounded-lg border p-3">
        <div class="flex flex-wrap items-center gap-2">
          <YdTag :variant="statusTag(effect.status).variant">{{ statusTag(effect.status).text }}</YdTag>
          <span class="text-sm text-muted-foreground">{{ formatTime(effect.submittedAt) }}</span>
          <YdButton
            v-if="effect.status === 'submitted'"
            size="sm"
            variant="outline"
            @click="withdraw(effect)"
          >撤回</YdButton>
        </div>
        <p v-if="effect.text" class="mt-2 whitespace-pre-wrap text-sm">{{ effect.text }}</p>
        <div v-if="effect.attachments.length" class="mt-2 flex flex-wrap gap-2">
          <span v-for="(attachment, index) in effect.attachments" :key="attachment.fileKey"
            class="rounded border px-2 py-1 text-xs">
            <YdIcon :name="attachment.kind === 'image' ? 'i-ri:image-line' : attachment.kind === 'video' ? 'i-ri:video-line' : 'i-ri:file-line'" />
            <a :href="attachmentUrl(effect, index)" target="_blank" rel="noopener">{{ attachment.caption || attachment.filename }}</a>
          </span>
        </div>
        <p v-if="effect.reviewNote" class="mt-2 text-sm text-muted-foreground">审核意见：{{ effect.reviewNote }}</p>
      </div>
    </div>
    <p v-else-if="!loading" class="text-sm text-muted-foreground">还没有提交过效果材料</p>
  </div>
  <YdModal
    v-model="modalVisible"
    title="提交活动效果"
    confirm-button-text="提交"
    :confirm-button-loading="submitting"
    :show-cancel-button="!submitting"
    cancel-button-text="取消"
    @confirm="submit"
  >
    <div class="flex flex-col gap-3">
      <label class="flex flex-col gap-1 text-sm">
        <span class="text-secondary-foreground/80">效果说明</span>
        <YdTextarea v-model="text" :rows="4" maxlength="2000" placeholder="活动的开展情况、亮点与成果…" />
      </label>
      <div class="flex flex-col gap-1 text-sm">
        <span class="text-secondary-foreground/80">图片 / 视频 / 文档（最多 20 个，单个不超过 200MB）</span>
        <div>
          <YdButton size="sm" variant="outline" :loading="uploading" @click="chooseEffectFiles">
            <YdIcon name="i-ri:upload-2-line" />选择文件上传
          </YdButton>
          <span v-if="uploading" class="ml-2 text-sm text-muted-foreground">上传中…</span>
        </div>
      </div>
      <div v-if="pending.length" class="flex flex-col gap-2">
        <div v-for="(attachment, index) in pending" :key="attachment.fileKey"
          class="flex items-center gap-2 rounded border p-2">
          <YdIcon
            :name="attachment.kind === 'image' ? 'i-ri:image-line' : attachment.kind === 'video' ? 'i-ri:video-line' : 'i-ri:file-line'"
          />
          <YdInput v-model="attachment.caption" size="sm" placeholder="图注（选填）" class="flex-1" />
          <YdButton size="sm" variant="outline" @click="removePending(index)">移除</YdButton>
        </div>
      </div>
    </div>
  </YdModal>
</template>
