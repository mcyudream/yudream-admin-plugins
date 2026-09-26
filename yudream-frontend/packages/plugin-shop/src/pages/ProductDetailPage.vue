<script setup lang="ts">
import type { ShopProductDetail } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { FaAlert, FaAvatar, FaButton, FaIcon, FaNumberField, FaPageHeader, FaPageMain, FaTag, useFaModal, useFaToast } from '@yudream/components'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createShopApi } from '../api/shop-api'
import MarkdownPreview from '../components/MarkdownPreview.vue'
import { displayUserName, errorMessage, formatAmount, formatTime, hasPermission } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const route = useRoute()
const api = createShopApi(props.sdk)
const toast = useFaToast()
const confirm = useFaModal()

const loading = ref(false)
const product = ref<ShopProductDetail | null>(null)
const activeImage = ref('')
const quantity = ref(1)
const purchasing = ref(false)
const walletAvailable = ref(true)
const balance = ref<string | null>(null)

const productId = computed(() => String(route.query.id || ''))
const currentUserId = computed(() => String(props.sdk.account?.userId ?? ''))
const canUse = computed(() => hasPermission(props.sdk.account?.permissions, 'plugin:shop:use'))
const isOwnProduct = computed(() => !!product.value && product.value.ownerId === currentUserId.value)
const soldOut = computed(() => !!product.value && product.value.stock === 0)
const maxQuantity = computed(() => {
  if (!product.value || product.value.stock < 0) {
    return 99
  }
  return Math.max(1, Math.min(99, product.value.stock))
})
const totalAmount = computed(() => (Number(product.value?.price) || 0) * quantity.value)
const imageUrls = computed(() => (product.value?.images ?? []).map(url => props.sdk.files.assetUrl(url)))
const buyDisabledReason = computed(() => {
  if (!product.value) return ''
  if (!canUse.value) return '当前账号没有购买商品的权限'
  if (!walletAvailable.value) return '钱包插件未启用，暂时无法购买'
  if (isOwnProduct.value) return '这是你自己上架的商品'
  if (soldOut.value) return '商品已售罄'
  return ''
})

async function load() {
  if (!productId.value) {
    product.value = null
    return
  }
  loading.value = true
  try {
    product.value = await api.plazaProductDetail(productId.value)
    activeImage.value = imageUrls.value[0] || ''
    quantity.value = 1
    void loadBalance()
  }
  catch (error) {
    product.value = null
    toast.error(errorMessage(error, '加载商品详情失败'))
  }
  finally {
    loading.value = false
  }
}

async function loadBalance() {
  if (!product.value) {
    return
  }
  try {
    const result = await api.myBalance(product.value.assetCode)
    walletAvailable.value = result.available
    balance.value = result.balance
  }
  catch {
    walletAvailable.value = false
    balance.value = null
  }
}

function selectImage(url: string) {
  activeImage.value = url
}

function confirmPurchase() {
  if (!product.value || buyDisabledReason.value) {
    return
  }
  const item = product.value
  confirm.confirm({
    title: '确认购买',
    content: `以 ${item.assetSymbol || '¥'}${formatAmount(item.price)} × ${quantity.value} = ${item.assetSymbol || '¥'}${formatAmount(totalAmount.value)} ${item.assetCode} 购买「${item.title}」，支付后立即从钱包扣款，确认购买吗？`,
    onConfirm: () => submitPurchase(item),
  })
}

async function submitPurchase(item: ShopProductDetail) {
  if (purchasing.value) {
    return
  }
  purchasing.value = true
  try {
    const order = await api.purchase(item.id, quantity.value)
    if (order.status === 'DELIVERED') {
      toast.success('购买成功，已发货')
    }
    else if (order.status === 'DELIVERING' || order.status === 'PAID') {
      toast.success('购买成功，发货处理中')
    }
    else if (order.status === 'REFUNDED') {
      toast.warning('发货失败，货款已退回钱包')
    }
    else {
      toast.warning('购买完成，但发货失败，请联系管理员')
    }
    void router.push({ path: '/platform/plugins/shop/orders' })
  }
  catch (error) {
    toast.error(errorMessage(error, '购买失败'))
    void load()
  }
  finally {
    purchasing.value = false
  }
}

watch(productId, () => { void load() })
onMounted(() => { void load() })
</script>

<template>
  <section class="shop-page">
    <FaPageHeader title="商品详情" class="mb-0">
      <FaButton variant="outline" @click="router.push({ path: '/platform/plugins/shop' })">
        <FaIcon name="i-ri:arrow-left-line" />
        返回广场
      </FaButton>
    </FaPageHeader>
    <FaPageMain>
      <div v-loading="loading">
        <div v-if="!loading && !product" class="shop-plaza-empty">
          <FaIcon name="i-ri:store-2-line" class="text-4xl text-muted-foreground" />
          <p>商品不存在或已下架</p>
        </div>
        <div v-else-if="product" class="grid gap-4">
          <div class="shop-detail-layout">
            <div class="grid gap-3">
              <div class="shop-detail-gallery-main">
                <img
                  v-if="activeImage"
                  :src="activeImage"
                  :alt="product.title"
                >
                <div v-else class="shop-product-card-cover-placeholder shop-detail-gallery-placeholder">
                  <FaIcon name="i-ri:image-line" class="text-4xl" />
                </div>
              </div>
              <div v-if="imageUrls.length > 1" class="shop-detail-gallery-thumbs">
                <img
                  v-for="url in imageUrls"
                  :key="url"
                  :src="url"
                  :alt="product.title"
                  :class="{ 'shop-detail-thumb-active': url === activeImage }"
                  loading="lazy"
                  @click="selectImage(url)"
                >
              </div>
            </div>

            <div class="grid content-start gap-4">
              <div class="grid gap-2">
                <div class="flex flex-wrap items-center gap-2">
                  <h2 class="text-xl font-semibold">{{ product.title }}</h2>
                  <FaTag variant="secondary">{{ product.typeDisplayName || product.type }}</FaTag>
                </div>
                <p v-if="product.summary" class="text-sm text-muted-foreground">{{ product.summary }}</p>
              </div>

              <div class="shop-detail-price-row">
                <span class="shop-detail-price">{{ product.assetSymbol || '¥' }}{{ formatAmount(product.price) }}</span>
                <span class="shop-product-card-asset">{{ product.assetCode }}</span>
              </div>

              <div class="grid gap-1 text-sm text-muted-foreground">
                <span>库存：{{ product.stock < 0 ? '不限' : product.stock }} · 已售 {{ Number(product.soldCount) || 0 }}</span>
                <span>上架时间：{{ formatTime(product.createdAt) }}</span>
              </div>

              <div class="flex items-center gap-2">
                <FaAvatar :src="product.owner?.avatar || ''" :fallback="displayUserName(product.owner)" class="size-8" />
                <span class="text-sm">{{ displayUserName(product.owner) }}</span>
              </div>

              <FaAlert v-if="buyDisabledReason">
                <template #description>{{ buyDisabledReason }}</template>
              </FaAlert>
              <FaAlert v-else-if="balance !== null">
                <template #description>
                  当前钱包余额：{{ formatAmount(balance) }} {{ product.assetCode }}
                </template>
              </FaAlert>

              <div class="flex flex-wrap items-center gap-3">
                <FaNumberField v-model="quantity" :min="1" :max="maxQuantity" :step="1" :disabled="!!buyDisabledReason" />
                <span class="text-sm text-muted-foreground">
                  合计 {{ product.assetSymbol || '¥' }}{{ formatAmount(totalAmount) }} {{ product.assetCode }}
                </span>
                <FaButton :disabled="!!buyDisabledReason" :loading="purchasing" @click="confirmPurchase">
                  <FaIcon name="i-ri:shopping-cart-2-line" />
                  立即购买
                </FaButton>
              </div>
            </div>
          </div>

          <div class="shop-detail-description">
            <h3 class="mb-3 text-base font-semibold">商品介绍</h3>
            <MarkdownPreview v-if="product.descriptionMd" :sdk="sdk" :content="product.descriptionMd" />
            <p v-else class="text-sm text-muted-foreground">卖家没有填写详细介绍。</p>
          </div>
        </div>
      </div>
    </FaPageMain>
  </section>
</template>
