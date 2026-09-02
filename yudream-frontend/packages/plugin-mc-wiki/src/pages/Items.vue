<script setup lang="ts">
import type { McWikiPluginModel } from '../composables/useMcWikiPlugin'
import type { WikiItem, WikiRecipe } from '../types'
import { FaButton, FaCard, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaSearchBar, FaSelect, FaTag } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import ItemDetailDrawer from '../components/ItemDetailDrawer.vue'
import RecipeDetailDrawer from '../components/RecipeDetailDrawer.vue'
import WikiIcon from '../components/WikiIcon.vue'
import { itemDisplayName } from '../types'

const props = defineProps<{ model: McWikiPluginModel }>()

const versionOptions = computed(() => (props.model.meta?.versions ?? []).map(version => ({ label: version, value: version })))

const selectedItem = ref<string | null>(null)
const itemOpen = ref(false)
const selectedRecipe = ref<WikiRecipe | null>(null)
const recipeOpen = ref(false)

function openItem(item: WikiItem) {
  selectedItem.value = item.namespacedId
  itemOpen.value = true
}

function openRecipe(recipe: WikiRecipe) {
  selectedRecipe.value = recipe
  recipeOpen.value = true
}

onMounted(async () => {
  await props.model.loadMeta()
  void props.model.loadRenders()
  await props.model.loadItems()
})
</script>

<template>
  <FaPageHeader title="Minecraft 物品图鉴" description="浏览已发布版本的物品、方块与生物，查看渲染图与关联配方">
    <FaTag v-if="model.meta?.published" variant="secondary">版本 {{ model.publicVersion || model.meta.version }}</FaTag>
  </FaPageHeader>
  <FaPageMain>
    <FaCard v-if="model.meta && !model.meta.published" class="mc-wiki-empty">
      <FaIcon name="i-ri:archive-line" class="text-3xl text-secondary-foreground/50" />
      <p class="m-0 text-sm text-secondary-foreground/70">公开百科尚未发布任何版本，请联系管理员在「百科版本」页导入并发布。</p>
    </FaCard>
    <template v-else>
      <FaSearchBar class="w-full">
        <div class="mc-wiki-filter-bar">
          <FaSelect v-if="versionOptions.length > 1" :model-value="model.publicVersion || model.meta?.version || ''" :options="versionOptions" placeholder="选择版本" @change="value => model.changePublicVersion(String(value))" />
          <FaInput v-model="model.itemKeyword" placeholder="搜索名称或 ID，如 苹果 / apple" clearable @keydown.enter="model.searchItems" @clear="model.searchItems" />
          <FaButton variant="outline" :loading="model.loading" @click="model.searchItems"><FaIcon name="i-ri:search-line" />查询</FaButton>
        </div>
      </FaSearchBar>
      <div v-loading="model.loading" class="mc-wiki-icon-grid">
        <button v-for="item in model.items" :key="item.namespacedId" type="button" class="mc-wiki-item-cell" :title="itemDisplayName(item)" @click="openItem(item)">
          <WikiIcon :src="model.iconUrl(item.namespacedId)" :label="itemDisplayName(item)" :size="36" />
          <span class="mc-wiki-item-name">{{ itemDisplayName(item) }}</span>
        </button>
        <FaCard v-if="!model.loading && !model.items.length" class="mc-wiki-empty">
          <FaIcon name="i-ri:search-line" class="text-3xl text-secondary-foreground/50" />
          <p class="m-0 text-sm text-secondary-foreground/70">没有匹配的物品，换个关键字试试。</p>
        </FaCard>
      </div>
      <FaPagination v-model:page="model.itemPager.page" v-model:size="model.itemPager.size" :total="model.itemPager.total" class="mt-3" @page-change="model.loadItems" @size-change="model.searchItems" />
    </template>
    <ItemDetailDrawer v-model="itemOpen" :item-id="selectedItem" :version="model.publicVersion" :render-gen="model.renderGen" :api="model.api" @view-recipe="openRecipe" />
    <RecipeDetailDrawer v-model="recipeOpen" :recipe="selectedRecipe" :icon-url="model.iconUrl" />
  </FaPageMain>
</template>
