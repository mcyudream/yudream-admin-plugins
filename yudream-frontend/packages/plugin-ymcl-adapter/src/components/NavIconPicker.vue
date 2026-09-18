<script setup lang="ts">
import { FaButton, FaIcon, FaInput, FaTag } from '@yudream/components'
import { computed, ref } from 'vue'
import {
  ICON_CATEGORIES,
  findCatalogIcon,
  iconCatalogDeduped,
  isNativeIconValue,
  preferredIconValue,
  remixIconName,
} from '../composables/nav-icons'
import type { IconCategory } from '../composables/nav-icons'

const props = withDefaults(defineProps<{
  modelValue?: string
  /** 展示用的默认图标（未覆盖时） */
  fallback?: string
  disabled?: boolean
  allowEmpty?: boolean
  gridMaxHeight?: number
}>(), {
  modelValue: '',
  fallback: '',
  disabled: false,
  allowEmpty: true,
  gridMaxHeight: 220,
})

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const search = ref('')
const category = ref<IconCategory | 'all'>('all')
const customName = ref('')
const showCustom = ref(false)

const pool = computed(() => iconCatalogDeduped())

/** 当前规范值：内置优先写 token。 */
const currentValue = computed(() => preferredIconValue(props.modelValue))

const categoryCounts = computed(() => {
  const map = new Map<string, number>()
  for (const item of pool.value) {
    map.set(item.category, (map.get(item.category) || 0) + 1)
  }
  return map
})

const categoryOptions = computed(() => [
  { key: 'all' as const, label: '全部', count: pool.value.length },
  ...ICON_CATEGORIES
    .map(item => ({
      key: item.key,
      label: item.label,
      count: categoryCounts.value.get(item.key) || 0,
    }))
    .filter(item => item.count > 0),
])

const filteredIcons = computed(() => {
  let list = pool.value
  if (category.value !== 'all') {
    list = list.filter(item => item.category === category.value)
  }
  const keyword = search.value.trim().toLowerCase()
  if (!keyword) return list
  return list.filter((item) => {
    if (item.name.toLowerCase().includes(keyword)) return true
    if (item.label.toLowerCase().includes(keyword)) return true
    if (item.token && item.token.toLowerCase().includes(keyword)) return true
    if (item.name.replace(/^i-ri:/, '').toLowerCase().includes(keyword)) return true
    return false
  })
})

const displayIcon = computed(() => {
  const value = currentValue.value || props.fallback?.trim()
  if (!value) return ''
  return remixIconName(value) || value
})

const selectedMeta = computed(() => findCatalogIcon(currentValue.value))
const nativeSelected = computed(() => isNativeIconValue(currentValue.value))

const isCustomValue = computed(() => {
  const value = currentValue.value
  if (!value) return false
  if (nativeSelected.value) return false
  return !pool.value.some(item => item.name === value)
})

const gridStyle = computed(() => ({
  maxHeight: `${props.gridMaxHeight}px`,
}))

function optionValue(item: { name: string, token?: string }) {
  // 内置图标一律写入启动器 token，避免下发 i-ri 字符串
  return item.token || item.name
}

function isSelected(item: { name: string, token?: string }) {
  return currentValue.value === optionValue(item)
}

function commit(value: string) {
  if (props.disabled) return
  const next = value.trim()
  if (!next) {
    emit('update:modelValue', props.allowEmpty ? '' : preferredIconValue(props.fallback))
    return
  }
  emit('update:modelValue', preferredIconValue(next))
}

function select(item: { name: string, token?: string }) {
  if (props.disabled) return
  const value = optionValue(item)
  if (currentValue.value === value) {
    commit(props.allowEmpty ? '' : (props.fallback || value))
    return
  }
  commit(value)
}

function clear() {
  if (props.disabled) return
  commit('')
}

function toggleCustom() {
  if (props.disabled) return
  if (showCustom.value) {
    showCustom.value = false
    return
  }
  customName.value = currentValue.value || ''
  showCustom.value = true
}

function applyCustom() {
  if (props.disabled) return
  const value = customName.value.trim()
  if (!value) return
  commit(value)
  showCustom.value = false
}

function useFallback() {
  if (props.disabled || !props.fallback) return
  // 默认值若是内置 token 对应的 Remix 名，写回 token
  commit(preferredIconValue(props.fallback))
}
</script>

<template>
  <div class="ymcl-icon-picker" :class="{ 'is-disabled': disabled }">
    <div class="ymcl-icon-picker__bar">
      <span class="ymcl-icon-picker__preview" :title="displayIcon || '未选择'">
        <FaIcon v-if="displayIcon" :name="displayIcon" />
        <FaIcon v-else name="i-ri:image-line" class="is-placeholder" />
      </span>
      <FaInput
        v-model="search"
        class="ymcl-icon-picker__search"
        placeholder="搜索中文 / token / i-ri:xxx…"
        clearable
        :disabled="disabled"
      />
      <FaButton size="sm" variant="ghost" :disabled="disabled" @click="toggleCustom">
        自定义
      </FaButton>
      <FaButton
        v-if="fallback && preferredIconValue(modelValue) !== preferredIconValue(fallback)"
        size="sm"
        variant="ghost"
        :disabled="disabled"
        @click="useFallback"
      >
        用默认
      </FaButton>
      <FaButton size="sm" variant="ghost" :disabled="disabled || !modelValue" @click="clear">
        清除
      </FaButton>
    </div>

    <div v-if="showCustom" class="ymcl-icon-picker__custom">
      <FaInput
        v-model="customName"
        placeholder="如 home 或 i-mdi:home-variant"
        :disabled="disabled"
        @keydown.enter.prevent="applyCustom"
      />
      <FaButton size="sm" :disabled="disabled || !customName.trim()" @click="applyCustom">
        应用
      </FaButton>
    </div>

    <div class="ymcl-icon-picker__chips" role="tablist" aria-label="图标分类">
      <button
        v-for="item in categoryOptions"
        :key="item.key"
        type="button"
        role="tab"
        class="ymcl-icon-picker__chip"
        :class="{ 'is-active': category === item.key }"
        :aria-selected="category === item.key"
        :disabled="disabled"
        @click="category = item.key"
      >
        {{ item.label }}
        <span class="ymcl-icon-picker__chip-count">{{ item.count }}</span>
      </button>
    </div>

    <div class="ymcl-icon-picker__meta">
      <template v-if="currentValue">
        当前值：<code>{{ currentValue }}</code>
        <FaTag v-if="nativeSelected" variant="outline">内置图标</FaTag>
        <FaTag v-else-if="isCustomValue" variant="outline">自定义</FaTag>
        <FaTag v-else-if="selectedMeta" variant="secondary">{{ selectedMeta.label }}</FaTag>
        <FaIcon v-if="displayIcon" :name="displayIcon" class="ymcl-icon-picker__meta-icon" />
      </template>
      <span v-else class="ymcl-muted">
        未覆盖——使用页面默认图标{{ fallback ? `（${preferredIconValue(fallback) || fallback}）` : '' }}
      </span>
    </div>

    <div
      class="ymcl-icon-picker__grid"
      :style="gridStyle"
      role="listbox"
      aria-label="选择图标"
    >
      <button
        v-for="icon in filteredIcons"
        :key="icon.token || icon.name"
        type="button"
        role="option"
        class="ymcl-icon-picker__cell"
        :class="{ 'is-active': isSelected(icon) }"
        :title="`${icon.label} · ${optionValue(icon)}${icon.token ? ' · 内置' : ''}`"
        :aria-selected="isSelected(icon)"
        :disabled="disabled"
        @click="select(icon)"
      >
        <FaIcon :name="icon.name" />
        <span class="ymcl-icon-picker__cell-label">{{ icon.label }}</span>
        <span v-if="icon.token" class="ymcl-icon-picker__cell-badge">内置</span>
      </button>
    </div>

    <div class="ymcl-icon-picker__footer">
      <p v-if="!filteredIcons.length" class="ymcl-muted">
        没有匹配的图标，可点「自定义」直接填图标名
      </p>
      <p v-else class="ymcl-muted">
        共 {{ filteredIcons.length }} 个候选 · 带「内置」标记的会写入启动器原生 token
      </p>
    </div>
  </div>
</template>
