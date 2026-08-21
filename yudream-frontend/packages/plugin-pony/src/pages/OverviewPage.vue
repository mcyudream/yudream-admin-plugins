<script setup lang="ts">
import type { PonyPluginModel } from '../composables/usePonyPlugin'
import { FaButton, FaCard, FaIcon, FaPageHeader, FaPageMain } from '@yudream/components'

const props = defineProps<{ model: PonyPluginModel }>()
const overview = () => props.model.overview
</script>

<template>
  <FaPageHeader title="小马归位总览" class="mb-0">
    <FaButton variant="outline" :loading="model.loading" @click="model.loadOverview"><FaIcon name="i-ri:refresh-line" />刷新</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <div v-loading="model.loading" class="pony-stat-grid">
      <FaCard class="pony-stat-card">
        <span>累计对局</span>
        <strong>{{ overview()?.gamesTotal ?? '-' }}</strong>
      </FaCard>
      <FaCard class="pony-stat-card">
        <span>进行中</span>
        <strong>{{ overview()?.gamesPlaying ?? '-' }}</strong>
      </FaCard>
      <FaCard class="pony-stat-card">
        <span>已完成</span>
        <strong>{{ overview()?.gamesWon ?? '-' }}</strong>
      </FaCard>
      <FaCard class="pony-stat-card">
        <span>参与玩家</span>
        <strong>{{ overview()?.playersTotal ?? '-' }}</strong>
      </FaCard>
    </div>

    <FaCard class="pony-help-card">
      <h3>群内玩法</h3>
      <div class="pony-help-grid">
        <div>
          <h4><FaIcon name="i-ri:gamepad-line" />开局</h4>
          <p><code>/小马</code> 开始一局 8×8 棋盘，可附加棋盘尺寸（6-9），如 <code>/小马 6</code>。</p>
          <p>每种颜色恰好 1 匹小马，每行每列均有且仅有 1 匹，小马两两不能相邻（含斜角）。</p>
        </div>
        <div>
          <h4><FaIcon name="i-ri:map-pin-line" />放马与标记</h4>
          <p><code>/马 3 5</code> 把第 3 列第 5 行放上小马；放对会自动把该行、该列、周围一圈与同色区域标记为 ×。</p>
          <p><code>/标 3 5</code> 手动给格子标 × 或取消标记，被标记的格子不能放马。</p>
        </div>
        <div>
          <h4><FaIcon name="i-ri:trophy-line" />战绩</h4>
          <p>放错会扣 1 点生命，生命耗尽对局失败并揭晓答案；全部归位即胜利。</p>
          <p><code>/小马战绩</code> 查看个人战绩（需绑定账号），<code>/小马排行</code> 查看排行。</p>
        </div>
        <div>
          <h4><FaIcon name="i-ri:grid-line" />棋盘</h4>
          <p>棋盘每格标有行列序号：左侧为行号、底部为列号，按「列 行」发送坐标。</p>
          <p>谜题自动生成并校验唯一解，一定可以推理出最终解。</p>
        </div>
      </div>
    </FaCard>
  </FaPageMain>
</template>
