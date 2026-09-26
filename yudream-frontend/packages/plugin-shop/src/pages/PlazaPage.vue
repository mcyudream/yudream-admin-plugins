<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import ProductPlaza from '../components/ProductPlaza.vue'

/**
 * 玩家市场：只列玩家发布、用钱包货币互相买卖的商品（SELLER 结算：钱从买家转给卖家）。
 * 官方消耗类商品在「积分商城」单独呈现，两者不混在同一个列表里。
 */
defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()
</script>

<template>
  <ProductPlaza
    :sdk="sdk"
    settlement="SELLER"
    title="玩家市场"
    description="玩家发布的商品，用钱包货币直接买卖；付款经钱包转给卖家"
    empty-hint="暂时没有玩家上架中的商品"
    empty-icon="i-ri:store-2-line"
    show-publish
  />
</template>
