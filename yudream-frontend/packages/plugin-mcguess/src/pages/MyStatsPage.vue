<script setup lang="ts">
import { YdLoadingOverlay } from '@yudream/components'
import type { McguessPluginModel } from '../composables/useMcguessPlugin'
import { YdButton, YdCard, YdIcon, YdPageHeader, YdPageMain, YdStat, YdStats } from '@yudream/components'

defineProps<{ model: McguessPluginModel }>()
</script>

<template>
  <YdPageHeader title="我的猜谜战绩" class="mb-0">
    <YdButton variant="outline" :loading="model.loading" @click="model.loadMyStats"><YdIcon name="i-ri:refresh-line" />刷新</YdButton>
  </YdPageHeader>
  <YdPageMain>
    <YdLoadingOverlay :loading="model.loading" v-if="model.myStatsEmpty"><YdCard   class="mcguess-empty-card">
      <YdIcon name="i-ri:treasure-map-line" />
      <h3>还没有猜谜战绩</h3>
      <p>在 QQ 群内发送 <code>/猜物</code>、<code>/猜生物</code>、<code>/猜合成</code>、<code>/迷雾</code>、<code>/快答</code>、<code>/宾果</code>、<code>/找茬</code> 或 <code>/比大小</code> 参与游戏，猜测后会自动记录战绩。未绑定 QQ 时请先完成绑定。</p>
    </YdCard></YdLoadingOverlay>
    <template v-else-if="model.myStatsView">
      <YdLoadingOverlay :loading="model.loading"><YdStats>
        <YdStat title="猜物 · 参与 / 获胜" :value="`${model.myStatsView.itemPlayed} / ${model.myStatsView.itemWins}`" />
        <YdStat title="猜生物 · 参与 / 获胜" :value="`${model.myStatsView.mobPlayed} / ${model.myStatsView.mobWins}`" />
        <YdStat title="猜合成 · 参与 / 获胜" :value="`${model.myStatsView.recipePlayed} / ${model.myStatsView.recipeWins}`" />
        <YdStat title="迷雾 · 参与 / 获胜" :value="`${model.myStatsView.fogPlayed} / ${model.myStatsView.fogWins}`" />
        <YdStat title="快答 · 参与 / 获胜" :value="`${model.myStatsView.quizPlayed} / ${model.myStatsView.quizWins}`" />
        <YdStat title="宾果 · 参与 / 获胜" :value="`${model.myStatsView.bingoPlayed} / ${model.myStatsView.bingoWins}`" />
        <YdStat title="找茬 · 参与 / 获胜" :value="`${model.myStatsView.spotPlayed} / ${model.myStatsView.spotWins}`" />
        <YdStat title="图鉴 · 已收集" :value="model.myStatsView.collectionCount" />
        <YdStat title="比大小 · 历史最佳" :value="model.myStatsView.holBest > 0 ? `${model.myStatsView.holBest} 连胜` : '-'" />
        <YdStat title="胜率" :value="model.winRate" />
        <YdStat title="总猜测" :value="model.myStatsView.totalGuesses" />
      </YdStats></YdLoadingOverlay>
      <p class="mcguess-muted">最近参与：{{ model.formatTime(model.myStatsView.updatedAt) }}</p>
    </template>
    <YdLoadingOverlay :loading="true" v-else><YdCard   class="mcguess-empty-card"><p class="mcguess-muted">战绩加载中…</p></YdCard></YdLoadingOverlay>
  </YdPageMain>
</template>
