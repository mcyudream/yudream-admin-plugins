<script setup lang="ts">
import type { PonyPluginModel } from '../composables/usePonyPlugin'
import { FaButton, FaCard, FaIcon, FaPageHeader, FaPageMain, FaTag } from '@yudream/components'

defineProps<{ model: PonyPluginModel }>()
</script>

<template>
  <FaPageHeader title="我的小马战绩" class="mb-0">
    <FaButton variant="outline" :loading="model.loading" @click="model.loadMyStats"><FaIcon name="i-ri:refresh-line" />刷新</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <FaCard v-if="model.myStatsEmpty" v-loading="model.loading" class="pony-empty-card">
      <FaIcon name="i-ri:gamepad-line" />
      <h3>还没有小马归位战绩</h3>
      <p>在 QQ 群内发送 <code>/小马</code> 开局，成功放马后会自动记录战绩。未绑定 QQ 时请先完成绑定。</p>
    </FaCard>
    <template v-else-if="model.myStatsView">
      <div v-loading="model.loading" class="pony-stat-grid">
        <FaCard class="pony-stat-card">
          <span>总场次</span>
          <strong>{{ model.myStatsView.played }}</strong>
        </FaCard>
        <FaCard class="pony-stat-card">
          <span>总胜场</span>
          <strong>{{ model.myStatsView.wins }}</strong>
        </FaCard>
        <FaCard class="pony-stat-card">
          <span>胜率</span>
          <strong>{{ model.winRate }}</strong>
        </FaCard>
        <FaCard class="pony-stat-card">
          <span>累计放马</span>
          <strong>{{ model.myStatsView.horsesPlaced }}</strong>
        </FaCard>
        <FaCard class="pony-stat-card">
          <span>当前连胜</span>
          <strong>{{ model.myStatsView.currentStreak }}</strong>
        </FaCard>
        <FaCard class="pony-stat-card">
          <span>最佳连胜</span>
          <strong>{{ model.myStatsView.bestStreak }}</strong>
        </FaCard>
      </div>

      <FaCard class="pony-panel-card">
        <h3>战绩概览</h3>
        <div class="pony-mode-row">
          <div>
            <FaTag variant="secondary">胜 / 总</FaTag>
            <strong>{{ model.myStatsView.wins }} / {{ model.myStatsView.played }}</strong>
            <span>群内协作归位</span>
          </div>
          <div>
            <FaTag variant="secondary">连胜</FaTag>
            <strong>{{ model.myStatsView.currentStreak }} / {{ model.myStatsView.bestStreak }}</strong>
            <span>当前 / 最佳</span>
          </div>
        </div>
      </FaCard>
      <p class="pony-muted">最近参与：{{ model.formatTime(model.myStatsView.updatedAt) }}</p>
    </template>
    <FaCard v-else v-loading="true" class="pony-empty-card"><p class="pony-muted">战绩加载中…</p></FaCard>
  </FaPageMain>
</template>
