<script setup lang="ts">
import type { WikiRecipe } from '../types'
import { FaDrawer, FaTag } from '@yudream/components'
import { computed } from 'vue'
import { recipeTypeLabel } from '../types'
import RecipeGrid from './RecipeGrid.vue'
import WikiIcon from './WikiIcon.vue'

const props = defineProps<{ modelValue: boolean, recipe: WikiRecipe | null, iconUrl: (itemId: string) => string }>()
const emit = defineEmits<{ 'update:modelValue': [boolean] }>()

const title = computed(() => props.recipe ? (props.recipe.resultNameZh || props.recipe.resultNameEn || props.recipe.resultId) : '配方详情')

function ingredientLabel(value: string): string {
  if (value.startsWith('#')) {
    return `标签 ${value.slice(1)}`
  }
  if (value.startsWith('ore:')) {
    return `矿物词典 ${value.slice(4)}`
  }
  return value
}

function ingredientIcon(value: string): string | null {
  return value.startsWith('#') || value.startsWith('ore:') ? null : props.iconUrl(value)
}

function close(value: boolean) {
  emit('update:modelValue', value)
}
</script>

<template>
  <FaDrawer :model-value="modelValue" :title="title" side="right" :show-confirm-button="false" :footer="false" content-class="w-[min(560px,calc(100vw-24px))]" @update:model-value="close">
    <div v-if="recipe" class="flex flex-col gap-4">
      <div class="flex items-center gap-3">
        <WikiIcon :src="iconUrl(recipe.resultId)" :label="title" :size="48" />
        <div class="flex min-w-0 flex-col gap-1">
          <strong class="break-all text-base">{{ title }}</strong>
          <span class="break-all text-sm text-secondary-foreground/60">{{ recipe.resultId }}</span>
        </div>
        <FaTag class="ml-auto shrink-0">{{ recipeTypeLabel(recipe.type) }}</FaTag>
      </div>
      <section class="flex flex-col gap-2">
        <h4 class="m-0 text-sm font-semibold">合成方式</h4>
        <RecipeGrid :recipe="recipe" :icon-url="iconUrl" />
      </section>
      <section class="flex flex-col gap-2">
        <h4 class="m-0 text-sm font-semibold">原料（{{ recipe.ingredients.length }}）</h4>
        <ul class="m-0 flex list-none flex-col gap-2 p-0">
          <li v-for="ingredient in recipe.ingredients" :key="ingredient" class="flex items-center gap-2">
            <WikiIcon :src="ingredientIcon(ingredient)" :label="ingredientLabel(ingredient)" :size="24" />
            <span class="break-all text-sm">{{ ingredientLabel(ingredient) }}</span>
          </li>
        </ul>
      </section>
      <section class="flex flex-col gap-2">
        <h4 class="m-0 text-sm font-semibold">产物数量</h4>
        <span class="text-sm">{{ recipe.resultCount }}</span>
      </section>
    </div>
  </FaDrawer>
</template>
