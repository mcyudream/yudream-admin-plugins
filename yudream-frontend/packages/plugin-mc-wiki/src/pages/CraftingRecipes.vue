<script setup lang="ts">
import type { McWikiPluginModel } from '../composables/useMcWikiPlugin'
import type { WikiRecipe } from '../types'
import { FaButton, FaCard, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaSearchBar, FaSelect, FaTag } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import RecipeDetailDrawer from '../components/RecipeDetailDrawer.vue'
import WikiIcon from '../components/WikiIcon.vue'
import { recipeTypeLabel } from '../types'

const props = defineProps<{ model: McWikiPluginModel }>()

const versionOptions = computed(() => (props.model.meta?.versions ?? []).map(version => ({ label: version, value: version })))

const selected = ref<WikiRecipe | null>(null)
const detailOpen = ref(false)

function recipeName(recipe: WikiRecipe) {
  return recipe.resultNameZh || recipe.resultNameEn || recipe.resultId
}

function openDetail(recipe: WikiRecipe) {
  selected.value = recipe
  detailOpen.value = true
}

onMounted(async () => {
  await props.model.loadMeta()
  void props.model.loadRenders()
  await props.model.loadRecipes()
})
</script>

<template>
  <FaPageHeader title="Minecraft 合成配方" description="浏览已发布版本的原版合成、烧炼、切石与锻造配方">
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
          <FaInput v-model="model.recipeKeyword" placeholder="搜索产物名称或 ID，如 钻石剑 / diamond_sword" clearable @keydown.enter="model.searchRecipes" @clear="model.searchRecipes" />
          <FaButton variant="outline" :loading="model.loading" @click="model.searchRecipes"><FaIcon name="i-ri:search-line" />查询</FaButton>
        </div>
      </FaSearchBar>
      <div v-loading="model.loading" class="mc-wiki-card-grid">
        <FaCard v-for="recipe in model.recipes" :key="recipe.id" class="mc-wiki-recipe-card" @click="openDetail(recipe)">
          <div class="flex items-center gap-3">
            <WikiIcon :src="model.iconUrl(recipe.resultId)" :label="recipeName(recipe)" :size="40" />
            <div class="flex min-w-0 flex-col gap-1">
              <strong class="break-all text-sm">{{ recipeName(recipe) }}<template v-if="recipe.resultCount > 1"> × {{ recipe.resultCount }}</template></strong>
              <span class="break-all text-xs text-secondary-foreground/60">{{ recipe.resultId }}</span>
            </div>
            <FaTag class="ml-auto shrink-0" variant="outline">{{ recipeTypeLabel(recipe.type) }}</FaTag>
          </div>
        </FaCard>
        <FaCard v-if="!model.loading && !model.recipes.length" class="mc-wiki-empty">
          <FaIcon name="i-ri:search-line" class="text-3xl text-secondary-foreground/50" />
          <p class="m-0 text-sm text-secondary-foreground/70">没有匹配的配方，换个关键字试试。</p>
        </FaCard>
      </div>
      <FaPagination v-model:page="model.recipePager.page" v-model:size="model.recipePager.size" :total="model.recipePager.total" class="mt-3" @page-change="model.loadRecipes" @size-change="model.searchRecipes" />
    </template>
    <RecipeDetailDrawer v-model="detailOpen" :recipe="selected" :icon-url="model.iconUrl" />
  </FaPageMain>
</template>
