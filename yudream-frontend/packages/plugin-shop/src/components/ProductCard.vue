<script setup lang="ts">
import type { ShopProductSummary } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaIcon } from '@yudream/components'
import { computed } from 'vue'
import { displayProductOwner, formatAmount, isPointsRedeem } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  product: ShopProductSummary
}>()

const emit = defineEmits<{
  open: [product: ShopProductSummary]
}>()

/** 内置积分兑换商品：广场卡片上单独标注，提示这是消耗积分的兑换而非普通购买 */
const pointsRedeem = computed(() => isPointsRedeem(props.product.type))
const variants = computed(() => props.product.variants ?? [])
const ownerLabel = computed(() => displayProductOwner(props.product))
/** 有型号时展示最低价（多型号同价则不显示「起」） */
const priceText = computed(() => {
  if (!variants.value.length) {
    return formatAmount(props.product.price)
  }
  const prices = variants.value.map(variant => Number(variant.price)).filter(Number.isFinite)
  const min = Math.min(...prices)
  const max = Math.max(...prices)
  return min === max ? formatAmount(min) : `${formatAmount(min)} 起`
})

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
      <span v-if="pointsRedeem" class="shop-product-card-type">
        <FaIcon name="i-ri:gift-2-line" />
        积分兑换
      </span>
      <span v-if="soldOut" class="shop-product-card-soldout">已售罄</span>
    </div>
    <div class="shop-product-card-body">
      <h3>{{ product.title }}</h3>
      <p v-if="product.summary" class="shop-product-card-summary">{{ product.summary }}</p>
      <div class="shop-product-card-price">
        <span class="shop-product-card-amount">
          {{ product.assetSymbol || '¥' }}{{ priceText }}
        </span>
        <span class="shop-product-card-asset">{{ product.assetCode }}</span>
      </div>
      <div class="shop-product-card-meta">
        <span v-if="ownerLabel"><FaIcon name="i-ri:user-line" />{{ ownerLabel }}</span>
        <span v-if="variants.length"><FaIcon name="i-ri:apps-2-line" />{{ variants.length }} 种型号</span>
        <span><FaIcon name="i-ri:shopping-bag-3-line" />已售 {{ soldCount }}</span>
        <span v-if="Number(product.perUserLimit) > 0">
          <FaIcon name="i-ri:user-settings-line" />{{ pointsRedeem ? '每人限兑' : '每人限购' }} {{ Number(product.perUserLimit) }}
        </span>
        <span class="shop-product-card-stock">{{ stockText }}</span>
      </div>
    </div>
  </article>
</template>
