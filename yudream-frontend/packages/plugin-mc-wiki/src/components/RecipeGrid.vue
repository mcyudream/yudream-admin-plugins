<script setup lang="ts">
import type { WikiRecipe } from '../types'
import { FaIcon } from '@yudream/components'
import { computed } from 'vue'
import WikiIcon from './WikiIcon.vue'

const props = defineProps<{ recipe: WikiRecipe, iconUrl: (itemId: string) => string }>()

interface Cell { id: string | null, tag: boolean }

function ingredientId(def: unknown): Cell | null {
  if (!def) {
    return null
  }
  if (typeof def === 'string') {
    return { id: def, tag: def.startsWith('#') }
  }
  if (Array.isArray(def)) {
    return def.length ? ingredientId(def[0]) : null
  }
  if (typeof def === 'object') {
    const node = def as Record<string, unknown>
    if (typeof node.item === 'string') {
      return { id: node.item, tag: false }
    }
    if (typeof node.tag === 'string') {
      return { id: `#${node.tag}`, tag: true }
    }
  }
  return null
}

interface Parsed { kind: 'shaped' | 'list', cells: (Cell | null)[][], extra: Cell[] }

const parsed = computed<Parsed | null>(() => {
  try {
    const raw = JSON.parse(props.recipe.rawJson || '{}') as Record<string, unknown>
    if (Array.isArray(raw.pattern) && raw.key && typeof raw.key === 'object') {
      const key = raw.key as Record<string, unknown>
      const rows = (raw.pattern as unknown[]).filter((row): row is string => typeof row === 'string')
      const cells: (Cell | null)[][] = Array.from({ length: 3 }, (_, r) => Array.from({ length: 3 }, (_, c) => {
        const row = rows[r]
        const ch = row && c < row.length ? row[c] : ' '
        return ch && ch !== ' ' ? ingredientId(key[ch]) : null
      }))
      return { kind: 'shaped', cells, extra: [] }
    }
    const singles: Cell[] = []
    for (const field of ['ingredient', 'template', 'base', 'addition']) {
      const cell = ingredientId(raw[field])
      if (cell) {
        singles.push(cell)
      }
    }
    if (singles.length) {
      return { kind: 'list', cells: [], extra: singles }
    }
    if (Array.isArray(raw.ingredients)) {
      return { kind: 'list', cells: [], extra: (raw.ingredients as unknown[]).map(ingredientId).filter((cell): cell is Cell => !!cell) }
    }
    return null
  }
  catch {
    return null
  }
})

function plainId(cell: Cell): string {
  return cell.id?.replace(/^#/, '') ?? ''
}

function iconSrc(cell: Cell): string | null {
  if (!cell.id || cell.tag) {
    return null
  }
  return props.iconUrl(cell.id)
}

function fallbackCells(): Cell[] {
  return props.recipe.ingredients.map(value => ({ id: value, tag: value.startsWith('#') || value.startsWith('ore:') }))
}
</script>

<template>
  <div class="mc-wiki-recipe-grid">
    <template v-if="parsed?.kind === 'shaped'">
      <div class="mc-wiki-craft-table">
        <div v-for="(row, r) in parsed.cells" :key="r" class="mc-wiki-craft-row">
          <span v-for="(cell, c) in row" :key="c" class="mc-wiki-craft-cell">
            <WikiIcon v-if="cell" :src="iconSrc(cell)" :label="plainId(cell)" :size="28" />
          </span>
        </div>
      </div>
    </template>
    <template v-else>
      <div class="mc-wiki-craft-list">
        <span v-for="(cell, index) in parsed?.extra.length ? parsed.extra : fallbackCells()" :key="index" class="mc-wiki-craft-cell">
          <WikiIcon :src="iconSrc(cell)" :label="plainId(cell)" :size="28" />
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
