<script setup lang="ts">
import type { WordlePluginModel } from '../composables/useWordlePlugin'
import { FaButton, FaCard, FaIcon, FaPageHeader, FaPageMain } from '@yudream/components'

const props = defineProps<{ model: WordlePluginModel }>()
const overview = () => props.model.overview
</script>

<template>
  <FaPageHeader title="猜词游戏总览" class="mb-0">
    <FaButton variant="outline" :loading="model.loading" @click="model.loadOverview"><FaIcon name="i-ri:refresh-line" />刷新</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <div v-loading="model.loading" class="wordle-stat-grid">
      <FaCard class="wordle-stat-card">
        <span>累计对局</span>
        <strong>{{ overview()?.gamesTotal ?? '-' }}</strong>
      </FaCard>
      <FaCard class="wordle-stat-card">
        <span>进行中</span>
        <strong>{{ overview()?.gamesPlaying ?? '-' }}</strong>
      </FaCard>
      <FaCard class="wordle-stat-card">
        <span>已猜中</span>
        <strong>{{ overview()?.gamesWon ?? '-' }}</strong>
      </FaCard>
      <FaCard class="wordle-stat-card">
        <span>自定义词条</span>
        <strong>{{ overview()?.customWords ?? '-' }}</strong>
      </FaCard>
      <FaCard class="wordle-stat-card">
        <span>参与玩家</span>
        <strong>{{ overview()?.playersTotal ?? '-' }}</strong>
      </FaCard>
    </div>

    <FaCard class="wordle-help-card">
      <h3>群内玩法</h3>
      <div class="wordle-help-grid">
        <div>
          <h4><FaIcon name="i-ri:gamepad-line" />开局</h4>
          <p><code>/猜单词</code> 开始一局英文单词（默认词长 5），可附加词长，如 <code>/猜单词 6</code>。</p>
          <p><code>/猜成语</code> 开始一局四字成语。</p>
          <p>附加「困难」开启困难模式：<code>/猜单词 困难</code>，困难模式必须沿用已揭示的提示。</p>
        </div>
        <div>
          <h4><FaIcon name="i-ri:keyboard-line" />猜词</h4>
          <p><code>/猜 crane</code> 提交一次猜测；<code>/猜 随机</code> 随机挑一个未猜过的词。</p>
          <p><code>/猜词状态</code> 查看当前棋盘，<code>/结束猜词</code> 提前揭晓答案。</p>
        </div>
        <div>
          <h4><FaIcon name="i-ri:trophy-line" />战绩</h4>
          <p><code>/猜词战绩</code> 查看个人战绩（需绑定账号），<code>/猜词排行</code> 查看群内排行。</p>
          <p><code>/猜词帮助</code> 查看完整指令说明。</p>
        </div>
        <div>
          <h4><FaIcon name="i-ri:grid-line" />图例</h4>
          <p>🟩 字母与位置都正确，🟨 字母存在但位置不对，⬜ 字母不存在。</p>
          <p>管理端可在「词条管理」中补充自定义词库，禁用词条不会进入答案池。</p>
        </div>
      </div>
    </FaCard>
  </FaPageMain>
</template>
