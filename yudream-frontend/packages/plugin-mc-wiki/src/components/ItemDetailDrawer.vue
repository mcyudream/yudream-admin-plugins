<script setup lang="ts">
import type { McWikiApi } from '../api/mc-wiki-api'
import type { WikiItemDetail, WikiItemTextures, WikiRecipe } from '../types'
import { FaButton, FaDrawer, FaIcon, FaInput, FaTag } from '@yudream/components'
import { computed, ref, watch } from 'vue'
import { itemDisplayName, recipeTypeLabel } from '../types'
import WikiIcon from './WikiIcon.vue'

const props = withDefaults(defineProps<{
  modelValue: boolean
  itemId: string | null
  version?: string
  renderGen?: string
  api: McWikiApi
  publicTheme?: boolean
}>(), { version: '', renderGen: '', publicTheme: false })
const emit = defineEmits<{ 'update:modelValue': [boolean], 'view-recipe': [WikiRecipe] }>()

const drawerClass = computed(() => [
  props.publicTheme ? 'mc-wiki-public-drawer' : '',
  'w-[min(560px,calc(100vw-24px))]',
].filter(Boolean).join(' '))

const loading = ref(false)
const detail = ref<WikiItemDetail | null>(null)
const textures = ref<WikiItemTextures | null>(null)
const failed = ref(false)
const customSize = ref<number | string>(128)

const itemName = computed(() => detail.value ? itemDisplayName(detail.value.item) : '')
const downloadSize = computed(() => Math.min(1024, Math.max(1, Math.round(Number(customSize.value) || 128))))
const blockstatePretty = computed(() => {
  const raw = textures.value?.blockstate
  if (!raw) return ''
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  }
  catch {
    return raw
  }
})

const KIND_LABELS: Record<string, string> = { item: '物品', block: '方块', entity: '生物' }
const SIZE_PRESETS = [64, 128, 256, 512]

watch(() => [props.modelValue, props.itemId, props.version] as const, async ([open, itemId]) => {
  if (!open || !itemId) {
    return
  }
  loading.value = true
  failed.value = false
  detail.value = null
  textures.value = null
  try {
    const [itemResult, textureResult] = await Promise.allSettled([props.api.itemDetail(itemId, props.version), props.api.itemTextures(itemId, props.version)])
    if (itemResult.status === 'rejected') {
      failed.value = true
      return
    }
    detail.value = itemResult.value
    textures.value = textureResult.status === 'fulfilled' ? textureResult.value : null
  }
  catch {
    failed.value = true
  }
  finally {
    loading.value = false
  }
}, { immediate: true })

function recipeName(recipe: WikiRecipe): string {
  return recipe.resultNameZh || recipe.resultNameEn || recipe.resultId
}

function iconUrl(itemId: string, size?: number): string {
  return props.api.iconUrl(itemId, size, props.version, props.renderGen)
}

function downloadName(): string {
  const base = (detail.value?.item.namespacedId ?? 'render').replace(':', '-')
  return `${base}-${downloadSize.value}px.png`
}
</script>

<template>
  <FaDrawer :model-value="modelValue" :title="itemName || '物品详情'" side="right" :show-confirm-button="false" :footer="false" :content-class="drawerClass" @update:model-value="value => emit('update:modelValue', value)">
    <div v-loading="loading" :class="publicTheme ? 'mc-wiki-public-drawer__body' : 'flex flex-col gap-4'">
      <p v-if="failed" class="m-0 text-sm text-destructive">物品详情加载失败，可能尚未发布百科版本。</p>
      <template v-if="detail">
        <div class="flex items-center gap-3">
          <WikiIcon :src="iconUrl(detail.item.namespacedId)" :label="itemName" :size="48" />
          <div class="flex min-w-0 flex-col gap-1">
            <strong class="break-all text-base">{{ itemName }}</strong>
            <span v-if="detail.item.nameEn && detail.item.nameEn !== itemName" class="break-all text-sm text-secondary-foreground/60">{{ detail.item.nameEn }}</span>
            <span class="break-all text-sm text-secondary-foreground/60">{{ detail.item.namespacedId }}</span>
          </div>
          <FaTag class="ml-auto shrink-0" variant="outline">{{ KIND_LABELS[detail.item.kind] ?? detail.item.kind }}</FaTag>
        </div>
        <section class="flex flex-col gap-3">
          <h4 class="m-0 text-sm font-semibold">渲染图</h4>
          <div class="flex items-end gap-3">
            <WikiIcon :src="iconUrl(detail.item.namespacedId, 128)" :label="itemName" :size="128" />
            <div class="flex min-w-0 flex-1 flex-col gap-2">
              <span v-if="textures?.renderAvailable" class="text-sm text-secondary-foreground/60">3D 等轴渲染图，可自定义像素尺寸下载</span>
              <span v-else class="text-sm text-secondary-foreground/60">该物品暂无渲染图（{{ textures?.renderName || detail.item.namespacedId }}），请在「百科版本」页一键更新渲染资产</span>
              <div class="mc-wiki-size-bar">
                <FaInput v-model="customSize" type="number" min="1" max="1024" class="w-24" />
                <FaButton v-for="preset in SIZE_PRESETS" :key="preset" size="sm" :variant="downloadSize === preset ? 'default' : 'outline'" @click="customSize = preset">{{ preset }}px</FaButton>
              </div>
              <a class="mc-wiki-download" :href="iconUrl(detail.item.namespacedId, downloadSize)" :download="downloadName()"><FaIcon name="i-ri:download-line" />下载 {{ downloadSize }}px 渲染图</a>
            </div>
          </div>
        </section>
        <section v-if="detail.item.tags.length" class="flex flex-col gap-2">
          <h4 class="m-0 text-sm font-semibold">标签</h4>
          <div class="flex flex-wrap gap-2">
            <FaTag v-for="tag in detail.item.tags" :key="tag" variant="secondary">{{ tag }}</FaTag>
          </div>
        </section>
        <section v-if="blockstatePretty" class="flex flex-col gap-2">
          <h4 class="m-0 text-sm font-semibold">方块状态</h4>
          <pre class="mc-wiki-blockstate">{{ blockstatePretty }}</pre>
        </section>
        <section class="flex flex-col gap-2">
          <h4 class="m-0 text-sm font-semibold">产出它的配方（{{ detail.producing.length }}）</h4>
          <p v-if="!detail.producing.length" class="m-0 text-sm text-secondary-foreground/60">该物品无法通过配方合成。</p>
          <div v-for="recipe in detail.producing" :key="recipe.id" class="mc-wiki-recipe-link" @click="emit('view-recipe', recipe)">
            <WikiIcon :src="iconUrl(recipe.resultId)" :label="recipeName(recipe)" :size="24" />
            <span class="min-w-0 flex-1 break-all text-sm">{{ recipeName(recipe) }}<template v-if="recipe.resultCount > 1"> × {{ recipe.resultCount }}</template></span>
            <FaTag variant="outline">{{ recipeTypeLabel(recipe.type) }}</FaTag>
          </div>
        </section>
        <section class="flex flex-col gap-2">
          <h4 class="m-0 text-sm font-semibold">用到它的配方（{{ detail.using.length }}）</h4>
          <p v-if="!detail.using.length" class="m-0 text-sm text-secondary-foreground/60">没有配方使用该物品作为原料。</p>
          <div v-for="recipe in detail.using" :key="recipe.id" class="mc-wiki-recipe-link" @click="emit('view-recipe', recipe)">
            <WikiIcon :src="iconUrl(recipe.resultId)" :label="recipeName(recipe)" :size="24" />
            <span class="min-w-0 flex-1 break-all text-sm">{{ recipeName(recipe) }}<template v-if="recipe.resultCount > 1"> × {{ recipe.resultCount }}</template></span>
            <FaTag variant="outline">{{ recipeTypeLabel(recipe.type) }}</FaTag>
          </div>
        </section>
      </template>
    </div>
  </FaDrawer>
</template>
