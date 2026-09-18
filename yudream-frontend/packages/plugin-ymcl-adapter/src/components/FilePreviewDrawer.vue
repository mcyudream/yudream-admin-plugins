<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { CasFilePreview } from '../types'
import { FaButton, FaDrawer, FaIcon, FaTag, useFaToast } from '@yudream/components'
import { computed, ref, watch } from 'vue'
import { formatSize } from '../composables/ymcl-protocol'

const props = defineProps<{
  modelValue: boolean
  sdk: YuDreamPluginSdk
  sha512: string
  path: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const toast = useFaToast()
const loading = ref(false)
const preview = ref<CasFilePreview | null>(null)

const downloadUrl = computed(() => {
  if (!preview.value?.downloadUrl) {
    return ''
  }
  return props.sdk.http.url(preview.value.downloadUrl)
})

const imageUrl = computed(() => {
  if (preview.value?.kind !== 'image') {
    return ''
  }
  return downloadUrl.value
})

const metaEntries = computed(() => {
  const meta = preview.value?.meta
  if (!meta) {
    return [] as Array<{ label: string, value: string }>
  }
  const labels: Record<string, string> = {
    id: '模组 ID',
    name: '名称',
    version: '版本',
    loader: '加载器',
    minecraft: 'MC 版本',
    description: '简介',
    authors: '作者',
  }
  return Object.entries(meta)
    .filter(([, value]) => value != null && value !== '')
    .map(([key, value]) => ({
      label: labels[key] || key,
      value: Array.isArray(value) ? value.map(String).join(', ') : String(value),
    }))
})

async function loadPreview() {
  if (!props.sha512 || !props.modelValue) {
    return
  }
  loading.value = true
  preview.value = null
  try {
    const data = await props.sdk.http.get<CasFilePreview>(
      `/v1/admin/cas/${encodeURIComponent(props.sha512)}/preview${props.path ? `?path=${encodeURIComponent(props.path)}` : ''}`,
    )
    preview.value = data
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '预览加载失败')
  }
  finally {
    loading.value = false
  }
}

watch(
  () => [props.modelValue, props.sha512],
  () => {
    void loadPreview()
  },
  { immediate: true },
)

function copyContent() {
  if (!preview.value?.content) {
    return
  }
  navigator.clipboard.writeText(preview.value.content)
    .then(() => toast.success('内容已复制'))
    .catch(() => toast.error('复制失败'))
}
</script>

<template>
  <FaDrawer
    :model-value="modelValue"
    :title="preview?.filename || path || '文件预览'"
    side="right"
    :show-confirm-button="false"
    :footer="false"
    content-class="ymcl-file-preview-drawer"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <div v-loading="loading" class="ymcl-drawer-body">
      <div v-if="preview" class="ymcl-action-row">
        <FaTag variant="secondary">{{ preview.kind }}</FaTag>
        <FaTag variant="outline">{{ formatSize(preview.size || 0) }}</FaTag>
        <FaTag v-if="preview.truncated" variant="outline">已截断</FaTag>
        <FaButton as="a" size="sm" variant="outline" class="ml-auto" :href="downloadUrl" target="_blank" rel="noopener">
          <FaIcon name="i-ri:download-line" />
          下载
        </FaButton>
      </div>

      <template v-if="preview?.kind === 'text'">
        <div class="ymcl-action-row">
          <FaButton size="sm" variant="outline" @click="copyContent">
            <FaIcon name="i-ri:file-copy-line" />
            复制
          </FaButton>
          <span v-if="preview.truncated" class="ymcl-muted">内容超过 256KB，已截断展示</span>
        </div>
        <pre class="ymcl-json-pre">{{ preview.content }}</pre>
      </template>

      <template v-else-if="preview?.kind === 'mod'">
        <dl v-if="metaEntries.length" class="ymcl-info-list">
          <div v-for="item in metaEntries" :key="item.label">
            <dt>{{ item.label }}</dt>
            <dd class="ymcl-break">
              {{ item.value }}
            </dd>
          </div>
        </dl>
        <p v-if="preview.message" class="ymcl-muted">
          {{ preview.message }}
        </p>
        <div v-if="preview.metaFiles?.length" class="ymcl-action-row">
          <FaTag v-for="file in preview.metaFiles" :key="file" variant="outline">
            {{ file }}
          </FaTag>
        </div>
      </template>

      <template v-else-if="preview?.kind === 'image'">
        <img v-if="imageUrl" :src="imageUrl" :alt="preview.filename" class="ymcl-preview-image">
        <p v-else class="ymcl-muted">
          无法生成图片地址
        </p>
      </template>

      <template v-else-if="preview">
        <p class="ymcl-muted">
          {{ preview.message || '暂不支持在线预览，请下载后查看。' }}
        </p>
        <p v-if="preview.path" class="ymcl-muted ymcl-break">
          路径：{{ preview.path }}
        </p>
      </template>

      <p v-else-if="!loading" class="ymcl-muted">
        预览加载失败或文件不存在。
      </p>
    </div>
  </FaDrawer>
</template>
