<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import ProductPlaza from '../components/ProductPlaza.vue'

/**
 * 积分商城：只列官方投放的消耗类商品（BURN 结算：只扣买家钱包，没有收款方）。
 * 与「玩家市场」分开呈现，玩家之间互相买卖的商品不会出现在这里。
 */
defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()
</script>

<template>
  <ProductPlaza
    :sdk="sdk"
    settlement="BURN"
    title="积分商城"
    description="官方投放的兑换商品，使用积分或指定货币兑换；消耗的部分不会转给任何账号"
    empty-hint="暂时没有可兑换的官方商品"
    empty-icon="i-ri:gift-2-line"
  />
</template>
