<script setup lang="ts">
import { FaButton, FaIcon, FaInput, FaTag } from '@yudream/components'
import { computed, ref } from 'vue'
import {
  ICON_CATEGORIES,
  NATIVE_ICON_TOKENS,
  findCatalogIcon,
  iconCatalogDeduped,
  remixIconName,
} from '../composables/nav-icons'
import type { IconCategory } from '../composables/nav-icons'

const props = withDefaults(defineProps<{
  modelValue?: string
  /** 展示用的默认图标（未覆盖时） */
  fallback?: string
  disabled?: boolean
  /** 清空后是否允许空值 */
  allowEmpty?: boolean
  /** 网格最大高度（px） */
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
    if (item.name.replace(/^i-ri:/, '').toLowerCase().includes(keyword)) return true
    if (item.token && item.token.toLowerCase().includes(keyword)) return true
    return false
  })
})

const displayIcon = computed(() => {
  const value = props.modelValue?.trim()
  if (!value) return props.fallback || ''
  return remixIconName(value) || value
})

const selectedMeta = computed(() => findCatalogIcon(props.modelValue))

const isNativeToken = computed(() => {
  const value = props.modelValue?.trim()
  return !!value && Object.prototype.hasOwnProperty.call(NATIVE_ICON_TOKENS, value)
})

const isCustomValue = computed(() => {
  const value = props.modelValue?.trim()
  if (!value) return false
  const normalized = remixIconName(value) || value
  return !pool.value.some(item => item.name === normalized)
})

const gridStyle = computed(() => ({
  maxHeight: `${props.gridMaxHeight}px`,
}))

function commit(name: string) {
  const value = name.trim()
  if (props.disabled) return
  if (!value) {
    emit('update:modelValue', props.allowEmpty ? '' : (props.fallback || ''))
    return
  }
  emit('update:modelValue', value)
}

function select(name: string) {
  if (props.disabled) return
  if (props.modelValue?.trim() === name) {
    commit(props.allowEmpty ? '' : (props.fallback || name))
    return
  }
  commit(name)
}

function clear() {
  if (props.disabled) return
  commit('')
}

function applyCustom() {
  if (props.disabled) return
  const value = customName.value.trim()
  if (!value) return
  commit(value)
  showCustom.value = false
}

function toggleCustom() {
  if (props.disabled) return
  if (showCustom.value) {
    showCustom.value = false
    return
  }
  customName.value = props.modelValue?.trim() || ''
  showCustom.value = true
}

function useFallback() {
  if (props.disabled || !props.fallback) return
  commit(props.fallback)
}
</script>

<template>
  <div
    class="launcher-icon-picker"
    :class="{ 'is-disabled': disabled }"
  >
    <div class="launcher-icon-picker__bar">
      <span class="launcher-icon-picker__preview" :title="displayIcon || '未选择'">
        <FaIcon v-if="displayIcon" :name="displayIcon" />
        <FaIcon v-else name="i-ri:image-line" class="is-placeholder" />
      </span>
      <FaInput
        v-model="search"
        class="launcher-icon-picker__search"
        placeholder="搜索中文名 / i-ri:xxx / token…"
        clearable
        :disabled="disabled"
      />
      <FaButton
        size="sm"
        variant="ghost"
        :disabled="disabled"
        :class="{ 'is-active': showCustom }"
        @click="toggleCustom"
      >
        自定义
      </FaButton>
      <FaButton
        v-if="fallback && modelValue !== fallback"
        size="sm"
        variant="ghost"
        :disabled="disabled"
        @click="useFallback"
      >
        用默认
      </FaButton>
      <FaButton
        size="sm"
        variant="ghost"
        :disabled="disabled || !modelValue"
        @click="clear"
      >
        清除
      </FaButton>
    </div>

    <div v-if="showCustom" class="launcher-icon-picker__custom">
      <FaInput
        v-model="customName"
        placeholder="如 i-mdi:home-variant 或 home"
        :disabled="disabled"
        @keydown.enter.prevent="applyCustom"
      />
      <FaButton size="sm" :disabled="disabled || !customName.trim()" @click="applyCustom">
        应用
      </FaButton>
    </div>

    <div class="launcher-icon-picker__chips" role="tablist" aria-label="图标分类">
      <button
        v-for="item in categoryOptions"
        :key="item.key"
        type="button"
        role="tab"
        class="launcher-icon-picker__chip"
        :class="{ 'is-active': category === item.key }"
        :aria-selected="category === item.key"
        :disabled="disabled"
        @click="category = item.key"
      >
        {{ item.label }}
        <span class="launcher-icon-picker__chip-count">{{ item.count }}</span>
      </button>
    </div>

    <div class="launcher-icon-picker__meta">
      <template v-if="modelValue">
        当前值：<code>{{ modelValue }}</code>
        <FaTag v-if="isNativeToken" variant="outline">原生 token</FaTag>
        <FaTag v-else-if="isCustomValue" variant="outline">自定义</FaTag>
        <FaTag v-else-if="selectedMeta" variant="secondary">{{ selectedMeta.label }}</FaTag>
        <FaIcon
          v-if="displayIcon"
          :name="displayIcon"
          class="launcher-icon-picker__meta-icon"
        />
      </template>
      <span v-else class="launcher-muted">
        未覆盖——使用默认图标{{ fallback ? `（${fallback}）` : '' }}
      </span>
    </div>

    <div
      class="launcher-icon-picker__grid"
      :style="gridStyle"
      role="listbox"
      aria-label="选择图标"
    >
      <button
        v-for="icon in filteredIcons"
        :key="icon.name"
        type="button"
        role="option"
        class="launcher-icon-picker__cell"
        :class="{
          'is-active': modelValue === icon.name
            || remixIconName(modelValue) === icon.name
            || (!modelValue && fallback === icon.name),
        }"
        :title="`${icon.label} · ${icon.name}${icon.token ? ` · token=${icon.token}` : ''}`"
        :aria-selected="modelValue === icon.name"
        :disabled="disabled"
        @click="select(icon.token || icon.name)"
      >
        <FaIcon :name="icon.name" />
        <span class="launcher-icon-picker__cell-label">{{ icon.label }}</span>
      </button>
    </div>

    <div class="launcher-icon-picker__footer">
      <p v-if="!filteredIcons.length" class="launcher-muted">
        没有匹配的图标，可点「自定义」直接填图标名
      </p>
      <p v-else class="launcher-muted">
        共 {{ filteredIcons.length }} 个候选 · 点击选中，再点取消{{ allowEmpty ? '（恢复默认）' : '' }}
      </p>
    </div>
  </div>
</template>
