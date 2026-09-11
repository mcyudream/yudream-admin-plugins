<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed, onMounted, ref, watch } from 'vue'
import { useMcWikiPlugin } from '../composables/useMcWikiPlugin'
import ItemDetailDrawer from '../components/ItemDetailDrawer.vue'
import RecipeDetailDrawer from '../components/RecipeDetailDrawer.vue'
import WikiIcon from '../components/WikiIcon.vue'
import { itemDisplayName, recipeTypeLabel, type WikiItem, type WikiRecipe } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk, route?: { meta?: { plugin?: { component?: string } } } }>()
const model = useMcWikiPlugin(props.sdk)

onMounted(async () => {
  const applySeo = (props.sdk as YuDreamPluginSdk & { site?: { applySeo?: (input: { title: string, description?: string, canonicalPath?: string }) => void } }).site?.applySeo
  applySeo?.({
    title: '百科',
    description: 'Minecraft 物品图鉴与合成配方。',
    canonicalPath: '/encyclopedia',
  })
  await model.loadMeta()
  if (!model.meta?.published) {
    return
  }
  await Promise.all([model.loadItems(), model.loadRecipes()])
})

type Tab = 'items' | 'recipes'
const tab = ref<Tab>('items')
const selectedItem = ref<string | null>(null)
const itemOpen = ref(false)
const selectedRecipe = ref<WikiRecipe | null>(null)
const recipeOpen = ref(false)

const versionOptions = computed(() => model.meta?.versions ?? [])
const currentVersion = computed(() => model.publicVersion || model.meta?.version || '')
const published = computed(() => !!model.meta?.published)

function recipeName(recipe: WikiRecipe) {
  return recipe.resultNameZh || recipe.resultNameEn || recipe.resultId
}

function openItem(item: WikiItem) {
  selectedItem.value = item.namespacedId
  itemOpen.value = true
}

function openRecipe(recipe: WikiRecipe) {
  selectedRecipe.value = recipe
  recipeOpen.value = true
}

function onVersionChange(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  void model.changePublicVersion(value)
}

watch(tab, (value) => {
  if (value === 'recipes' && !model.recipes.length && published.value) {
    void model.loadRecipes()
  }
})
</script>

<template>
  <div class="mc-wiki-public">
    <header class="mc-wiki-public__hero">
      <p class="mc-wiki-public__eyebrow">Minecraft 百科</p>
      <h1 class="mc-wiki-public__title">物品图鉴与合成配方</h1>
      <p class="mc-wiki-public__lead">浏览已发布版本的物品、方块、生物与原版配方。未装载或未发布时页面保持空态，不影响站点其它页。</p>
      <div v-if="published" class="mc-wiki-public__meta">
        <span>版本 {{ currentVersion }}</span>
        <span>{{ model.meta?.items ?? 0 }} 物品</span>
        <span>{{ model.meta?.recipes ?? 0 }} 配方</span>
      </div>
    </header>

    <section v-if="!published" class="mc-wiki-public__empty">
      公开百科尚未发布任何版本，请联系管理员在「百科版本」页导入并发布。
    </section>

    <template v-else>
      <div class="mc-wiki-public__toolbar">
        <div class="mc-wiki-public__tabs" role="tablist">
          <button type="button" role="tab" :aria-selected="tab === 'items'" :class="{ 'is-active': tab === 'items' }" @click="tab = 'items'">物品图鉴</button>
          <button type="button" role="tab" :aria-selected="tab === 'recipes'" :class="{ 'is-active': tab === 'recipes' }" @click="tab = 'recipes'">合成配方</button>
        </div>
        <select v-if="versionOptions.length > 1" class="mc-wiki-public__select" :value="currentVersion" @change="onVersionChange">
          <option v-for="version in versionOptions" :key="version" :value="version">{{ version }}</option>
        </select>
        <form class="mc-wiki-public__search" @submit.prevent="tab === 'items' ? model.searchItems() : model.searchRecipes()">
          <input
            v-if="tab === 'items'"
            v-model="model.itemKeyword"
            type="search"
            placeholder="搜索名称或 ID，如 苹果 / apple"
          >
          <input
            v-else
            v-model="model.recipeKeyword"
            type="search"
            placeholder="搜索产物名称或 ID，如 钻石剑"
          >
          <button type="submit">查询</button>
        </form>
      </div>

      <div v-if="tab === 'items'" class="mc-wiki-public__grid" :class="{ 'is-loading': model.loading }">
        <button v-for="item in model.items" :key="item.namespacedId" type="button" class="mc-wiki-public__cell" :title="itemDisplayName(item)" @click="openItem(item)">
          <WikiIcon :src="model.iconUrl(item.namespacedId)" :label="itemDisplayName(item)" :size="40" />
          <span>{{ itemDisplayName(item) }}</span>
        </button>
        <p v-if="!model.loading && !model.items.length" class="mc-wiki-public__empty">没有匹配的物品，换个关键字试试。</p>
      </div>

      <div v-else class="mc-wiki-public__recipes" :class="{ 'is-loading': model.loading }">
        <button v-for="recipe in model.recipes" :key="recipe.id" type="button" class="mc-wiki-public__recipe" @click="openRecipe(recipe)">
          <WikiIcon :src="model.iconUrl(recipe.resultId)" :label="recipeName(recipe)" :size="36" />
          <span class="mc-wiki-public__recipe-name">{{ recipeName(recipe) }}<template v-if="recipe.resultCount > 1"> × {{ recipe.resultCount }}</template></span>
          <span class="mc-wiki-public__recipe-type">{{ recipeTypeLabel(recipe.type) }}</span>
        </button>
        <p v-if="!model.loading && !model.recipes.length" class="mc-wiki-public__empty">没有匹配的配方，换个关键字试试。</p>
      </div>

      <div v-if="tab === 'items' && model.itemPager.total > model.itemPager.size" class="mc-wiki-public__pager">
        <button type="button" :disabled="model.itemPager.page <= 1" @click="model.itemPager.page -= 1; model.loadItems()">上一页</button>
        <span>{{ model.itemPager.page }} / {{ Math.max(1, Math.ceil(model.itemPager.total / model.itemPager.size)) }}</span>
        <button type="button" :disabled="model.itemPager.page * model.itemPager.size >= model.itemPager.total" @click="model.itemPager.page += 1; model.loadItems()">下一页</button>
      </div>
      <div v-if="tab === 'recipes' && model.recipePager.total > model.recipePager.size" class="mc-wiki-public__pager">
        <button type="button" :disabled="model.recipePager.page <= 1" @click="model.recipePager.page -= 1; model.loadRecipes()">上一页</button>
        <span>{{ model.recipePager.page }} / {{ Math.max(1, Math.ceil(model.recipePager.total / model.recipePager.size)) }}</span>
        <button type="button" :disabled="model.recipePager.page * model.recipePager.size >= model.recipePager.total" @click="model.recipePager.page += 1; model.loadRecipes()">下一页</button>
      </div>
    </template>

    <ItemDetailDrawer v-model="itemOpen" public-theme :item-id="selectedItem" :version="model.publicVersion" :render-gen="model.renderGen" :api="model.api" @view-recipe="openRecipe" />
    <RecipeDetailDrawer v-model="recipeOpen" public-theme :recipe="selectedRecipe" :icon-url="model.iconUrl" />
  </div>
</template>
