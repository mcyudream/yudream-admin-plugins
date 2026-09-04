<script setup lang="ts">
import type { TimelinePluginModel } from '../composables/useTimelinePlugin'
import type { TimelineEventPayload } from '../types'
import { DatePicker as ADatePicker } from '@arco-design/web-vue'
import { FaButton, FaDrawer, FaIcon, FaImageUpload, FaInput, FaNumberField, FaSwitch, FaTextarea, useFaToast } from '@yudream/components'
import { computed, reactive, ref, watch } from 'vue'
import { errorMessage, normalizeFileUrl } from '../composables/utils'
import MarkdownEditor from './MarkdownEditor.vue'

const props = defineProps<{
  model: TimelinePluginModel
  open: boolean
  eventId: string | null
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  'saved': []
}>()

const model = props.model
const toast = useFaToast()

const drawerOpen = computed({
  get: () => props.open,
  set: value => emit('update:open', value),
})

function emptyForm(): TimelineEventPayload {
  return { title: '', summary: '', eventDate: '', dateLabel: '', coverImage: '', images: [], detail: '', published: false, sort: 0 }
}

const form = reactive<TimelineEventPayload>(emptyForm())
const loadingDetail = ref(false)

// FaImageUpload 内部通过 push/splice 原地改数组，不会触发 update:modelValue；
// 用独立 ref 承接组件持有的数组引用，再用 deep watch 同步回 form
const coverList = ref<string[]>([])
const imageList = ref<string[]>([])

watch(coverList, (list) => {
  form.coverImage = list.length ? normalizeFileUrl(list[list.length - 1]) : ''
}, { deep: true })

watch(imageList, (list) => {
  form.images = list.map(item => normalizeFileUrl(item)).filter(Boolean)
}, { deep: true })

watch(() => props.open, async (open) => {
  if (!open) {
    return
  }
  Object.assign(form, emptyForm())
  coverList.value = []
  imageList.value = []
  if (!props.eventId) {
    return
  }
  loadingDetail.value = true
  try {
    const event = await model.loadAdminEvent(props.eventId)
    Object.assign(form, {
      title: event.title,
      summary: event.summary,
      eventDate: event.eventDate,
      dateLabel: event.dateLabel,
      coverImage: event.coverImage,
      images: [...event.images],
      detail: event.detail,
      published: event.published,
      sort: event.sort,
    })
    coverList.value = event.coverImage ? [event.coverImage] : []
    imageList.value = [...event.images]
  }
  catch (error) {
    toast.error(errorMessage(error, '加载事件失败'))
    emit('update:open', false)
  }
  finally {
    loadingDetail.value = false
  }
})

async function coverUpload(options: { file: File }) {
  return await model.uploadImage(options.file)
}

async function galleryUpload(options: { file: File }) {
  return await model.uploadImage(options.file)
}

function afterUpload(response: unknown) {
  return typeof response === 'string' ? response : ''
}

async function uploadMarkdownImage(file: File) {
  return await model.uploadImage(file)
}

async function save() {
  if (!form.title.trim()) {
    toast.warning('请填写事件标题')
    return
  }
  if (!form.eventDate) {
    toast.warning('请选择事件时间')
    return
  }
  const payload: TimelineEventPayload = {
    ...form,
    title: form.title.trim(),
    summary: form.summary.trim(),
    dateLabel: form.dateLabel.trim(),
    detail: form.detail,
  }
  const ok = await model.saveEvent(props.eventId, payload)
  if (ok) {
    emit('saved')
  }
}
</script>

<template>
  <FaDrawer v-model="drawerOpen" :title="eventId ? '编辑事件' : '新建事件'" size="720px">
    <div v-loading="loadingDetail" class="tl-editor">
      <div class="grid gap-2">
        <span>事件标题 <em class="tl-required">*</em></span>
        <FaInput v-model="form.title" placeholder="例如：社团正式成立" :maxlength="60" />
      </div>
      <div class="grid gap-2">
        <span>一句话简述</span>
        <FaTextarea v-model="form.summary" placeholder="时间轴卡片上展示的摘要，最多 200 字" :maxlength="200" :rows="2" />
      </div>
      <div class="tl-editor-row">
        <div class="grid gap-2">
          <span>事件时间 <em class="tl-required">*</em></span>
          <ADatePicker v-model="form.eventDate" value-format="YYYY-MM-DD" placeholder="选择事件日期" style="width: 100%" allow-clear />
        </div>
        <div class="grid gap-2">
          <span>时间展示文案</span>
          <FaInput v-model="form.dateLabel" placeholder="可选，如「2024 年春」，留空按日期展示" :maxlength="40" />
        </div>
      </div>
      <div class="grid gap-2">
        <span>事件封面</span>
        <FaImageUpload
          :model-value="coverList"
          :max="1"
          :width="240"
          :height="135"
          :http-request="coverUpload"
          :after-upload="afterUpload"
        />
        <span class="text-xs text-muted-foreground">建议 16:9 图片，展示在时间轴卡片与详情页头图，上传后自动公开访问。</span>
      </div>
      <div class="grid gap-2">
        <span>详情图集（最多 12 张）</span>
        <FaImageUpload
          :model-value="imageList"
          :max="12"
          :width="160"
          :height="120"
          :http-request="galleryUpload"
          :after-upload="afterUpload"
        />
      </div>
      <div class="grid gap-2">
        <span>事件详情（Markdown）</span>
        <MarkdownEditor v-model="form.detail" placeholder="详细介绍这个事件的背景、过程与意义，支持 Markdown 与图片上传" :upload-image="uploadMarkdownImage" />
      </div>
      <div class="tl-editor-row">
        <div class="grid gap-2">
          <span>排序权重</span>
          <FaNumberField v-model="form.sort" :min="-9999" :max="9999" placeholder="0" />
          <span class="text-xs text-muted-foreground">同一天的事件按权重从大到小排列。</span>
        </div>
        <div class="grid gap-2">
          <span>发布状态</span>
          <div class="tl-switch-row">
            <FaSwitch v-model="form.published" />
            <span class="text-sm">{{ form.published ? '已发布，公开时间轴可见' : '草稿，仅管理端可见' }}</span>
          </div>
        </div>
      </div>
      <div class="tl-editor-footer">
        <FaButton variant="outline" :disabled="model.saving" @click="drawerOpen = false">
          取消
        </FaButton>
        <FaButton :loading="model.saving" @click="save">
          <FaIcon name="i-ri:save-line" />保存
        </FaButton>
      </div>
    </div>
  </FaDrawer>
</template>
