<script setup lang="ts">
import type { ShopVariantPayload } from '../types'
import { FaButton, FaIcon, FaInput, FaNumberField } from '@yudream/components'
import { computed } from 'vue'

/**
 * 商品型号编辑器：同一商品可选不同型号（如冰箱贴的西瓜 / 钻石图案），每个型号各自定价与库存。
 *
 * <p>有型号时商品价格与库存由型号决定（取最低价与库存合计），所以调用方应隐藏商品级价格/库存输入。
 * 型号图片字段在这里只做保留（不在界面更换），已有值会原样回传。
 */
const props = withDefaults(defineProps<{
  modelValue: ShopVariantPayload[]
  max?: number
}>(), {
  max: 20,
})

const emit = defineEmits<{
  'update:modelValue': [rows: ShopVariantPayload[]]
}>()

const rows = computed(() => props.modelValue ?? [])
const canAdd = computed(() => rows.value.length < props.max)

function patch(index: number, changes: Partial<ShopVariantPayload>) {
  emit('update:modelValue', rows.value.map((row, i) => (i === index ? { ...row, ...changes } : row)))
}

function add() {
  if (!canAdd.value) {
    return
  }
  const fallback = rows.value.length ? Number(rows.value[0].price) || 1 : 1
  emit('update:modelValue', [...rows.value, { name: '', price: fallback, stock: -1 }])
}

function remove(index: number) {
  emit('update:modelValue', rows.value.filter((_, i) => i !== index))
}
</script>

<template>
  <div class="grid gap-3">
    <div class="flex flex-wrap items-start justify-between gap-2">
      <div class="grid gap-1">
        <span class="text-sm font-medium">商品型号（可选）</span>
        <span class="text-xs text-muted-foreground">
          配置后买家可在详情页选择型号，价格与库存以型号为准（商品价取型号最低价、库存取合计）；
          不配置则按上面的商品价格与库存售卖。
        </span>
      </div>
      <FaButton type="button" size="sm" variant="outline" :disabled="!canAdd" @click="add">
        <FaIcon name="i-ri:add-line" />
        添加型号
      </FaButton>
    </div>

    <div v-if="!rows.length" class="shop-variant-empty">
      暂无型号：当前商品只有一种规格，买家直接按商品价格与库存下单。
    </div>

    <div v-else class="grid gap-2">
      <div v-for="(row, index) in rows" :key="row.id || index" class="shop-variant-item">
        <div class="shop-variant-item-head">
          <span class="shop-variant-index">{{ index + 1 }}</span>
          <FaInput
            :model-value="row.name"
            placeholder="型号名称，如：西瓜 / 钻石"
            @update:model-value="patch(index, { name: String($event) })"
          />
          <FaButton type="button" size="sm" variant="outline" @click="remove(index)">
            <FaIcon name="i-ri:delete-bin-4-line" />
            删除
          </FaButton>
        </div>
        <div class="shop-variant-item-body">
          <label class="shop-variant-field">
            <span>价格</span>
            <FaNumberField
              :model-value="Number(row.price)"
              :min="0.01"
              :step="0.01"
              class="w-full"
              @update:model-value="patch(index, { price: Number($event) })"
            />
          </label>
          <label class="shop-variant-field">
            <span>库存（-1 表示不限）</span>
            <FaNumberField
              :model-value="Number(row.stock)"
              :min="-1"
              :step="1"
              class="w-full"
              @update:model-value="patch(index, { stock: Number($event) })"
            />
          </label>
        </div>
      </div>
    </div>
  </div>
</template>
