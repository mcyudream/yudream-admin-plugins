<script setup lang="ts">
import type { ShopProductSummary } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaIcon } from '@yudream/components'
import { computed } from 'vue'
import { displayUserName, formatAmount } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  product: ShopProductSummary
}>()

const emit = defineEmits<{
  open: [product: ShopProductSummary]
}>()

const coverThumb = computed(() => {
  const cover = props.product.coverImage
  return cover ? props.sdk.files.thumbUrl(cover, { maxEdge: 480 }) : ''
})
const coverFull = computed(() => {
  const cover = props.product.coverImage
  return cover ? props.sdk.files.assetUrl(cover) : ''
})
const soldCount = computed(() => Number(props.product.soldCount) || 0)
const stockText = computed(() => props.product.stock < 0 ? '库存充足' : `仅剩 ${props.product.stock} 件`)
const soldOut = computed(() => props.product.stock === 0)
</script>

<template>
  <article
    class="shop-product-card"
    role="button"
    tabindex="0"
    @click="emit('open', product)"
    @keydown.enter="emit('open', product)"
  >
    <div class="shop-product-card-cover">
      <img
        v-if="coverThumb"
        :src="coverThumb"
        :alt="product.title"
        loading="lazy"
        @error="($event.target as HTMLImageElement).src = coverFull"
      >
      <div v-else class="shop-product-card-cover-placeholder">
        <FaIcon name="i-ri:image-line" class="text-3xl" />
      </div>
      <span v-if="soldOut" class="shop-product-card-soldout">已售罄</span>
    </div>
    <div class="shop-product-card-body">
      <h3>{{ product.title }}</h3>
      <p v-if="product.summary" class="shop-product-card-summary">{{ product.summary }}</p>
      <div class="shop-product-card-price">
        <span class="shop-product-card-amount">
          {{ product.assetSymbol || '¥' }}{{ formatAmount(product.price) }}
        </span>
        <span class="shop-product-card-asset">{{ product.assetCode }}</span>
      </div>
      <div class="shop-product-card-meta">
        <span><FaIcon name="i-ri:user-line" />{{ displayUserName(product.owner) }}</span>
        <span><FaIcon name="i-ri:shopping-bag-3-line" />已售 {{ soldCount }}</span>
        <span class="shop-product-card-stock">{{ stockText }}</span>
      </div>
    </div>
  </article>
</template>
