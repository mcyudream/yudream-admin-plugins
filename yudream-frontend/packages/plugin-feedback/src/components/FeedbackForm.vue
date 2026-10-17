<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { Feedback, FeedbackSeverity, FeedbackType } from '../types'
import { YdButton, YdIcon, YdImageUpload, YdInput, YdSelect, YdTextarea } from '@yudream/components'
import { computed, reactive, ref, watch } from 'vue'
import { SEVERITY_FORM_OPTIONS, TYPE_FORM_OPTIONS } from '../composables/utils'

/**
 * 提交 / 修改反馈的表单。
 *
 * <p>「提交反馈」与「修改反馈」此前各写一遍表单，字段与校验容易只改一处；这里收成同一份，
 * 修改场景只多一个「承载已有内容」的入口，校验规则与后端的聚合约束保持一一对应。
 */
const props = withDefaults(defineProps<{
  sdk: YuDreamPluginSdk
  /** 修改场景回填来源；为空表示新建。 */
  source?: Feedback | null
  submitting?: boolean
  submitText?: string
}>(), {
  source: null,
  submitting: false,
  submitText: '提交反馈',
})

const emit = defineEmits<{
  (event: 'submit', payload: { title: string, description: string, type: FeedbackType, severity: FeedbackSeverity, attachments: string[] }): void
  (event: 'cancel'): void
}>()

/** 与后端 Feedback 聚合的上限保持一致，避免只在提交后才由服务端报错。 */
const MAX_TITLE_LENGTH = 120
const MAX_DESCRIPTION_LENGTH = 5000
const MAX_ATTACHMENT_COUNT = 6

const form = reactive({
  title: '',
  description: '',
  type: 'bug' as FeedbackType,
  severity: 'normal' as FeedbackSeverity,
})

/**
 * YdImageUpload 内部通过 push/splice 原地改数组，不会触发 update:modelValue；
 * 用独立 ref 承接组件持有的数组引用，再用 deep watch 同步回本地状态。
 * 列表项始终是可展示的完整 URL，提交前才归一化为站内相对路径。
 */
const attachmentList = ref<string[]>([])
const attachments = ref<string[]>([])

const errors = reactive({ title: '', description: '' })

watch(attachmentList, list => {
  attachments.value = list.filter(item => Boolean(item))
}, { deep: true })

watch(() => props.source, source => {
  form.title = source?.title ?? ''
  form.description = source?.description ?? ''
  form.type = source?.type ?? 'bug'
  form.severity = source?.severity ?? 'normal'
  attachmentList.value = (source?.attachments ?? []).map(item => resolveImageUrl(item))
  errors.title = ''
  errors.description = ''
}, { immediate: true })

const titleRemain = computed(() => MAX_TITLE_LENGTH - form.title.length)
const descriptionRemain = computed(() => MAX_DESCRIPTION_LENGTH - form.description.length)

async function uploadImage(options: { file: File }) {
  const uploaded = await props.sdk.files.uploadImage(options.file, { module: 'feedback', publicAccess: true })
  return uploaded.assetUrl || uploaded.url || ''
}

function afterUpload(response: unknown) {
  return typeof response === 'string' ? response : ''
}

/** 列表里展示的是可直接渲染的地址，外链原样保留、站内文件交回 SDK 拼绝对地址。 */
function resolveImageUrl(url: string): string {
  const raw = (url || '').trim()
  if (!raw) {
    return ''
  }
  return /^https?:\/\//i.test(raw) ? raw : props.sdk.files.assetUrl(raw)
}

function validate(): boolean {
  errors.title = ''
  errors.description = ''
  const title = form.title.trim()
  const description = form.description.trim()
  if (!title) {
    errors.title = '请填写反馈标题'
  }
  else if (title.length > MAX_TITLE_LENGTH) {
    errors.title = `标题不能超过 ${MAX_TITLE_LENGTH} 个字符`
  }
  if (!description) {
    errors.description = '请填写问题描述'
  }
  else if (description.length > MAX_DESCRIPTION_LENGTH) {
    errors.description = `问题描述不能超过 ${MAX_DESCRIPTION_LENGTH} 个字符`
  }
  return !errors.title && !errors.description
}

function submit() {
  if (props.submitting || !validate()) {
    return
  }
  emit('submit', {
    title: form.title.trim(),
    description: form.description.trim(),
    type: form.type,
    severity: form.severity,
    attachments: attachments.value.map(item => normalizeFileUrl(item)).filter(Boolean),
  })
}

/** 提交前把展示地址收敛为站内相对路径，避免把宿主域名写进反馈数据。 */
function normalizeFileUrl(value: string): string {
  return (value || '')
    .trim()
    .replace(/^https?:\/\/[^/]+(?=\/api\/files\/)/i, '')
    .replace(/^\/proxy(?=\/api\/files\/)/i, '')
}
</script>

<template>
  <div class="fb-form">
    <div class="fb-form__field">
      <label class="fb-form__label" for="fb-title">
        反馈标题
        <span class="fb-form__required">*</span>
      </label>
      <YdInput
        id="fb-title"
        v-model="form.title"
        :maxlength="MAX_TITLE_LENGTH"
        class="w-full"
        :class="{ 'fb-form__control--invalid': errors.title }"
        placeholder="一句话概括问题，例如「物料上传后缩略图不显示」"
      />
      <span class="fb-form__hint" :class="{ 'is-error': errors.title }">
        {{ errors.title || `还可以输入 ${titleRemain} 个字符` }}
      </span>
    </div>

    <div class="fb-form__grid">
      <div class="fb-form__field">
        <label class="fb-form__label" for="fb-type">反馈类型</label>
        <YdSelect id="fb-type" v-model="form.type" class="w-full" :options="TYPE_FORM_OPTIONS" />
        <span class="fb-form__hint">便于管理员按类型分派，后期可修正</span>
      </div>
      <div class="fb-form__field">
        <label class="fb-form__label" for="fb-severity">严重程度</label>
        <YdSelect id="fb-severity" v-model="form.severity" class="w-full" :options="SEVERITY_FORM_OPTIONS" />
        <span class="fb-form__hint">影响使用的紧急程度，仅作受理参考</span>
      </div>
    </div>

    <div class="fb-form__field">
      <label class="fb-form__label" for="fb-description">
        问题描述
        <span class="fb-form__required">*</span>
      </label>
      <YdTextarea
        id="fb-description"
        v-model="form.description"
        :maxlength="MAX_DESCRIPTION_LENGTH"
        :auto-size="{ minRows: 8, maxRows: 20 }"
        class="w-full"
        :class="{ 'fb-form__control--invalid': errors.description }"
        placeholder="请说明操作步骤、预期结果与实际结果；有报错信息或复现路径时一并附上，能显著加快定位。"
      />
      <span class="fb-form__hint" :class="{ 'is-error': errors.description }">
        {{ errors.description || `还可以输入 ${descriptionRemain} 个字符` }}
      </span>
    </div>

    <div class="fb-form__field">
      <span class="fb-form__label">截图或附件</span>
      <YdImageUpload
        :model-value="attachmentList"
        :max="MAX_ATTACHMENT_COUNT"
        :width="140"
        :height="110"
        :http-request="uploadImage"
        :after-upload="afterUpload"
      />
      <span class="fb-form__hint">最多 {{ MAX_ATTACHMENT_COUNT }} 张，支持直接粘贴截图</span>
    </div>

    <div class="fb-form__actions">
      <YdButton variant="outline" :disabled="submitting" @click="emit('cancel')">
        取消
      </YdButton>
      <YdButton :loading="submitting" @click="submit">
        <YdIcon name="i-ri:send-plane-line" />
        {{ submitText }}
      </YdButton>
    </div>
  </div>
</template>

<style scoped>
.fb-form {
  display: grid;
  gap: var(--fb-sp-5);
  max-width: 860px;
  padding: var(--fb-sp-6);
  border: 1px solid var(--fb-border);
  border-radius: var(--fb-radius);
  background: var(--fb-surface);
}

.fb-form__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--fb-sp-5);
}

.fb-form__field {
  display: grid;
  gap: var(--fb-sp-2);
  min-width: 0;
}

.fb-form__label {
  color: var(--fb-text-2);
  font-size: 13px;
  font-weight: 600;
}

.fb-form__required {
  margin-left: 2px;
  color: rgb(var(--danger-6));
}

.fb-form__hint {
  color: var(--fb-text-3);
  font-size: 12px;
}

.fb-form__hint.is-error {
  color: rgb(var(--danger-6));
}

/* 校验失败时把输入框边框标红：YdInput / YdTextarea 没有 error 属性，只能从外层改边框色。 */
.fb-form__control--invalid {
  border-color: rgb(var(--danger-6));
}

.fb-form__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--fb-sp-3);
  padding-top: var(--fb-sp-4);
  border-top: 1px solid var(--fb-border);
}

@media (max-width: 640px) {
  .fb-form {
    padding: var(--fb-sp-4);
  }

  .fb-form__actions {
    flex-direction: column-reverse;
  }
}
</style>
