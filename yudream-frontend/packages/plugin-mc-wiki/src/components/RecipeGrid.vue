<script setup lang="ts">
import type { WikiRecipe } from '../types'
import { FaIcon } from '@yudream/components'
import { computed } from 'vue'
import WikiIcon from './WikiIcon.vue'
import { fallbackRecipeCells, parseRecipeCells, plainIngredientId, type RecipeCell } from '../utils/recipeCells'

const props = defineProps<{ recipe: WikiRecipe, iconUrl: (itemId: string) => string }>()

const parsed = computed(() => parseRecipeCells(props.recipe))

function iconSrc(cell: RecipeCell): string | null {
  if (cell.id.startsWith('ore:')) {
    return null
  }
  if (cell.tag) {
    return cell.representative ? props.iconUrl(cell.representative) : null
  }
  return props.iconUrl(cell.id)
}

function cellTitle(cell: RecipeCell): string {
  if (!cell.tag) {
    return plainIngredientId(cell)
  }
  return cell.representative
    ? `标签 ${plainIngredientId(cell)}（任选其一，示例 ${cell.representative}）`
    : `标签 ${plainIngredientId(cell)}`
}

function displayCells(): RecipeCell[] {
  return parsed.value?.extra.length ? parsed.value.extra : fallbackRecipeCells(props.recipe)
}
</script>

<template>
  <div class="mc-wiki-recipe-grid">
    <template v-if="parsed?.kind === 'shaped'">
      <div class="mc-wiki-craft-table">
        <div v-for="(row, r) in parsed.cells" :key="r" class="mc-wiki-craft-row">
          <span v-for="(cell, c) in row" :key="c" class="mc-wiki-craft-cell" :class="{ 'is-tag': cell?.tag }" :title="cell ? cellTitle(cell) : undefined">
            <template v-if="cell">
              <span v-if="cell.tag" class="mc-wiki-craft-tagmark" aria-hidden="true">#</span>
              <WikiIcon :src="iconSrc(cell)" :label="plainIngredientId(cell)" :size="28" />
            </template>
          </span>
        </div>
      </div>
    </template>
    <template v-else>
      <div class="mc-wiki-craft-list">
        <span v-for="(cell, index) in displayCells()" :key="index" class="mc-wiki-craft-cell" :class="{ 'is-tag': cell.tag }" :title="cellTitle(cell)">
          <span v-if="cell.tag" class="mc-wiki-craft-tagmark" aria-hidden="true">#</span>
          <WikiIcon :src="iconSrc(cell)" :label="plainIngredientId(cell)" :size="28" />
        </span>
      </div>
    </template>
    <FaIcon name="i-ri:arrow-right-line" class="mc-wiki-craft-arrow" />
    <span class="mc-wiki-craft-cell mc-wiki-craft-result">
      <WikiIcon :src="recipe.resultId ? iconUrl(recipe.resultId) : null" :label="recipe.resultId" :size="32" />
      <span v-if="recipe.resultCount > 1" class="mc-wiki-craft-count">{{ recipe.resultCount }}</span>
    </span>
  </div>
</template>
