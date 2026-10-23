<script setup lang="ts">
import type { EntityPeekFact, RelatedEntityRef } from '@yudream/components'
import type { SkinTexture } from '../types'
import { computed, ref, watch } from 'vue'
import { YdEntityPeek } from '@yudream/components'
import { readSkinTexture, skinTextureApi } from '../peek-runtime'
import SkinTexturePreview from './SkinTexturePreview.vue'

/**
 * `yudream-skin:texture` 实体卡提供者：entityId 为材质 hash。
 * 首次打开拉取一次材质清单并按 hash 缓存（管理员 /admin、普通身份 /me），
 * 渲染图走公开材质端点；其他插件页面用 YdRelatedEntity 引用同一类型即可复用本卡。
 */
const props = defineProps<RelatedEntityRef>()

const texture = ref<SkinTexture>()
const loading = ref(false)
const error = ref('')

const hash = computed(() => props.entityId)

watch(() => props.entityId, () => {
  texture.value = undefined
  error.value = ''
}, { immediate: true })

async function load() {
  if (!hash.value || texture.value) {
    return
  }
  loading.value = true
  error.value = ''
  try {
    texture.value = await readSkinTexture(hash.value)
  }
  catch (cause) {
    error.value = cause instanceof Error ? cause.message : '材质信息加载失败'
  }
  finally {
    loading.value = false
  }
}

const previewUrl = computed(() => skinTextureApi().textureUrl(hash.value))

const typeLabel = computed(() => {
  const value = texture.value?.type
  if (value === 'cape') {
    return '披风'
  }
  if (value === 'slim') {
    return 'Alex 皮肤'
  }
  if (value === 'default' || value === 'skin') {
    return 'Steve 皮肤'
  }
  return value || '-'
})

const facts = computed<EntityPeekFact[]>(() => [
  { label: '类型', value: typeLabel.value },
  { label: '可见性', value: texture.value ? (texture.value.publicAccess ? '公开' : '私有') : '-' },
  { label: '大小', value: formatSize(texture.value?.size) },
  { label: '材质 Hash', value: hash.value, mono: true },
])

function formatSize(size?: number) {
  if (!size) {
    return '-'
  }
  if (size < 1024) {
    return `${size} B`
  }
  return `${(size / 1024).toFixed(1)} KB`
}
</script>

<template>
  <YdEntityPeek
    :title="label || texture?.name || entityId"
    :subtitle="secondary"
    :avatar="avatar"
    :loading="loading"
    :error="error"
    :facts="facts"
    side="bottom"
    @open="load()"
    @retry="load()"
  >
    <template #extra>
      <div v-if="texture && previewUrl" class="skin-texture-peek">
        <SkinTexturePreview :texture-url="previewUrl" :type="texture.type" :model="texture.model" />
      </div>
    </template>
  </YdEntityPeek>
</template>

<style scoped>
.skin-texture-peek {
  display: flex;
  justify-content: center;
  max-width: 100%;
  overflow: hidden;
}
</style>
