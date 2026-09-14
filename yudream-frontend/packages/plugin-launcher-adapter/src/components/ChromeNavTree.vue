<script setup lang="ts">
import { FaIcon, FaTag } from '@yudream/components'
import type { YmclNavKind, YmclNavNode } from '../types'

defineOptions({ name: 'ChromeNavTree' })

export interface ChromeTreeNode {
  key: string
  title: string
  icon: string
  kind: YmclNavKind
  node: YmclNavNode
  parent?: YmclNavNode
  parentKey?: string
  children?: ChromeTreeNode[]
}

defineProps<{
  nodes: ChromeTreeNode[]
  selectedKey: string
  depth?: number
}>()

const emit = defineEmits<{
  select: [key: string]
}>()

function kindLabel(kind: YmclNavKind, depth: number) {
  if (kind === 'tab') {
    return 'Tab'
  }
  return depth <= 1 ? '菜单' : '下级'
}

function kindVariant(kind: YmclNavKind, depth: number) {
  if (kind === 'tab') {
    return 'default' as const
  }
  return depth <= 1 ? 'secondary' as const : 'outline' as const
}
</script>

<template>
  <div v-for="item in nodes" :key="item.key" class="chrome-tree-block" :class="{ 'chrome-tree-block--child': (depth || 0) > 0 }">
    <button
      class="chrome-tree-node"
      :class="{
        'is-active': selectedKey === item.key,
        'chrome-tree-node--leaf': (depth || 0) > 1,
      }"
      type="button"
      @click="emit('select', item.key)"
    >
      <span class="chrome-tree-node__main">
        <span class="chrome-tree-node__icon">
          <FaIcon :name="item.icon" />
        </span>
        <span>{{ item.title }}</span>
        <FaTag :variant="kindVariant(item.kind, depth || 0)" class="text-xs">
          {{ kindLabel(item.kind, depth || 0) }}
        </FaTag>
        <FaTag v-if="!item.node.visible" variant="secondary" class="text-xs">
          隐藏
        </FaTag>
      </span>
    </button>
    <ChromeNavTree
      v-if="item.children?.length"
      :nodes="item.children"
      :selected-key="selectedKey"
      :depth="(depth || 0) + 1"
      @select="emit('select', $event)"
    />
  </div>
</template>
