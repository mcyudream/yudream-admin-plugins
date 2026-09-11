<script setup lang="ts">
import type { NewsSourcePayload, NewsSourceView, NewsSourceType } from '../types'
import { InputTag as ArcoInputTag } from '@arco-design/web-vue'
import { FaButton, FaInput, FaModal, FaSwitch, useFaToast } from '@yudream/components'
import { computed, reactive, ref, watch } from 'vue'
import { createMcNewsApi } from '../api/mc-news-api'
import { errorMessage, SOURCE_TYPE_OPTIONS } from '../composables/utils'

const props = defineProps<{
  sdk: Parameters<typeof createMcNewsApi>[0]
  open: boolean
  source: NewsSourceView | null
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  'saved': []
}>()

const api = createMcNewsApi(props.sdk)
const toast = useFaToast()
const saving = ref(false)

const modalOpen = computed({
  get: () => props.open,
  set: value => emit('update:open', value),
})

const form = reactive<NewsSourcePayload>({
  name: '',
  type: 'mcnet',
  url: '',
  keywords: [],
  enabled: true,
})

watch(() => props.open, (open) => {
  if (!open)
    return
  const source = props.source
  form.name = source?.name ?? ''
  form.type = (source?.type ?? 'mcnet') as NewsSourceType
  form.url = source?.url ?? ''
  form.keywords = [...(source?.keywords ?? [])]
  form.enabled = source?.enabled ?? true
})

async function save() {
  if (!form.name.trim() || !form.url.trim()) {
    toast.error('请填写新闻源名称与地址')
    return
  }
  saving.value = true
  try {
    if (props.source) {
      await api.updateSource(props.source.id, { ...form })
    }
    else {
      await api.createSource({ ...form })
    }
    toast.success(props.source ? '新闻源已更新' : '新闻源已创建')
    modalOpen.value = false
    emit('saved')
  }
  catch (error) {
    toast.error(errorMessage(error, '保存新闻源失败'))
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <FaModal v-model="modalOpen" :title="props.source ? '编辑新闻源' : '新增新闻源'" :show-confirm-button="false" :close-on-click-overlay="false">
    <div class="mc-news-form">
      <div class="mc-news-form-item">
        <span class="mc-news-form-label">名称</span>
        <FaInput v-model="form.name" placeholder="如：Minecraft 官网新闻" />
      </div>
      <div class="mc-news-form-item">
        <span class="mc-news-form-label">类型</span>
        <div class="mc-news-toolbar">
          <FaButton
            v-for="option in SOURCE_TYPE_OPTIONS"
            :key="option.value"
            :variant="form.type === option.value ? 'secondary' : 'outline'"
            @click="form.type = option.value as NewsSourceType"
          >
            {{ option.label }}
          </FaButton>
        </div>
        <span class="mc-news-form-hint">mcnet 解析 minecraft.net 文章列表 JSON；zendesk 解析 Minecraft 反馈隧道帮助中心文章 API。</span>
      </div>
      <div class="mc-news-form-item">
        <span class="mc-news-form-label">地址</span>
        <FaInput v-model="form.url" placeholder="https://..." />
      </div>
      <div class="mc-news-form-item">
        <span class="mc-news-form-label">关键词（可选）</span>
        <ArcoInputTag v-model="form.keywords" placeholder="输入后回车添加，留空不过滤" allow-clear :max-tag-count="5" />
        <span class="mc-news-form-hint">标题或摘要命中任一关键词才会收录；留空表示收录全部。</span>
      </div>
      <div class="mc-news-form-item">
        <span class="mc-news-form-label">启用</span>
        <FaSwitch v-model="form.enabled" />
      </div>
    </div>
    <template #footer>
      <FaButton variant="outline" :disabled="saving" @click="modalOpen = false">
        取消
      </FaButton>
      <FaButton :loading="saving" @click="save">
        保存
      </FaButton>
    </template>
  </FaModal>
</template>
