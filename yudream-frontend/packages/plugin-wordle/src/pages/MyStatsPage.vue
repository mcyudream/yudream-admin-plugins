<script setup lang="ts">
import type { WordlePluginModel } from '../composables/useWordlePlugin'
import { FaButton, FaCard, FaIcon, FaPageHeader, FaPageMain, FaTag } from '@yudream/components'

const props = defineProps<{ model: WordlePluginModel }>()

function distributionEntries() {
  const view = props.model.myStatsView
  if (!view) {
    return []
  }
  return Object.entries(view.winDistribution || {}).sort(([a], [b]) => Number(a) - Number(b))
}
</script>

<template>
  <FaPageHeader title="我的猜词战绩" class="mb-0">
    <FaButton variant="outline" :loading="model.loading" @click="model.loadMyStats"><FaIcon name="i-ri:refresh-line" />刷新</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <FaCard v-if="model.myStatsEmpty" v-loading="model.loading" class="wordle-empty-card">
      <FaIcon name="i-ri:gamepad-line" />
      <h3>还没有猜词战绩</h3>
      <p>在 QQ 群内发送 <code>/猜单词</code> 或 <code>/猜成语</code> 开局，猜词后会自动记录战绩。未绑定 QQ 时请先完成绑定。</p>
    </FaCard>
    <template v-else-if="model.myStatsView">
      <div v-loading="model.loading" class="wordle-stat-grid">
        <FaCard class="wordle-stat-card">
          <span>总场次</span>
          <strong>{{ model.totalPlayed }}</strong>
        </FaCard>
        <FaCard class="wordle-stat-card">
          <span>总胜场</span>
          <strong>{{ model.totalWins }}</strong>
        </FaCard>
        <FaCard class="wordle-stat-card">
          <span>胜率</span>
          <strong>{{ model.winRate }}</strong>
        </FaCard>
        <FaCard class="wordle-stat-card">
          <span>当前连胜</span>
          <strong>{{ model.myStatsView.currentStreak }}</strong>
        </FaCard>
        <FaCard class="wordle-stat-card">
          <span>最佳连胜</span>
          <strong>{{ model.myStatsView.bestStreak }}</strong>
        </FaCard>
      </div>

      <div class="wordle-two-col">
        <FaCard class="wordle-panel-card">
          <h3>模式战绩</h3>
          <div class="wordle-mode-row">
            <div>
              <FaTag variant="secondary">英文单词</FaTag>
              <strong>{{ model.myStatsView.englishWins }} / {{ model.myStatsView.englishPlayed }}</strong>
              <span>胜 / 总</span>
            </div>
            <div>
              <FaTag variant="secondary">四字成语</FaTag>
              <strong>{{ model.myStatsView.idiomWins }} / {{ model.myStatsView.idiomPlayed }}</strong>
              <span>胜 / 总</span>
            </div>
          </div>
        </FaCard>
        <FaCard class="wordle-panel-card">
          <h3>猜中分布</h3>
          <div v-if="distributionEntries().length" class="wordle-distribution">
            <div v-for="[guesses, count] in distributionEntries()" :key="guesses" class="wordle-distribution-row">
              <span class="wordle-distribution-label">{{ guesses }} 次猜中</span>
              <div class="wordle-distribution-bar">
                <div class="wordle-distribution-fill" :style="{ width: `${Math.min(100, (count / Math.max(...distributionEntries().map(([, c]) => c))) * 100)}%` }" />
              </div>
              <span>{{ count }}</span>
            </div>
          </div>
          <p v-else class="wordle-muted">还没有猜中记录。</p>
        </FaCard>
      </div>
      <p class="wordle-muted">最近参与：{{ model.formatTime(model.myStatsView.updatedAt) }}</p>
    </template>
    <FaCard v-else v-loading="true" class="wordle-empty-card"><p class="wordle-muted">战绩加载中…</p></FaCard>
  </FaPageMain>
</template>
